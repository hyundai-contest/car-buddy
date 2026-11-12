package ai.carbuddy.episode.detectors

import ai.carbuddy.core.geo.Sun
import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.util.Geo
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.signal.CameraAlert
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.LowBeam
import ai.carbuddy.signal.SeatOccupancy
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.Speed
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// PT detectors (기획안 v13 3장 표, SPEC 6.2 마지막 문단). Numbers live in DetectorConfig.

private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
private const val MAX_STEP_MS = 60_000L                     // longer silences are not counted as time

private fun TripContext.moving(t: Long) = isMoving() && !isParked(t)

/** FULL_HOUSE: fullHouseSeats occupied seats for fullHouseMinSec on a trip. Once per trip. */
class FullHouseDetector : StatefulDetector<FullHouseDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(val since: Long? = null, val maxSeats: Int = 0, val emitted: Boolean = false)

    override val type = EpisodeType.FULL_HOUSE
    override val requires = setOf(Capability.SEAT_OCCUPANCY)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val n = ctx.seats.count { it.value }
        if (n < ctx.config.fullHouseSeats) { s = s.copy(since = null); return emptyList() }
        val since = s.since ?: e.t
        s = s.copy(since = since, maxSeats = maxOf(s.maxSeats, n))
        if (s.emitted || e.t - since < ctx.config.fullHouseMinSec * 1000L) return emptyList()
        s = s.copy(emitted = true)
        return listOf(EpisodeCandidate(type, since, e.t, mapOf("count" to s.maxSeats), key = "full"))
    }
}

/** WIND_RUN: every window ≥ windRunPct while moving, windRunMin or longer (one episode per run). */
class WindRunDetector : StatefulDetector<WindRunDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(val since: Long? = null, val lastT: Long = 0)

    override val type = EpisodeType.WIND_RUN
    override val requires = setOf(Capability.WINDOW)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val open = ctx.windows.size >= SeatPos.entries.size && ctx.windows.values.all { it >= cfg.windRunPct }
        val minMs = cfg.windRunMin * 60_000L
        if (open && ctx.moving(e.t)) {
            val since = s.since ?: e.t
            s = State(since, e.t)
            return if (e.t - since >= minMs) listOf(candidate(since, e.t)) else emptyList()
        }
        val since = s.since ?: return emptyList()
        val end = if (open) s.lastT else e.t                    // stopped with windows open: the run ended at the last moving moment
        s = State()
        return if (end - since >= minMs) listOf(candidate(since, end)) else emptyList()
    }

    private fun candidate(since: Long, end: Long) =
        EpisodeCandidate(type, since, end, mapOf("minutes" to ((end - since) / 60_000.0).roundToInt()), key = since.toString())
}

/**
 * TUNNEL_PARADE: daytime low beam on for tunnelMinSec..tunnelMaxMin then off = one tunnel;
 * tunnelEpisodeCount or more make the episode. Day = sun up at the last fix (no fix: 07–17 h).
 */
class TunnelParadeDetector : StatefulDetector<TunnelParadeDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(val onAt: Long? = null, val onInDay: Boolean = false, val count: Int = 0, val firstAt: Long? = null)

    override val type = EpisodeType.TUNNEL_PARADE
    override val requires = setOf(Capability.LOW_BEAM)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        if (e !is LowBeam) return emptyList()
        val cfg = ctx.config
        if (e.on) { if (s.onAt == null) s = s.copy(onAt = e.t, onInDay = isDay(e.t, ctx)); return emptyList() }
        val on = s.onAt ?: return emptyList()
        val d = e.t - on
        val tunnel = s.onInDay && d >= cfg.tunnelMinSec * 1000L && d <= cfg.tunnelMaxMin * 60_000L
        s = s.copy(onAt = null, count = s.count + if (tunnel) 1 else 0, firstAt = s.firstAt ?: if (tunnel) on else null)
        if (!tunnel || s.count < cfg.tunnelEpisodeCount) return emptyList()
        return listOf(EpisodeCandidate(type, s.firstAt!!, e.t, mapOf("count" to s.count), key = "tunnels"))
    }

    private fun isDay(t: Long, ctx: TripContext): Boolean {
        val fix = ctx.lastLocation ?: return Instant.ofEpochMilli(t).atZone(ZONE).hour in 7..16
        return Sun.isDay(t, fix.lat, fix.lng)
    }
}

/**
 * SUNSET_DRIVE: around sunset (solar elevation sunsetElevMinDeg..sunsetElevMaxDeg, sun in the west)
 * heading within sunsetBearingDeg of the sun for sunsetMin in total. Heading from consecutive fixes.
 */
