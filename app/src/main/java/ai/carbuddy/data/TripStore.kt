package ai.carbuddy.data

import ai.carbuddy.core.model.TripState

/**
 * Local persistence used by the trip pipeline. Blocking calls: the pipeline runs on a single
 * background dispatcher and writes on every event (the process may die at any moment, SPEC 5장).
 */
interface TripStore {
    fun activeTrip(): TripEntity?
    fun trip(id: String): TripEntity?
    fun saveTrip(trip: TripEntity)
    fun trips(): List<TripEntity>
    /** Removes a trip and everything recorded for it ("이 여정 기록 끄기", 보관함 지우기). */
    fun deleteTrip(id: String)

    fun upsertEpisode(e: EpisodeEntity)
    fun episodes(tripId: String): List<EpisodeEntity>
    fun setEpisodeHidden(id: String, hidden: Boolean)

    fun detectorStates(tripId: String): Map<String, String>
    fun saveDetectorState(tripId: String, detector: String, json: String)
    fun clearDetectorStates(tripId: String)

    fun saveCard(card: CardEntity)
    fun cardForTrip(tripId: String): CardEntity?
    fun card(id: String): CardEntity?
    fun cards(): List<CardEntity>
    fun deleteAllCards()

    fun addGap(g: TripGapEntity)
    fun gaps(tripId: String): List<TripGapEntity>

    fun addTrackPoint(p: TrackPointEntity)
    fun trackPoints(tripId: String): List<TrackPointEntity>
    fun deleteAllTrackPoints()

    fun addWaypointStop(w: WaypointStopEntity)
    fun waypointStops(tripId: String): List<WaypointStopEntity>

    fun places(): List<PlaceEntity>
    fun savePlace(p: PlaceEntity): Long
    fun deletePlaces()

    fun addNotifyLog(n: NotifyLogEntity)
    fun notifyLogs(date: String): List<NotifyLogEntity>

    fun appendSignal(row: SignalLogEntity)
    fun purgeSignalsBefore(t: Long)
    fun signals(from: Long, to: Long): List<SignalLogEntity>

    fun buddy(): BuddyEntity
    fun saveBuddy(b: BuddyEntity)

    fun settings(): SettingsEntity
    fun saveSettings(s: SettingsEntity)
    /** Dev: delete everything (repeatable demo takes). */
    fun wipeAll()

    fun ambientBackup(): AmbientBackupEntity?
    fun saveAmbientBackup(b: AmbientBackupEntity?)
}

/** In-memory store for unit tests and previews. Survives "process kill" by sharing the instance. */
class InMemoryTripStore : TripStore {
    private val tripsById = linkedMapOf<String, TripEntity>()
    private val episodesById = linkedMapOf<String, EpisodeEntity>()
    private val detector = mutableMapOf<Pair<String, String>, String>()
    private val cardsById = linkedMapOf<String, CardEntity>()
    private val gapRows = mutableListOf<TripGapEntity>()
    private val trackRows = mutableListOf<TrackPointEntity>()
    private val waypointRows = mutableListOf<WaypointStopEntity>()
    private val placeRows = linkedMapOf<Long, PlaceEntity>()
    private val notifyRows = mutableListOf<NotifyLogEntity>()
    private val signalRows = mutableListOf<SignalLogEntity>()
    private var buddyRow = BuddyEntity()
    private var settingsRow = SettingsEntity()
    private var ambient: AmbientBackupEntity? = null
    private var nextId = 1L

    @Synchronized override fun activeTrip() =
        tripsById.values.lastOrNull { it.state != TripState.ENDED.name }
    @Synchronized override fun trip(id: String) = tripsById[id]
    @Synchronized override fun saveTrip(trip: TripEntity) { tripsById[trip.id] = trip }
    @Synchronized override fun trips() = tripsById.values.toList()
    @Synchronized override fun deleteTrip(id: String) {
        tripsById.remove(id); episodesById.values.removeAll { it.tripId == id }
        cardsById.values.removeAll { it.tripId == id }; trackRows.removeAll { it.tripId == id }
        gapRows.removeAll { it.tripId == id }; waypointRows.removeAll { it.tripId == id }
        detector.keys.removeAll { it.first == id }
    }

