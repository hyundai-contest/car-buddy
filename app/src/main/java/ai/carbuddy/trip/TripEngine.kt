package ai.carbuddy.trip

import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.TripState
import ai.carbuddy.core.model.TripState.ARRIVING
import ai.carbuddy.core.model.TripState.DRIVING
import ai.carbuddy.core.model.TripState.ENDED
import ai.carbuddy.core.model.TripState.IDLE
import ai.carbuddy.core.model.TripState.PARKED_AT_DEST
import ai.carbuddy.core.model.TripState.PAUSED
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.core.util.Geo
import ai.carbuddy.data.TripEntity
import ai.carbuddy.data.TripGapEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.data.WaypointStopEntity
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.LowBeam
import ai.carbuddy.signal.PauseClosed
import ai.carbuddy.signal.PauseKind
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.SignalResumed
import ai.carbuddy.signal.Tick
import ai.carbuddy.signal.WaypointChanged
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID

data class Transition(val tripId: String, val from: TripState, val to: TripState, val t: Long, val reason: String, val endReason: EndReason? = null)

/**
 * Trip state machine (SPEC 5장). State changes are written to [store] immediately; last-known
 * values at least every lastSignalFlushSec (SPEC 5.1). Process restarts go through [recovery].
 */
class TripEngine(
    private val store: TripStore,
    private val ctx: TripContext,
    /** false while "기록 안 함" / "기록 일시정지" (SPEC 5.8) */
    private val recording: () -> Boolean = { true },
    /** frequent place at this point (SPEC 6.4) → a no-destination arrival is final at once */
    private val frequentPlace: (Double, Double) -> Boolean = { _, _ -> false },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val cfg get() = ctx.config.trip
    var trip: TripEntity? = null
        private set
    val state: TripState get() = trip?.state?.let(TripState::valueOf) ?: IDLE
    val recovery = TripRecovery(this)

    /** Derived events (PauseClosed) produced by the last [onEvent]; the pipeline feeds them to detectors. */
    val derived = mutableListOf<SignalEvent>()

    private var movingSince: Long? = null
    private var heldKmh = 0f
    private var heldSince: Long? = null
    private var lastFlushAt = 0L
    private var lowBeamOnAt: Long? = null
    /** last signal time before the gap being recovered (the trip_gap start) */
    private var recoverGapFrom: Long? = null
    private val waypointSer = ListSerializer(Waypoint.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads the persisted active trip into memory (process start). */
    fun restore() {
        trip = store.activeTrip()
        trip?.let { syncContext(it) }
    }

    /** Updates bookkeeping fields of the active trip (recap done, ceremony step). */
    fun patch(f: (TripEntity) -> TripEntity) { trip?.let { save(f(it)) } }

    fun onEvent(e: SignalEvent): List<Transition> {
        derived.clear()
        if (e is PowerBoot || e is SignalResumed) return recovery.recover(e.t, e)
        trackMoving(e)
        val prevKmh = heldKmh
        val prevT = heldSince
        heldKmh = ctx.speedKmh
        heldSince = e.t

        val t0 = trip
        if (t0 == null) {
            val since = movingSince
            if (since != null && e.t - since >= cfg.drivingConfirmSec * 1000L && recording()) return listOf(start(since))
            return emptyList()
        }
        val t = accumulate(t0, e, prevKmh, prevT)
        if (t.recovering) {
            trip = t
            val r = resolveRecovery(t, e)
            if (r != null) return r
            if (trip?.recovering == true) return emptyList()   // still waiting for the first fix
        }
        val cur = trip?.takeIf { it !== t0 } ?: t
        val out = when (TripState.valueOf(cur.state)) {
            DRIVING, ARRIVING -> whileDriving(cur, e)
            PAUSED -> whilePaused(cur, e)
            PARKED_AT_DEST -> whileParkedAtDest(cur, e)
            IDLE, ENDED -> emptyList()
        }
        if (out.isEmpty()) {
            trip = cur
            val parkChange = e is ai.carbuddy.signal.Gear || e is ai.carbuddy.signal.DriverBelt ||
                (e is ai.carbuddy.signal.Speed && (prevKmh > 0.5f) != (ctx.speedKmh > 0.5f))
            if (e !is Tick && (parkChange || e.t - lastFlushAt >= cfg.lastSignalFlushSec * 1000L)) save(cur)
        }
        return out
    }

    // ---------------------------------------------------------------- states

    private fun noDest(t: TripEntity) = t.destLat == null && t.destName == null

    private fun whileDriving(t: TripEntity, e: SignalEvent): List<Transition> {
        val st = TripState.valueOf(t.state)
        val remain = ctx.remainSec
        if (ctx.isParked(e.t)) {
            if (!noDest(t) && atDestination()) {
                return listOf(move(t.copy(arrivedAt = t.arrivedAt ?: e.t), PARKED_AT_DEST, e.t, "parked at destination"))
            }
            val since = ctx.parkedSince() ?: e.t
            val beltOff = ctx.driverBelt == false
            val longIdle = !beltOff && e.t - since >= cfg.idleBeltOnMin * 60_000L
            if (beltOff || longIdle) {
                val loc = ctx.lastLocation
                if (noDest(t) && beltOff && loc != null && frequentPlace(loc.lat, loc.lng)) {
                    return listOf(end(t.copy(provisional = true, provisionalEndAt = since), since, EndReason.NO_DEST_PLACE))
                }
                return listOf(move(pausedCopy(t, since, loc?.lat, loc?.lng, ctx.remainSec), PAUSED, e.t,
                    if (beltOff) "parked + belt off" else "parked ${cfg.idleBeltOnMin} min with belt on"))
            }
            return emptyList()
        }
        if (st == DRIVING && !noDest(t) && remain != null && remain <= cfg.arrivingRemainSec && e.t - ctx.remainT < 120_000) {
            return listOf(move(t, ARRIVING, e.t, "remain ${remain}s"))
        }
        if (st == ARRIVING && (remain != null && remain > cfg.arrivingExitRemainSec || noDest(t))) {
            return listOf(move(t, DRIVING, e.t, "remain grew"))
        }
        return emptyList()
    }

    /**
     * A stop starts. No destination: provisional arrival. With a destination the stop is provisional
     * (it may be the real end) when it looks like one: near the destination, at a frequent place,
     * guidance already over, or the car was heading away from the destination. Otherwise it is a
     * stop on the way (rest area). Waypoints are always planned stops.
     */
    private fun pausedCopy(t: TripEntity, since: Long, lat: Double?, lng: Double?, remain: Int?): TripEntity {
        val wp = if (lat != null && lng != null) ctx.nearWaypoint(lat, lng) else null
        val nd = noDest(t)
        val kind = when { wp != null -> PauseKind.WAYPOINT; nd -> PauseKind.PROVISIONAL; else -> PauseKind.STOP }
        val endLike = !nd && wp == null && lat != null && lng != null && looksLikeEnd(lat, lng)
        val prov = nd || endLike
        if (endLike) Timber.tag("CB/trip").i("stop looks like the end")
        return t.copy(
            pausedAt = since, pauseLat = lat, pauseLng = lng, pauseRemainSec = remain, pauseKind = kind.name,
            provisional = prov, provisionalEndAt = if (prov) since else null,
        )
    }

    private fun destDistM(lat: Double, lng: Double): Double? = ctx.dest?.let { ctx.distanceToDestM(lat, lng, it) }

    private fun nearDest(lat: Double, lng: Double): Boolean = destDistM(lat, lng)?.let { it <= cfg.arriveNearKm * 1000 } ?: false

    private fun nearDestOrFrequent(lat: Double, lng: Double): Boolean = nearDest(lat, lng) || frequentPlace(lat, lng)

    private fun looksLikeEnd(lat: Double, lng: Double): Boolean {
        if (nearDestOrFrequent(lat, lng)) return true
        if (ctx.navEnded) return true                         // guidance ended / cancelled and we are not at the destination
        val now = destDistM(lat, lng) ?: return false
        val closest = ctx.recentLocations.mapNotNull { destDistM(it.lat, it.lng) }.minOrNull() ?: return false
        return now - closest >= cfg.awayFromDestKm * 1000      // drove away from the destination before stopping
    }

    /** How long this stop may last before the trip is over (IMPLEMENTATION_NOTES '여정 끝 판정'). */
    fun pauseLimitMs(t: TripEntity): Long {
        val lat = t.pauseLat ?: t.lastLat
        val lng = t.pauseLng ?: t.lastLng
        val min = when {
            noDest(t) -> cfg.noDestPauseMin
            t.provisional && lat != null && lng != null && nearDestOrFrequent(lat, lng) -> cfg.nearDestPauseMin
            t.provisional -> cfg.noDestPauseMin
            else -> cfg.destPauseMaxMin
        }
        return min * 60_000L
    }

    /** The stop outlasted [pauseLimitMs]: the trip ended where the car stopped. */
    private fun timeoutEnd(t: TripEntity, stopStart: Long): Transition {
        val lat = t.pauseLat ?: t.lastLat
        val lng = t.pauseLng ?: t.lastLng
        val near = lat != null && lng != null && nearDest(lat, lng)
        return when {
            noDest(t) -> end(t, t.provisionalEndAt ?: stopStart, EndReason.NO_DEST_TIMEOUT)
            t.provisional && near -> end(t.copy(arrivedAt = stopStart), stopStart, EndReason.ARRIVED)
            t.provisional -> end(t, stopStart, EndReason.ARRIVED_ELSEWHERE)
            else -> end(t, stopStart, EndReason.MAX_GAP)
        }
    }

    private fun whilePaused(t: TripEntity, e: SignalEvent): List<Transition> {
        val pausedAt = t.pausedAt ?: t.stateSince
        val kind = t.pauseKind?.let(PauseKind::valueOf) ?: PauseKind.STOP
        val since = movingSince
        if (since != null && e.t - since >= cfg.drivingConfirmSec * 1000L) {
            val stay = since - pausedAt
            if (stay > pauseLimitMs(t)) return listOf(timeoutEnd(t, pausedAt)) + start(since)
            derived += PauseClosed(
                since, pausedAt, t.pauseLat, t.pauseLng, kind, t.pauseRemainSec,
                t.pauseLat?.let { la -> t.pauseLng?.let { ctx.distanceToDestM(la, it) } },
            )
            if (kind != PauseKind.STOP) {
                val name = if (kind == PauseKind.WAYPOINT) t.pauseLat?.let { la -> t.pauseLng?.let { ctx.nearWaypoint(la, it)?.name } } else null
                store.addWaypointStop(WaypointStopEntity(tripId = t.id, name = name, fromT = pausedAt, toT = since, kind = kind.name))
            }
            val resumed = t.copy(
                pausedTotalMs = t.pausedTotalMs + stay, pausedAt = null, pauseKind = null, pauseRemainSec = null,
                provisional = false, provisionalEndAt = null,
            )
            return listOf(move(resumed, DRIVING, since, "resumed after ${stay / 60_000} min"))
        }
        if (e.t - pausedAt > pauseLimitMs(t)) return listOf(timeoutEnd(t, pausedAt))
        return emptyList()
    }

    private fun whileParkedAtDest(t: TripEntity, e: SignalEvent): List<Transition> {
        if (ctx.driverBelt == false) return listOf(end(t, e.t, EndReason.ARRIVED))
        val since = movingSince
        if (since != null && e.t - since >= cfg.drivingConfirmSec * 1000L && !ctx.isParked(e.t)) {
            return listOf(move(t, DRIVING, since, "moved again before leaving"))
        }
        // no belt signal on this car → a short wait; belt kept on → idleBeltOnMin
        val parkedFor = e.t - (t.arrivedAt ?: t.stateSince)
        if (ctx.driverBelt == null && parkedFor >= cfg.arriveBeltUnknownSec * 1000L) return listOf(end(t, e.t, EndReason.ARRIVED))
        if (ctx.driverBelt == true && parkedFor >= cfg.idleBeltOnMin * 60_000L) return listOf(end(t, e.t, EndReason.ARRIVED))
        return emptyList()
    }

    // ---------------------------------------------------------------- recovery (via TripRecovery)

    internal fun recoverNow(now: Long, cause: SignalEvent): List<Transition> {
        restore()
        val t0 = trip ?: return emptyList()
        val last = t0.lastSignalAt ?: t0.stateSince
        if (t0.recoveredAt != null && t0.recoveredAt >= last) return emptyList()   // this gap was handled already
        movingSince = null
        heldSince = null
        val out = mutableListOf<Transition>()
        var t = t0
        // 1단계: tidy the state we died in
        when (TripState.valueOf(t.state)) {
            PARKED_AT_DEST -> return listOf(end(t, last, EndReason.ARRIVED_POWER_OFF))
            DRIVING, ARRIVING -> if (t.lastGear == "P" || (t.lastSpeed ?: 1f) <= 0.5f) {
                // stopped at the destination when the power went off → arrival, time known
                val d = if (!noDest(t) && t.lastLat != null && t.lastLng != null) destDistM(t.lastLat, t.lastLng) else null
                if (d != null && d <= cfg.arriveRadiusM) {
                    return listOf(end(t.copy(arrivedAt = t.arrivedAt ?: t.parkedSince ?: last), last, EndReason.ARRIVED_POWER_OFF))
                }
                out += move(pausedCopy(t, t.parkedSince ?: last, t.lastLat, t.lastLng, t.lastRemainSec), PAUSED, last, "recovery: power went off while parked")
                t = trip!!
            }
            else -> Unit
        }
        // 2단계: end now, or wait for the first fix
        val stopStart = t.pausedAt ?: last
        if (now - stopStart > pauseLimitMs(t)) { out += timeoutEnd(t, stopStart); return out }
        recoverGapFrom = last
        save(t.copy(recovering = true, recoveredAt = now))
        Timber.tag("CB/trip").i("recovery (%s): waiting for first fix, gap %d s", cause::class.simpleName, (now - last) / 1000)
        return out
    }

    /** 2단계 continued: first location after recovery (or timeout). Null = nothing changed state. */
    private fun resolveRecovery(t: TripEntity, e: SignalEvent): List<Transition>? {
        val fix = e as? Location
        val timedOut = e.t - (t.recoveredAt ?: e.t) >= cfg.recoverFixWaitSec * 1000L
        if (fix == null && !timedOut) return null
        val n = t.copy(recovering = false)
        if (fix == null) { save(n); return null }
        val dest = ctx.origDest ?: ctx.dest
        val toDest = dest?.let { ctx.distanceToDestM(fix.lat, fix.lng, it) }
        if (toDest != null && toDest <= cfg.arriveRadiusM && ctx.isParked(e.t)) {
            // arrived while the app was off: arrival time unknown → NO_DEST receipt
            return listOf(end(n.copy(arrivalKnown = false), recoverGapFrom ?: t.recoveredAt ?: e.t, EndReason.ARRIVED_POWER_OFF))
        }
        val refLat = t.pauseLat ?: t.lastLat
        val refLng = t.pauseLng ?: t.lastLng
        if (refLat != null && refLng != null) {
            val jump = Geo.distanceM(refLat, refLng, fix.lat, fix.lng)
            if (jump > cfg.recoverJumpM) {
                val from = recoverGapFrom ?: t.recoveredAt ?: t.stateSince
                val wasPaused = TripState.valueOf(t.state) == PAUSED
                store.addGap(TripGapEntity(tripId = t.id, fromT = from, toT = fix.t, reason = if (wasPaused) "LATE_START" else "CRASH", straightKm = jump / 1000.0))
                Timber.tag("CB/trip").i("recovery: missed %.1f km → trip_gap", jump / 1000.0)
                var g = n.copy(distanceKm = n.distanceKm + jump / 1000.0, lastLat = fix.lat, lastLng = fix.lng)
                if (wasPaused) {
                    // that stop's end is unknown → no REST_STOP for it
                    g = g.copy(pausedAt = null, pauseKind = null, provisional = false, provisionalEndAt = null)
                    return if (ctx.isParked(e.t)) {
                        listOf(move(pausedCopy(g, ctx.parkedSince() ?: fix.t, fix.lat, fix.lng, ctx.remainSec), PAUSED, fix.t, "recovery: elsewhere after a gap"))
                    } else {
                        listOf(move(g, DRIVING, fix.t, "recovery: driving after a gap"))
                    }
                }
                save(g)
                return null
            }
        }
        save(n)
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun trackMoving(e: SignalEvent) {
        if (ctx.isDeparting() && !ctx.isParked(e.t)) { if (movingSince == null) movingSince = e.t } else movingSince = null
    }

    /** Distance, tallies, last-known values, navigation fields. */
    private fun accumulate(t: TripEntity, e: SignalEvent, prevKmh: Float, prevT: Long?): TripEntity {
        var n = t
        val dt = if (prevT != null) (e.t - prevT).coerceIn(0, MAX_INTEGRATION_GAP_MS) else 0L
        if (dt > 0 && n.state in DRIVING_STATES) {
            if (prevKmh > 0f) n = n.copy(distanceKm = n.distanceKm + prevKmh * dt / 3_600_000.0)
            if (ctx.windows.values.any { it >= ctx.config.rainWindowOpenPct }) n = n.copy(windowOpenMs = n.windowOpenMs + dt)
            if (ctx.wiper != WiperMode.OFF) n = n.copy(wiperOnMs = n.wiperOnMs + dt)
        }
        when (e) {
            is Location -> n = n.copy(lastLat = e.lat, lastLng = e.lng, startLat = n.startLat ?: e.lat, startLng = n.startLng ?: e.lng)
            is LowBeam -> if (e.on) lowBeamOnAt = e.t else lowBeamOnAt?.let { on ->
                val d = e.t - on
                // daytime only: at night the headlights are simply on (sun at the last fix, else 07–17 h)
                val day = ctx.lastLocation?.let { ai.carbuddy.core.geo.Sun.isDay(on, it.lat, it.lng) }
                    ?: (java.time.Instant.ofEpochMilli(on).atZone(java.time.ZoneId.of("Asia/Seoul")).hour in 7..16)
                if (day && d >= ctx.config.tunnelMinSec * 1000L && d <= ctx.config.tunnelMaxMin * 60_000L) n = n.copy(tunnelCount = n.tunnelCount + 1)
                lowBeamOnAt = null
            }
            is RouteStarted, is DestinationChanged -> {
                val firstDest = noDest(n)
                n = n.copy(
                    destName = ctx.dest?.name, destLat = ctx.dest?.lat, destLng = ctx.dest?.lng,
                    origDestName = n.origDestName ?: ctx.origDest?.name ?: ctx.dest?.name,
                    origDestLat = n.origDestLat ?: ctx.origDest?.lat ?: ctx.dest?.lat,
                    origDestLng = n.origDestLng ?: ctx.origDest?.lng ?: ctx.dest?.lng,
                    hasNavi = true,
                )
                if (firstDest && ctx.dest != null) {
                    // destination set after departure (SPEC 5.5): the time settlement starts here
                    n = n.copy(destSetAt = e.t, baseEtaAt = (e as? RouteStarted)?.etaEpochMs)
                    ctx.destSetAt = e.t
                    if (ctx.origDest == null) ctx.origDest = ctx.dest
                    if (n.provisional) n = n.copy(provisional = false, provisionalEndAt = null, pauseKind = PauseKind.STOP.name)
                }
            }
            is RemainTime -> {
                n = n.copy(lastRemainSec = e.remainSec, hasNavi = true)
                if (n.baseEtaAt == null && !noDest(n)) n = n.copy(baseEtaAt = e.t + e.remainSec * 1000L)
            }
            is WaypointChanged -> n = n.copy(waypointsJson = json.encodeToString(waypointSer, ctx.waypoints.toList()), hasNavi = true)
            else -> Unit
        }
        if (ctx.navEnded && !n.navEnded) n = n.copy(navEnded = true)
        val pax = ctx.passengerCount()
        if (pax > n.maxPassengers) n = n.copy(maxPassengers = pax)
        if (e !is Tick) {
            n = n.copy(
                lastSignalAt = e.t, lastGear = ctx.gear.name, lastSpeed = ctx.speedKmh,
                parkedSince = if (ctx.isParked(e.t)) ctx.parkedSince() else null,
            )
        }
        return n
    }

    private fun atDestination(): Boolean {
        val d = ctx.dest ?: return false
        // the navigation's "arrived" only counts near the destination (it may say so and we park elsewhere)
        val loc = ctx.lastLocation ?: return ctx.destArrivedAt != null
        val dist = ctx.distanceToDestM(loc.lat, loc.lng, d) ?: return ctx.destArrivedAt != null
        if (ctx.destArrivedAt != null && dist <= cfg.arriveNearKm * 1000) return true
        if (dist <= cfg.arriveRadiusM) return true
        return ctx.navEnded && dist <= cfg.arriveNearKm * 1000
    }

    private fun start(at: Long): Transition {
        // a route set before departure only counts if recent (SPEC 5.5 대기 경로)
        if (ctx.dest != null && at - ctx.routeT > cfg.pendingRouteMin * 60_000L) { ctx.dest = null; ctx.origDest = null; ctx.routeEta = null }
        val hasDest = ctx.dest != null
        val baseEta = if (!hasDest) null else {
            val remain = ctx.remainSec
            // ETA when the remaining time was measured (stationary before departure → it slides, SPEC 5.5)
            if (remain != null && ctx.remainT >= ctx.routeT) ctx.remainT + remain * 1000L else ctx.routeEta?.plus(at - ctx.routeT)
        }
        val loc = ctx.lastLocation
        val t = TripEntity(
            id = newId(), state = DRIVING.name, stateSince = at, startedAt = at,
            origDestName = ctx.dest?.name, origDestLat = ctx.dest?.lat, origDestLng = ctx.dest?.lng,
            destName = ctx.dest?.name, destLat = ctx.dest?.lat, destLng = ctx.dest?.lng,
            baseEtaAt = baseEta, hasNavi = ctx.hasNavi, lastRemainSec = ctx.remainSec,
            startLat = loc?.lat, startLng = loc?.lng, lastLat = loc?.lat, lastLng = loc?.lng, lastSignalAt = at,
            maxPassengers = ctx.passengerCount(),
            waypointsJson = json.encodeToString(waypointSer, ctx.waypoints.toList()),
        )
        if (hasDest) ctx.origDest = ctx.dest
        save(t)
        syncContext(t)
        Timber.tag("CB/trip").i("trip %s started (dest=%s)", t.id, t.destName)
        return Transition(t.id, IDLE, DRIVING, at, "moving")
    }

    private fun move(t: TripEntity, to: TripState, at: Long, reason: String): Transition {
        val from = TripState.valueOf(t.state)
        val n = t.copy(state = to.name, stateSince = at)
        save(n)
        ctx.state = to
        Timber.tag("CB/trip").i("%s → %s (%s)", from, to, reason)
        return Transition(n.id, from, to, at, reason)
    }

    private fun end(t: TripEntity, endedAt: Long, reason: EndReason): Transition {
        val from = TripState.valueOf(t.state)
        val durationMin = ((t.arrivedAt ?: endedAt) - t.startedAt) / 60_000.0
        // the user's own [여기서 끝내기] always keeps the trip, however short
        val discarded = reason != EndReason.USER && (t.distanceKm < cfg.minTripKm || durationMin < cfg.minTripMin)
        val n = t.copy(state = ENDED.name, stateSince = endedAt, endedAt = endedAt, endReason = reason.name, discarded = discarded, recovering = false)
        save(n)
        trip = null
        ctx.state = IDLE
        Timber.tag("CB/trip").i("%s → ENDED (%s) discarded=%s", from, reason, discarded)
        return Transition(n.id, from, ENDED, endedAt, reason.name, reason)
    }

    /**
     * V10 [여기서 끝내기]: end the open trip now with what we have. Ends where the car stopped
     * (pause start) if it is parked, else at [t]. Null when no trip is open.
     */
    fun endByUser(t: Long): Transition? {
        val open = trip ?: return null
        val at = open.pausedAt?.takeIf { ctx.isParked(t) } ?: t
        return end(open, maxOf(at, open.startedAt), EndReason.USER)
    }

    /** "기록 멈추기": forget the active trip entirely (SPEC 5.8, UX V10). */
    fun discardActive(): String? {
        val id = trip?.id ?: return null
        store.deleteTrip(id)
        trip = null
        ctx.state = IDLE
        return id
    }

    private fun save(t: TripEntity) { trip = t; store.saveTrip(t); lastFlushAt = t.lastSignalAt ?: lastFlushAt }

    private fun syncContext(t: TripEntity) {
        ctx.tripId = t.id
        ctx.tripStartedAt = t.startedAt
        ctx.state = TripState.valueOf(t.state)
        ctx.destSetAt = t.destSetAt
        if (ctx.dest == null && (t.destName != null || t.destLat != null)) ctx.dest = Dest(t.destName, t.destLat, t.destLng)
        if (ctx.origDest == null && (t.origDestName != null || t.origDestLat != null)) ctx.origDest = Dest(t.origDestName, t.origDestLat, t.origDestLng)
        if (ctx.remainSec == null) t.lastRemainSec?.let { ctx.remainSec = it; ctx.remainT = t.lastSignalAt ?: 0 }
        if (ctx.waypoints.isEmpty()) runCatching { ctx.waypoints += json.decodeFromString(waypointSer, t.waypointsJson) }
        if (t.navEnded) ctx.navEnded = true
        if (t.hasNavi) ctx.hasNavi = true
        ctx.restoreParkedSince(t.parkedSince)
        if (ctx.lastLocation == null && t.lastLat != null && t.lastLng != null) {
            ctx.lastLocation = Location(t.lastSignalAt ?: t.stateSince, t.lastLat, t.lastLng, null, null)
        }
    }

    companion object {
        private val DRIVING_STATES = setOf(DRIVING.name, ARRIVING.name)
        private const val MAX_INTEGRATION_GAP_MS = 5 * 60_000L
    }
}

/**
 * The single recovery entry (SPEC 5.2–5.3, CLAUDE.md 규칙 4): PowerBoot(BOOT / APP_START) and
 * SignalResumed all come here. Idempotent via trip.recoveredAt (BOOT + APP_START together → once).
 */
class TripRecovery(private val engine: TripEngine) {
    @Synchronized
    fun recover(now: Long, cause: SignalEvent): List<Transition> = engine.recoverNow(now, cause)
}