class SunsetDriveDetector : StatefulDetector<SunsetDriveDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(
        val lat: Double? = null, val lng: Double? = null, val t: Long = 0,
        val totalMs: Long = 0, val firstAt: Long? = null, val lastAt: Long = 0,
    )

    override val type = EpisodeType.SUNSET_DRIVE
    override val requires = setOf(Capability.LOCATION)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        if (e !is Location) return emptyList()
        val cfg = ctx.config
        val pLat = s.lat; val pLng = s.lng
        val gap = e.t - s.t
        if (pLat == null || pLng == null || Geo.distanceM(pLat, pLng, e.lat, e.lng) < MIN_MOVE_M) {
            if (pLat == null) s = s.copy(lat = e.lat, lng = e.lng, t = e.t)
            return emptyList()
        }
        s = s.copy(lat = e.lat, lng = e.lng, t = e.t)
        if (gap <= 0 || gap > cfg.sunsetSampleGapSec * 1000L || !ctx.moving(e.t)) return emptyList()
        val sun = Sun.position(e.t, e.lat, e.lng)
        val heading = bearing(pLat, pLng, e.lat, e.lng)
        val facing = sun.elevationDeg in cfg.sunsetElevMinDeg..cfg.sunsetElevMaxDeg && sun.azimuthDeg in 180.0..360.0 &&
            Sun.angleBetween(heading, sun.azimuthDeg) <= cfg.sunsetBearingDeg
        if (!facing) return emptyList()
        s = s.copy(totalMs = s.totalMs + gap, firstAt = s.firstAt ?: (e.t - gap), lastAt = e.t)
        if (s.totalMs < cfg.sunsetMin * 60_000L) return emptyList()
        return listOf(EpisodeCandidate(type, s.firstAt!!, s.lastAt, mapOf("minutes" to (s.totalMs / 60_000.0).roundToInt()), key = "sunset"))
    }

    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private companion object { const val MIN_MOVE_M = 15.0 }
}

/**
 * DRIVER_SWAP: in P the driver seat goes empty → occupied (empty ≥ swapMinEmptySec) and someone
 * else left their seat meanwhile (fewer other seats occupied than before) — the driver merely
 * stepping out at a rest stop is not a swap. km = distance driven after the swap.
 * TODO(PT): ask the phone "누가 운전해요?" for the name (기획안 3장).
 */
class DriverSwapDetector : StatefulDetector<DriverSwapDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(
        val lastSeats: Map<String, Boolean> = emptyMap(),
        val emptyAt: Long? = null, val othersBefore: Int = 0,
        val swapAt: Long? = null, val km: Double = 0.0, val lastT: Long? = null, val lastKmh: Float = 0f,
    )

    override val type = EpisodeType.DRIVER_SWAP
    override val requires = setOf(Capability.SEAT_OCCUPANCY)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        // distance after the swap (speed integrated between events)
        s.lastT?.let { lt -> if (s.swapAt != null) { val dt = (e.t - lt).coerceIn(0, MAX_STEP_MS); s = s.copy(km = s.km + s.lastKmh * dt / 3_600_000.0) } }
        s = s.copy(lastT = e.t, lastKmh = if (e is Speed) e.kmh else ctx.speedKmh)
        if (e !is SeatOccupancy) return progress(e.t)
        val now = ctx.seats.mapKeys { it.key.name }
        val wasDriver = s.lastSeats[SeatPos.DRIVER.name] == true
        val isDriver = now[SeatPos.DRIVER.name] == true
        val others = { m: Map<String, Boolean> -> m.count { it.key != SeatPos.DRIVER.name && it.value } }
        val parked = ctx.isParked(e.t)
        when {
            wasDriver && !isDriver && parked -> s = s.copy(emptyAt = e.t, othersBefore = others(s.lastSeats))
            !wasDriver && isDriver && parked && s.emptyAt != null -> {
                val longEnough = e.t - s.emptyAt!! >= ctx.config.swapMinEmptySec * 1000L
                if (longEnough && others(now) < s.othersBefore) s = s.copy(swapAt = e.t, km = 0.0)
                s = s.copy(emptyAt = null)
            }
            !parked -> s = s.copy(emptyAt = null)
        }
        s = s.copy(lastSeats = now)
        return progress(e.t)
    }

    override fun onTripEnd(ctx: TripContext) = progress(s.lastT ?: ctx.now)

    private fun progress(t: Long): List<EpisodeCandidate> {
        val at = s.swapAt ?: return emptyList()
        return listOf(EpisodeCandidate(type, at, maxOf(at, t), mapOf("km" to s.km.roundToInt()), key = at.toString()))
    }
}

