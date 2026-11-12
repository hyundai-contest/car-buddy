package ai.carbuddy.episode

import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.detectors.CameraCleanDetector
import ai.carbuddy.episode.detectors.DetourDetector
import ai.carbuddy.episode.detectors.DriverSwapDetector
import ai.carbuddy.episode.detectors.FullHouseDetector
import ai.carbuddy.episode.detectors.NightDriveDetector
import ai.carbuddy.episode.detectors.RevisitDetector
import ai.carbuddy.episode.detectors.SunsetDriveDetector
import ai.carbuddy.episode.detectors.TunnelParadeDetector
import ai.carbuddy.episode.detectors.WindRunDetector
import ai.carbuddy.episode.detectors.JamEnduredDetector
import ai.carbuddy.episode.detectors.LostLoopDetector
import ai.carbuddy.episode.detectors.RestStopDetector
import ai.carbuddy.episode.detectors.SuddenRainDetector
import ai.carbuddy.episode.detectors.TempWarDetector
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import timber.log.Timber

/**
 * Which signal families this car supports (SPEC 4.2). Live adapters report families whose
 * registration fails ([markUnsupported]); detectors needing them are switched off. A replay
 * brings every signal itself, so while [replayActive] everything counts as supported.
 */
class CapabilityRegistry(initial: Set<Capability> = Capability.entries.toSet()) {
    @Volatile private var base: Set<Capability> = initial
    @Volatile private var missing: Set<Capability> = emptySet()
    @Volatile var replayActive: Boolean = false

    val supported: Set<Capability> get() = if (replayActive) base else base - missing
    /** Families a live adapter could not read on this car. */
    val unsupportedLive: Set<Capability> get() = missing

    @Synchronized fun markUnsupported(c: Capability) { missing = missing + c }
    @Synchronized fun markSupported(c: Capability) { missing = missing - c }
    fun supports(c: Set<Capability>) = supported.containsAll(c)
}

/**
 * Runs the detectors for the active trip and keeps their episodes in the store (SPEC 6장).
 * Candidates are upserted by (trip, type, key): re-emitting updates the same episode.
 */
class EpisodeEngine(
    val detectors: List<EpisodeDetector>,
    private val store: TripStore,
    private val capabilities: CapabilityRegistry = CapabilityRegistry(),
) {
    private var tripId: String? = null
    private val episodes = linkedMapOf<String, EpisodeEntity>()
    private val savedStates = mutableMapOf<String, String>()

    val current: List<EpisodeEntity> get() = episodes.values.toList()

    private fun active() = detectors.filter { capabilities.supports(it.requires) }

    /** Episode types this car can detect (dex: "이 차에서는 볼 수 없어요" for the rest). */
    fun activeTypes(): Set<String> = active().map { it.type.name }.toSet()

    fun startTrip(id: String) {
        tripId = id
        episodes.clear()
        savedStates.clear()
        detectors.forEach { it.reset() }
    }

    /** Process restart: reload episodes and detector state of the active trip. */
    fun restore(id: String) {
        startTrip(id)
        val states = store.detectorStates(id)
        for (d in detectors) states[d.id]?.let { json ->
            runCatching { d.restoreState(json); savedStates[d.id] = json }
                .onFailure { Timber.tag("CB/episode").w(it, "restore %s failed", d.id) }
        }
        store.episodes(id).forEach { episodes[it.id] = it }
    }

    fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeEntity> =
        active().flatMap { d -> apply(d, safely(d) { d.onEvent(e, ctx) }) }

    fun finish(ctx: TripContext): List<EpisodeEntity> =
        active().flatMap { d -> apply(d, safely(d) { d.onTripEnd(ctx) }) }

    fun setHidden(episodeId: String, hidden: Boolean) {
        episodes[episodeId]?.let { episodes[episodeId] = it.copy(hidden = hidden) }
        store.setEpisodeHidden(episodeId, hidden)
    }

    /** Persists detector states that changed since the last save (call after each event). */
    fun persistStates() {
        val id = tripId ?: return
        for (d in detectors) {
            val json = d.saveState() ?: continue
            if (savedStates[d.id] != json) {
                store.saveDetectorState(id, d.id, json)
                savedStates[d.id] = json
            }
        }
    }

    private inline fun safely(d: EpisodeDetector, block: () -> List<EpisodeCandidate>): List<EpisodeCandidate> =
        try { block() } catch (t: Exception) {
            Timber.tag("CB/episode").e(t, "%s failed", d.id); emptyList()
        }

    /** Rows are keyed by detector + candidate key, so a loop re-typed to PARKING_HUNT stays one row. */
    private fun apply(d: EpisodeDetector, cands: List<EpisodeCandidate>): List<EpisodeEntity> {
        val id = tripId ?: return emptyList()
        val changed = mutableListOf<EpisodeEntity>()
        for (c in cands) {
            val eid = "$id:${d.id}:${c.key}"
            val old = episodes[eid]
            val e = EpisodeEntity(
                id = eid, tripId = id, type = c.type.name, key = c.key,
                startT = c.startT, endT = c.endT, paramsJson = Params.encode(c.params),
                lostMinutes = c.lostMinutes, score = old?.score ?: 0.0,
                hidden = old?.hidden ?: false, star = old?.star ?: false,
            )
            if (e == old) continue
            if (old == null) Timber.tag("CB/episode").i("captured %s %s", c.type, c.params)
            episodes[eid] = e
            store.upsertEpisode(e)
            changed += e
        }
        return changed
    }

    companion object {
        /** The six Must detectors (SPEC 6.2). */
        fun mustDetectors(cities: CityResolver?, places: PlaceNameProvider? = null): List<EpisodeDetector> = listOf(
            LostLoopDetector(cities),
            DetourDetector(places),
            SuddenRainDetector(),
            TempWarDetector(),
            JamEnduredDetector(),
            RestStopDetector(places),
        )

        /** The eight PT detectors (기획안 v13 3장). [revisit] finds an earlier visit to the end city. */
        fun ptDetectors(revisit: (Double, Double) -> RevisitDetector.Visit?): List<EpisodeDetector> = listOf(
            FullHouseDetector(),
            WindRunDetector(),
            TunnelParadeDetector(),
            SunsetDriveDetector(),
            DriverSwapDetector(),
            CameraCleanDetector(),
            RevisitDetector(revisit),
            NightDriveDetector(),
        )

        fun typeOf(e: EpisodeEntity): EpisodeType = EpisodeType.valueOf(e.type)
    }
}
