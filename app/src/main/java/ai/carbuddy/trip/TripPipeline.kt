package ai.carbuddy.trip

import ai.carbuddy.card.Back
import ai.carbuddy.card.BuddyInfo
import ai.carbuddy.card.Card
import ai.carbuddy.card.DriverMoment
import ai.carbuddy.card.Overrides
import ai.carbuddy.ceremony.NotificationBudget
import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.TripState
import ai.carbuddy.core.util.Geo
import ai.carbuddy.data.CardEntity
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.SettingsEntity
import ai.carbuddy.data.SignalLogEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.episode.EpisodeDetector
import ai.carbuddy.episode.EpisodeEngine
import ai.carbuddy.episode.PlaceNameProvider
import ai.carbuddy.place.PlaceLearner
import ai.carbuddy.place.RoutineClassifier
import ai.carbuddy.route.RouteTracker
import ai.carbuddy.signal.BootReason
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.PauseClosed
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.PowerCycle
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.SignalJson
import ai.carbuddy.signal.SignalResumed
import ai.carbuddy.signal.Tick
import ai.carbuddy.story.BuddyEngine
import ai.carbuddy.story.CopyBook
import ai.carbuddy.story.Scorer
import ai.carbuddy.story.StoryComposer
import ai.carbuddy.story.StoryExtras
import ai.carbuddy.voice.ArrivalRecap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What happened while processing one event (UI, notifications, tests). */
sealed interface PipelineEvent {
    data class StateChanged(val transition: Transition) : PipelineEvent
    data class EpisodeCaptured(val episode: EpisodeEntity, val isNew: Boolean) : PipelineEvent
    data class RecapSpoken(val text: String) : PipelineEvent
    /** Parked at the destination: draft card ready (N1 / screening decision, SPEC 10.2). */
    data class Arrived(val card: Card, val trip: TripEntity) : PipelineEvent
    /** Trip ended. card is null for discarded trips. */
    data class Ended(val tripId: String, val card: Card?, val exit: Back?, val reason: EndReason?) : PipelineEvent
    /** A PowerBoot/SignalResumed was processed (N4 check, record-off reset). */
    data class Booted(val reason: BootReason?) : PipelineEvent
    /** 중간 상영 (NEXT_DESIGN A): back in the seat at a stop on the way; [card] is the story so far. */
    data class Halftime(val tripId: String, val card: Card, val t: Long, val remainSec: Int?) : PipelineEvent
}

/** Card upload port (SPEC 5.7). The companion server arrives in PT; until then nothing leaves the car. */
fun interface CardSyncPort { fun push(card: CardEntity) }

/**
 * Single consumer of the signal stream: context → trip state machine → detectors → story.
 * Synchronous and deterministic so replay logs double as integration tests (CLAUDE.md).
 */