/**
 * CAMERA_CLEAN: cameraMinCount or more camera alerts, and the speed never above the alert's limit
 * within cameraCheckSec after any of them; otherwise nothing at all (SPEC 6.2). Built at trip end.
 */
class CameraCleanDetector : StatefulDetector<CameraCleanDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(val count: Int = 0, val limit: Int? = null, val checkUntil: Long = 0, val over: Boolean = false, val firstAt: Long? = null, val lastAt: Long = 0)

    override val type = EpisodeType.CAMERA_CLEAN
    override val requires = setOf(Capability.NAVI, Capability.SPEED)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        when (e) {
            is CameraAlert -> {
                val limit = e.limitKmh ?: return emptyList()             // no limit: cannot be checked, not counted
                s = s.copy(count = s.count + 1, limit = limit, checkUntil = e.t + ctx.config.cameraCheckSec * 1000L,
                    firstAt = s.firstAt ?: e.t, lastAt = e.t, over = s.over || ctx.speedKmh > limit)
            }
            is Speed -> { val l = s.limit; if (l != null && e.t <= s.checkUntil && e.kmh > l) s = s.copy(over = true) }
            else -> Unit
        }
        return emptyList()
    }

    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> =
        if (s.over || s.count < ctx.config.cameraMinCount) emptyList()
        else listOf(EpisodeCandidate(type, s.firstAt!!, s.lastAt, mapOf("count" to s.count), key = "cameras"))
}

/**
 * REVISIT: the trip ends in a city (시·군) an earlier card already went to. [lookup] (pipeline)
 * resolves the city at the end point and returns the most recent earlier visit, or null.
 */
class RevisitDetector(private val lookup: (lat: Double, lng: Double) -> Visit?) : StatefulDetector<RevisitDetector.State>(State.serializer(), ::State) {
    @Serializable class State
    data class Visit(val city: String, val at: Long)

    override val type = EpisodeType.REVISIT
    override val requires = emptySet<Capability>()

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> = emptyList()

    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> {
        val fix = ctx.lastLocation ?: return emptyList()
        val v = lookup(fix.lat, fix.lng) ?: return emptyList()
        val t = ctx.now
        return listOf(EpisodeCandidate(type, t, t, mapOf("city" to v.city, "whenWord" to whenWord(v.at, t)), key = "revisit"))
    }

    /** "지난주" (≤ 7 days) · "이번 달" · "지난달" · "8월" · "작년 11월" · "2024년 3월". */
    fun whenWord(before: Long, now: Long): String {
        val a = Instant.ofEpochMilli(before).atZone(ZONE).toLocalDate()
        val b = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        val monthsApart = (b.year - a.year) * 12 + (b.monthValue - a.monthValue)
        return when {
            ChronoUnit.DAYS.between(a, b) <= 7 -> "지난주"
            monthsApart == 0 -> "이번 달"
            monthsApart == 1 -> "지난달"
            a.year == b.year -> "${a.monthValue}월"
            a.year == b.year - 1 -> "작년 ${a.monthValue}월"
            else -> "${a.year}년 ${a.monthValue}월"
        }
    }
}

/** NIGHT_DRIVE: nightMinMin of driving between nightStartHour and nightEndHour (local time). */
class NightDriveDetector : StatefulDetector<NightDriveDetector.State>(State.serializer(), ::State) {
    @Serializable data class State(val prevT: Long? = null, val prevMoving: Boolean = false, val totalMs: Long = 0, val firstAt: Long? = null, val lastAt: Long = 0)

    override val type = EpisodeType.NIGHT_DRIVE
    override val requires = emptySet<Capability>()

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val prev = s.prevT
        if (prev != null && s.prevMoving && inNight(prev, cfg.nightStartHour, cfg.nightEndHour)) {
            val dt = (e.t - prev).coerceIn(0, MAX_STEP_MS)
            s = s.copy(totalMs = s.totalMs + dt, firstAt = s.firstAt ?: prev, lastAt = e.t)
        }
        s = s.copy(prevT = e.t, prevMoving = ctx.moving(e.t))
        if (s.totalMs < cfg.nightMinMin * 60_000L) return emptyList()
        val hour = Instant.ofEpochMilli(s.firstAt!!).atZone(ZONE).hour
        return listOf(EpisodeCandidate(type, s.firstAt!!, s.lastAt, mapOf("minutes" to (s.totalMs / 60_000.0).roundToInt(), "hour" to hour), key = "night"))
    }

    private fun inNight(t: Long, from: Int, to: Int) = Instant.ofEpochMilli(t).atZone(ZONE).hour.let { it in from until to }
}