    @Synchronized override fun upsertEpisode(e: EpisodeEntity) { episodesById[e.id] = e }
    @Synchronized override fun episodes(tripId: String) = episodesById.values.filter { it.tripId == tripId }
    @Synchronized override fun setEpisodeHidden(id: String, hidden: Boolean) {
        episodesById[id]?.let { episodesById[id] = it.copy(hidden = hidden) }
    }

    @Synchronized override fun detectorStates(tripId: String) =
        detector.filterKeys { it.first == tripId }.mapKeys { it.key.second }
    @Synchronized override fun saveDetectorState(tripId: String, detector: String, json: String) { this.detector[tripId to detector] = json }
    @Synchronized override fun clearDetectorStates(tripId: String) { detector.keys.removeAll { it.first == tripId } }

    @Synchronized override fun saveCard(card: CardEntity) {
        cardsById.values.firstOrNull { it.tripId == card.tripId && it.id != card.id }?.let { cardsById.remove(it.id) }
        cardsById[card.id] = card
    }
    @Synchronized override fun cardForTrip(tripId: String) = cardsById.values.firstOrNull { it.tripId == tripId }
    @Synchronized override fun card(id: String) = cardsById[id]
    @Synchronized override fun cards() = cardsById.values.sortedByDescending { it.updatedAt }
    @Synchronized override fun deleteAllCards() { cardsById.clear() }

    @Synchronized override fun addGap(g: TripGapEntity) { gapRows += g.copy(id = nextId++) }
    @Synchronized override fun gaps(tripId: String) = gapRows.filter { it.tripId == tripId }
    @Synchronized override fun addTrackPoint(p: TrackPointEntity) { trackRows += p.copy(id = nextId++) }
    @Synchronized override fun trackPoints(tripId: String) = trackRows.filter { it.tripId == tripId }
    @Synchronized override fun deleteAllTrackPoints() { trackRows.clear() }
    @Synchronized override fun addWaypointStop(w: WaypointStopEntity) { waypointRows += w.copy(id = nextId++) }
    @Synchronized override fun waypointStops(tripId: String) = waypointRows.filter { it.tripId == tripId }
    @Synchronized override fun places() = placeRows.values.toList()
    @Synchronized override fun savePlace(p: PlaceEntity): Long {
        val id = if (p.id == 0L) nextId++ else p.id
        placeRows[id] = p.copy(id = id); return id
    }
    @Synchronized override fun deletePlaces() { placeRows.clear() }
    @Synchronized override fun addNotifyLog(n: NotifyLogEntity) { notifyRows += n.copy(id = nextId++) }
    @Synchronized override fun notifyLogs(date: String) = notifyRows.filter { it.date == date }

    @Synchronized override fun appendSignal(row: SignalLogEntity) { signalRows += row }
    @Synchronized override fun purgeSignalsBefore(t: Long) { signalRows.removeAll { it.t < t } }
    @Synchronized override fun signals(from: Long, to: Long) = signalRows.filter { it.t in from..to }

    @Synchronized override fun buddy() = buddyRow
    @Synchronized override fun saveBuddy(b: BuddyEntity) { buddyRow = b }
    @Synchronized override fun settings() = settingsRow
    @Synchronized override fun saveSettings(s: SettingsEntity) { settingsRow = s }
    @Synchronized override fun wipeAll() { throw UnsupportedOperationException("tests create a fresh store") }
    @Synchronized override fun ambientBackup() = ambient
    @Synchronized override fun saveAmbientBackup(b: AmbientBackupEntity?) { ambient = b }
}