class TripPipeline(
    val store: TripStore,
    val config: DetectorConfig,
    private val copy: CopyBook,
    private val cities: CityResolver,
    detectors: List<EpisodeDetector>? = null,
    capabilities: CapabilityRegistry = CapabilityRegistry(),
    newId: () -> String = { UUID.randomUUID().toString() },
    private val logSignals: Boolean = true,
    private val sync: CardSyncPort = CardSyncPort { },
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    /** Settings source; the runtime injects a cached one. */
    var settings: () -> SettingsEntity = { store.settings() }

    val ctx = TripContext(config)
    val places = PlaceLearner(store, config)
    private val routines = RoutineClassifier(store, config)
    private val tracker = RouteTracker(store, config)
    val budget = NotificationBudget(store, config, zone)
    val trips = TripEngine(
        store, ctx,
        recording = { settings().let { !it.recordOff && !it.recordPaused } },
        frequentPlace = { la, ln -> places.isFrequent(la, ln) },
        newId = newId,
    )
    val episodes = EpisodeEngine(
        detectors ?: (EpisodeEngine.mustDetectors(cities, PlaceNameProvider { la, ln -> places.nameAt(la, ln) }) + EpisodeEngine.ptDetectors(::lastVisit)),
        store, capabilities,
    )
    val composer = StoryComposer(copy, cities, config, zone)
    val buddy = BuddyEngine(store)
    var recap: ArrivalRecap? = null

    /** 체험 모드: trips are shown but never grow the buddy or learn places (and are deleted afterwards). */
    @Volatile var demoMode: Boolean = false

    /** REVISIT: most recent earlier card whose destination city (시·군) is the one at [lat],[lng]. */
    private fun lastVisit(lat: Double, lng: Double): ai.carbuddy.episode.detectors.RevisitDetector.Visit? {
        val city = cities.resolve(lat, lng) ?: return null
        val current = ctx.tripId ?: trips.trip?.id      // the trip being closed (trips.trip may already be cleared)
        val at = store.cards().asSequence()
            .filter { it.tripId != current }
            .filter { c -> runCatching { Card.fromJson(c.json).route.toCity == city.full }.getOrDefault(false) }
            .mapNotNull { store.trip(it.tripId)?.let { t -> t.endedAt ?: t.startedAt } }
            .maxOrNull() ?: return null
        return ai.carbuddy.episode.detectors.RevisitDetector.Visit(city.short, at)
    }
    private var lastEventT = 0L
    private val longSer = ListSerializer(Long.serializer())
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** Process start: reload the active trip and its detector state. */
    fun restore() {
        trips.restore()
        trips.trip?.let { episodes.restore(it.id) }
    }

    fun process(e: SignalEvent): List<PipelineEvent> {
        if (e is PowerCycle) return emptyList()            // replay marker, handled by harnesses
        val out = mutableListOf<PipelineEvent>()
        // Suspend-to-RAM: signals silent for a while, then back (SPEC 5.2 ③)
        if (e !is PowerBoot && e !is SignalResumed && lastEventT > 0 && trips.trip != null &&
            e.t - lastEventT > config.trip.signalGapMin * 60_000L
        ) out += process(SignalResumed(e.t, lastEventT))
        lastEventT = maxOf(lastEventT, e.t)
        if (logSignals && e !is Tick && e !is SignalResumed) store.appendSignal(SignalLogEntity(t = e.t, type = SignalJson.typeName(e), payloadJson = SignalJson.encode(e, e.t)))

        val ev = remapRoute(e)
        val beltBefore = ctx.driverBelt
        val driverSeatBefore = ctx.seats[SeatPos.DRIVER]
        ctx.update(ev)
        if (ev is PowerBoot) {
            restore()
            if (ev.reason == BootReason.BOOT) settings().takeIf { it.recordOff }?.let { store.saveSettings(it.copy(recordOff = false)) }
        }

        val transitions = trips.onEvent(ev)
        for (tr in transitions) {
            out += PipelineEvent.StateChanged(tr)
            when {
                tr.from == TripState.IDLE -> onTripStarted(tr)
                tr.to == TripState.ENDED -> { out += finish(tr); continue }
            }
            when (tr.to) {
                TripState.ARRIVING -> if (trips.trip?.recapDone != true) recap?.schedule(tr.t, episodes.current, history(tr.tripId), ctx.remainSec)
                TripState.PARKED_AT_DEST -> composeCard(tr.tripId, "DRAFT")?.let { c -> out += PipelineEvent.Arrived(c, store.trip(tr.tripId)!!) }
                TripState.PAUSED -> {
                    val t = trips.trip
                    if (t?.provisional == true) composeCard(tr.tripId, "PROVISIONAL", finalizeAt = (t.provisionalEndAt ?: tr.t) + trips.pauseLimitMs(t))
                    else composeCard(tr.tripId, "DRAFT")
                }
                TripState.DRIVING -> if (tr.from == TripState.PAUSED) store.cardForTrip(tr.tripId)?.let {
                    // resumed: cancel any provisional finalisation (SPEC 5.7 resume)
                    val n = it.copy(status = "DRAFT", finalizeAt = null, version = it.version + 1); store.saveCard(n); sync.push(n)
                }
                else -> Unit
            }
        }
        if (transitions.any { it.to == TripState.ENDED } && trips.trip == null) ctx.resetTrip()
        if (ev is PowerBoot || ev is SignalResumed) out += PipelineEvent.Booted((ev as? PowerBoot)?.reason)

        val trip = trips.trip
        if (trip != null) {
            episodes.onEvent(ev, ctx).forEach { out += captured(it) }
            for (d in trips.derived.toList()) {
                episodes.onEvent(d, ctx).forEach { out += captured(it) }
                if (!demoMode && d is PauseClosed && d.lat != null && d.lng != null) places.onStay(d.lat, d.lng, d.pausedAt, d.t)
            }
            if (ev is Location && (trip.state == TripState.DRIVING.name || trip.state == TripState.ARRIVING.name)) tracker.onLocation(trip.id, ev, true)
            episodes.persistStates()
            recap?.onEvent(ev)?.let { text ->
                trips.patch { it.copy(recapDone = true) }
                out += PipelineEvent.RecapSpoken(text)
            }
            halftime(ev, beltBefore, driverSeatBefore)?.let { out += it }
        }
        return out
    }

    /**
     * 중간 상영 (NEXT_DESIGN A). Back in the seat = the driver belt goes on while paused (it was off:
     * that is how the pause began, so an unknown value after a reboot counts as off), or the driver
     * seat explicitly goes empty → occupied. Only a stop on the way (not a provisional end), long
     * enough, with enough story; once per trip, stored so a reboot at the rest stop cannot repeat it.
     */
    private fun halftime(ev: SignalEvent, beltBefore: Boolean?, driverSeatBefore: Boolean?): PipelineEvent.Halftime? {
        val t = trips.trip ?: return null
        if (t.state != TripState.PAUSED.name || t.provisional || t.halftimeShownAt != null) return null
        val backIn = (ev is ai.carbuddy.signal.DriverBelt && ev.buckled && beltBefore != true) ||
            (ev is ai.carbuddy.signal.SeatOccupancy && ev.seats[SeatPos.DRIVER] == true && driverSeatBefore == false)
        if (!backIn) return null
        val pausedAt = t.pausedAt ?: return null
        if (ev.t - pausedAt < config.halftimeMinPauseMin * 60_000L) return null
        if (episodes.current.filterNot { it.hidden }.map { it.type }.distinct().size < config.halftimeMinEpisodes) return null
        trips.patch { it.copy(halftimeShownAt = ev.t) }
        val card = composeCard(t.id) ?: return null
        Timber.tag("CB/trip").i("halftime offered for %s after %d min", t.id, (ev.t - pausedAt) / 60_000)
        return PipelineEvent.Halftime(t.id, card, ev.t, t.pauseRemainSec ?: ctx.remainSec)
    }

    /** Same trip, route re-sent after a reboot (SPEC 5.3 3단계): same place → nothing; elsewhere → DestinationChanged. */
    private fun remapRoute(e: SignalEvent): SignalEvent {
        if (e !is RouteStarted || trips.trip == null) return e
        val cur = ctx.dest ?: return e
        val sameName = e.destName != null && e.destName == cur.name
        val near = e.destLat != null && e.destLng != null && cur.lat != null && cur.lng != null &&
            Geo.distanceM(e.destLat, e.destLng, cur.lat, cur.lng) <= config.trip.arriveRadiusM
        return if (sameName || near) e else DestinationChanged(e.t, e.destName, e.destLat, e.destLng)
    }

    private val seen = mutableSetOf<String>()
    private fun captured(e: EpisodeEntity): PipelineEvent = PipelineEvent.EpisodeCaptured(e, seen.add(e.id))

    private fun onTripStarted(tr: Transition) {
        episodes.startTrip(tr.tripId)
        recap?.reset()
        // a long stay where the previous trip ended counts as a place visit (SPEC 6.4)
        val prev = store.trips().filter { it.state == TripState.ENDED.name && it.id != tr.tripId && !it.discarded }.maxByOrNull { it.endedAt ?: 0 }
        val start = ctx.lastLocation
        if (prev != null && start != null && prev.lastLat != null && prev.lastLng != null && prev.endReason != EndReason.NO_DEST_TIMEOUT.name &&
            Geo.distanceM(prev.lastLat, prev.lastLng, start.lat, start.lng) <= config.placeRadiusM
        ) { if (!demoMode) places.onStay(prev.lastLat, prev.lastLng, prev.endedAt ?: tr.t, tr.t) }
    }

    // ------------------------------------------------------------------ cards

    /** Rarity history (SPEC 7장): type counts over past cards, excluding this trip. */
    fun history(tripId: String): Map<EpisodeType, Int> {
        val counts = buddy.typeCounts().toMutableMap()
        if (buddy.isCounted(tripId)) store.episodes(tripId).map { EpisodeType.valueOf(it.type) }.toSet()
            .forEach { counts[it] = (counts[it] ?: 1) - 1 }
        return counts
    }

    fun card(tripId: String): Card? = store.cardForTrip(tripId)?.let { runCatching { Card.fromJson(it.json) }.getOrNull() }

    /** (Re)builds and stores the card for [tripId]. Keeps choices made earlier (votes, title, hides). */
    fun composeCard(tripId: String, status: String? = null, back: Back? = null, finalizeAt: Long? = null, buddyOverride: BuddyInfo? = null, tripNoOverride: Int? = null): Card? {
        val trip = store.trip(tripId) ?: return null
        if (trip.discarded) return null
        val prevEntity = store.cardForTrip(tripId)
        val prev = prevEntity?.let { runCatching { Card.fromJson(it.json) }.getOrNull() }
        val b = buddy.current()
        val extras = StoryExtras(store.waypointStops(tripId), store.gaps(tripId), store.trackPoints(tripId), settings().routeTrimOn)
        var card = composer.compose(
            trip = trip, episodes = store.episodes(tripId), history = history(tripId), previous = prev,
            // a FINAL card keeps the buddy of that trip (later edits must not show today's buddy)
            buddy = buddyOverride ?: prev?.buddy?.takeIf { prevEntity.status == "FINAL" || prevEntity.status == "SYNCED" } ?: BuddyInfo(b.stage, b.equipped),
            back = back ?: prev?.back ?: Back(),
            tripNo = tripNoOverride ?: buddy.tripNumber(tripId), extras = extras,
        )
        val moments = runCatching { Json.decodeFromString(longSer, trip.momentsJson) }.getOrDefault(emptyList())
        if (moments.isNotEmpty()) card = card.copy(attachments = card.attachments.copy(driverMoments = moments.map { DriverMoment(fmt(it), nearestEpisode(card, it)) }))
        val newStatus = status ?: prevEntity?.status ?: "DRAFT"
        val entity = CardEntity(
            id = card.cardId, tripId = tripId, json = card.toJson(), status = newStatus,
            updatedAt = trip.arrivedAt ?: trip.endedAt ?: trip.lastSignalAt ?: trip.startedAt,
            version = (prevEntity?.version ?: 0) + 1,
            finalizeAt = if (newStatus == "PROVISIONAL") finalizeAt ?: prevEntity?.finalizeAt else null,
            serverStatus = prevEntity?.serverStatus, viewedAt = prevEntity?.viewedAt, ignoredCount = prevEntity?.ignoredCount ?: 0,
        )
        store.saveCard(entity)
        sync.push(entity)
        return card
    }

    private fun nearestEpisode(card: Card, t: Long): String? =
        card.visibleEpisodes.minByOrNull { kotlin.math.abs(java.time.OffsetDateTime.parse(it.startAt).toInstant().toEpochMilli() - t) }?.id

    fun setEpisodeHidden(tripId: String, episodeId: String, hidden: Boolean): Card? {
        if (trips.trip?.id == tripId) episodes.setHidden(episodeId, hidden) else store.setEpisodeHidden(episodeId, hidden)
        return composeCard(tripId)
    }

    fun updateOverrides(tripId: String, f: (Overrides) -> Overrides): Card? {
        val prevEntity = store.cardForTrip(tripId) ?: return null
        val prev = Card.fromJson(prevEntity.json)
        store.saveCard(prevEntity.copy(json = prev.copy(overrides = f(prev.overrides)).toJson()))
        return composeCard(tripId)
    }

    fun setCeremonyStep(tripId: String, step: Int?) {
        val t = store.trip(tripId) ?: return
        if (trips.trip?.id == tripId) trips.patch { it.copy(ceremonyStep = step) } else store.saveTrip(t.copy(ceremonyStep = step))
    }

    /** "지금 기억해" (SPEC 9장): 30 s cool-down. @return true if stored. */
    fun markMoment(t: Long): Boolean {
        val trip = trips.trip ?: return false
        val list = runCatching { Json.decodeFromString(longSer, trip.momentsJson) }.getOrDefault(emptyList())
        if (list.lastOrNull()?.let { t - it < config.momentCooldownSec * 1000L } == true) return false
        trips.patch { it.copy(momentsJson = Json.encodeToString(longSer, list + t)) }
        return true
    }

    /** "이 여정 기록 끄기": wipe the active trip and record nothing more of it (UX V10). */
    /** V10 [여기서 끝내기]: close the open trip now and build its FINAL card from what we have. */
    fun endActiveTrip(t: Long): List<PipelineEvent> {
        val tr = trips.endByUser(t) ?: return emptyList()
        val out = listOf(PipelineEvent.StateChanged(tr), finish(tr))
        ctx.resetTrip()
        Timber.tag("CB/trip").i("trip %s ended by the user", tr.tripId)
        return out
    }

    /** V10 [기록 멈추기]: forget this trip (no card) and stop recording until the next ignition (SPEC 5.8). */
    fun stopRecording(): String? {
        val id = forgetActiveTrip()
        store.saveSettings(store.settings().copy(recordOff = true))
        return id
    }

    fun forgetActiveTrip(): String? {
        val id = trips.discardActive() ?: return null
        episodes.startTrip("discarded-$id")
        ctx.resetTrip()
        Timber.tag("CB/trip").i("trip %s forgotten by the user", id)
        return id
    }

    private fun finish(tr: Transition): PipelineEvent {
        episodes.finish(ctx)
        episodes.persistStates()
        var trip = store.trip(tr.tripId)!!
        val exit = exitSnapshot()
        if (trip.discarded) {
            Timber.tag("CB/trip").i("trip %s discarded (%.2f km)", trip.id, trip.distanceKm)
            return PipelineEvent.Ended(trip.id, null, exit, tr.endReason)
        }
        val eps = store.episodes(trip.id)
        val hist = history(trip.id)
        val routine = routines.isRoutine(trip, eps) { Scorer.score(EpisodeType.valueOf(it.type), hist) }
        if (routine != trip.routine) { trip = trip.copy(routine = routine); store.saveTrip(trip) }
        if (!demoMode && tr.endReason == EndReason.NO_DEST_TIMEOUT && trip.lastLat != null && trip.lastLng != null) {
            val from = trip.provisionalEndAt ?: trip.endedAt ?: tr.t
            places.onStay(trip.lastLat!!, trip.lastLng!!, from, from + config.trip.noDestPauseMin * 60_000L)
        }
        val composed = composeCard(trip.id, "FINAL", back = exit)        // rarity from the history before this trip
        val news = if (demoMode) BuddyEngine.News() else buddy.onTripFinal(trip, eps, cities)
        val card = composed?.let { patchBuddy(trip.id, it, news) }
        store.clearDetectorStates(trip.id)
        return PipelineEvent.Ended(trip.id, card, exit, tr.endReason)
    }

    /** 확장 A: the FINAL card shows the buddy after this trip and what it changed (stage, new items). */
    private fun patchBuddy(tripId: String, card: Card, news: BuddyEngine.News): Card {
        val b = buddy.current()
        val patched = card.copy(buddy = BuddyInfo(b.stage, b.equipped, news.grewTo, news.newItems))
        if (patched == card) return card
        store.cardForTrip(tripId)?.let { store.saveCard(it.copy(json = patched.toJson())) }
        return patched
    }

    /** 확장 B exit check snapshot at belt release (windows, trunk, low beam, fuel). */
    private fun exitSnapshot(): Back {
        val openSeats = ctx.windows.filter { it.value >= config.exitWindowOpenPct }.keys.sortedBy { it.ordinal }.map(SeatPos::name)
        val lastFix = ctx.lastLocation
        val weakGps = lastFix == null || (lastFix.accuracyM ?: 0f) > config.parkingWeakAccuracyM ||
            ctx.now - lastFix.t > config.parkingNoFixMin * 60_000L
        return Back(
            windowsOpen = openSeats, trunkOpen = ctx.trunkOpen, lightsOn = ctx.lowBeamOn,
            fuelLow = (ctx.fuelPercent ?: 100) < config.fuelLowPct, parkingPrompt = weakGps,
        )
    }

    private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().format(iso)
}
