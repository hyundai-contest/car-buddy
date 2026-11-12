package ai.carbuddy.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CarBuddyDao {
    @Query("SELECT * FROM trip WHERE state != 'ENDED' ORDER BY startedAt DESC LIMIT 1") fun activeTrip(): TripEntity?
    @Query("SELECT * FROM trip WHERE id = :id") fun trip(id: String): TripEntity?
    @Query("SELECT * FROM trip ORDER BY startedAt DESC") fun trips(): List<TripEntity>
    @Query("SELECT * FROM trip ORDER BY startedAt DESC") fun tripsFlow(): Flow<List<TripEntity>>
    @Upsert fun saveTrip(t: TripEntity)
    @Query("DELETE FROM trip WHERE id = :id") fun deleteTripRow(id: String)
    @Query("DELETE FROM episode WHERE tripId = :id") fun deleteEpisodes(id: String)
    @Query("DELETE FROM card WHERE tripId = :id") fun deleteCard(id: String)
    @Query("DELETE FROM track_point WHERE tripId = :id") fun deleteTrack(id: String)
    @Query("DELETE FROM trip_gap WHERE tripId = :id") fun deleteGaps(id: String)
    @Query("DELETE FROM waypoint_stop WHERE tripId = :id") fun deleteWaypointStops(id: String)
    @Transaction fun deleteTrip(id: String) {
        deleteTripRow(id); deleteEpisodes(id); deleteCard(id); deleteTrack(id); deleteGaps(id); deleteWaypointStops(id); clearDetectorStates(id)
    }

    @Insert fun addGap(g: TripGapEntity)
    @Query("SELECT * FROM trip_gap WHERE tripId = :tripId ORDER BY fromT") fun gaps(tripId: String): List<TripGapEntity>
    @Insert fun addTrackPoint(p: TrackPointEntity)
    @Query("SELECT * FROM track_point WHERE tripId = :tripId ORDER BY t") fun trackPoints(tripId: String): List<TrackPointEntity>
    @Query("DELETE FROM track_point") fun deleteAllTrackPoints()
    @Insert fun addWaypointStop(w: WaypointStopEntity)
    @Query("SELECT * FROM waypoint_stop WHERE tripId = :tripId ORDER BY fromT") fun waypointStops(tripId: String): List<WaypointStopEntity>
    @Query("SELECT * FROM place") fun places(): List<PlaceEntity>
    @Upsert fun upsertPlace(p: PlaceEntity): Long
    @Query("DELETE FROM place") fun deletePlaces()
    @Insert fun addNotifyLog(n: NotifyLogEntity)
    @Query("SELECT * FROM notify_log WHERE date = :date") fun notifyLogs(date: String): List<NotifyLogEntity>

    @Upsert fun upsertEpisode(e: EpisodeEntity)
    @Query("SELECT * FROM episode WHERE tripId = :tripId ORDER BY startT") fun episodes(tripId: String): List<EpisodeEntity>
    @Query("UPDATE episode SET hidden = :hidden WHERE id = :id") fun setEpisodeHidden(id: String, hidden: Boolean)

    @Query("SELECT * FROM detector_state WHERE tripId = :tripId") fun detectorStates(tripId: String): List<DetectorStateEntity>
    @Upsert fun saveDetectorState(s: DetectorStateEntity)
    @Query("DELETE FROM detector_state WHERE tripId = :tripId") fun clearDetectorStates(tripId: String)

    @Query("DELETE FROM card WHERE tripId = :tripId AND id != :keepId") fun deleteOtherCards(tripId: String, keepId: String)
    @Upsert fun upsertCard(c: CardEntity)
    @Transaction fun saveCard(c: CardEntity) { deleteOtherCards(c.tripId, c.id); upsertCard(c) }
    @Query("SELECT * FROM card WHERE tripId = :tripId LIMIT 1") fun cardForTrip(tripId: String): CardEntity?
    @Query("SELECT * FROM card WHERE id = :id") fun card(id: String): CardEntity?
    @Query("SELECT * FROM card ORDER BY updatedAt DESC") fun cards(): List<CardEntity>
    @Query("SELECT * FROM card ORDER BY updatedAt DESC") fun cardsFlow(): Flow<List<CardEntity>>
    @Query("DELETE FROM card") fun deleteAllCards()

    @Insert fun appendSignal(row: SignalLogEntity)
    @Query("DELETE FROM signal_log WHERE t < :t") fun purgeSignalsBefore(t: Long)
    @Query("SELECT * FROM signal_log WHERE t BETWEEN :from AND :to ORDER BY id") fun signals(from: Long, to: Long): List<SignalLogEntity>

    @Query("SELECT * FROM buddy WHERE id = 1") fun buddy(): BuddyEntity?
    @Upsert fun saveBuddy(b: BuddyEntity)
    @Query("SELECT * FROM settings WHERE id = 1") fun settings(): SettingsEntity?
    @Query("SELECT * FROM settings WHERE id = 1") fun settingsFlow(): Flow<SettingsEntity?>
    @Upsert fun saveSettings(s: SettingsEntity)
    @Query("SELECT * FROM ambient_backup WHERE id = 1") fun ambientBackup(): AmbientBackupEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveAmbientBackup(b: AmbientBackupEntity)
    @Query("DELETE FROM ambient_backup") fun clearAmbientBackup()
}

@Database(
    entities = [
        TripEntity::class, EpisodeEntity::class, CardEntity::class, DetectorStateEntity::class,
        SignalLogEntity::class, BuddyEntity::class, AmbientBackupEntity::class, SettingsEntity::class,
        TripGapEntity::class, TrackPointEntity::class, WaypointStopEntity::class, PlaceEntity::class, NotifyLogEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CarBuddyDao

    companion object {
        /** v3: trip.halftimeShownAt (중간 상영). Adding a nullable column keeps every trip and card. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip ADD COLUMN halftimeShownAt INTEGER")
            }
        }
    }
}

class RoomTripStore(private val dao: CarBuddyDao, private val clearAll: () -> Unit = {}) : TripStore {
    override fun wipeAll() = clearAll()

    override fun activeTrip() = dao.activeTrip()
    override fun trip(id: String) = dao.trip(id)
    override fun saveTrip(trip: TripEntity) = dao.saveTrip(trip)
    override fun trips() = dao.trips()
    override fun deleteTrip(id: String) = dao.deleteTrip(id)
    override fun addGap(g: TripGapEntity) = dao.addGap(g)
    override fun gaps(tripId: String) = dao.gaps(tripId)
    override fun addTrackPoint(p: TrackPointEntity) = dao.addTrackPoint(p)
    override fun trackPoints(tripId: String) = dao.trackPoints(tripId)
    override fun deleteAllTrackPoints() = dao.deleteAllTrackPoints()
    override fun addWaypointStop(w: WaypointStopEntity) = dao.addWaypointStop(w)
    override fun waypointStops(tripId: String) = dao.waypointStops(tripId)
    override fun places() = dao.places()
    override fun savePlace(p: PlaceEntity): Long = dao.upsertPlace(p).let { if (it == -1L) p.id else it }
    override fun deletePlaces() = dao.deletePlaces()
    override fun addNotifyLog(n: NotifyLogEntity) = dao.addNotifyLog(n)
    override fun notifyLogs(date: String) = dao.notifyLogs(date)
    override fun upsertEpisode(e: EpisodeEntity) = dao.upsertEpisode(e)
    override fun episodes(tripId: String) = dao.episodes(tripId)
    override fun setEpisodeHidden(id: String, hidden: Boolean) = dao.setEpisodeHidden(id, hidden)
    override fun detectorStates(tripId: String) = dao.detectorStates(tripId).associate { it.detector to it.stateJson }
    override fun saveDetectorState(tripId: String, detector: String, json: String) =
        dao.saveDetectorState(DetectorStateEntity(tripId, detector, json))
    override fun clearDetectorStates(tripId: String) = dao.clearDetectorStates(tripId)
    override fun saveCard(card: CardEntity) = dao.saveCard(card)
    override fun cardForTrip(tripId: String) = dao.cardForTrip(tripId)
    override fun card(id: String) = dao.card(id)
    override fun cards() = dao.cards()
    override fun deleteAllCards() = dao.deleteAllCards()
    override fun appendSignal(row: SignalLogEntity) = dao.appendSignal(row)
    override fun purgeSignalsBefore(t: Long) = dao.purgeSignalsBefore(t)
    override fun signals(from: Long, to: Long) = dao.signals(from, to)
    override fun buddy() = dao.buddy() ?: BuddyEntity()
    override fun saveBuddy(b: BuddyEntity) = dao.saveBuddy(b)
    override fun settings() = dao.settings() ?: SettingsEntity()
    override fun saveSettings(s: SettingsEntity) = dao.saveSettings(s)
    override fun ambientBackup() = dao.ambientBackup()
    override fun saveAmbientBackup(b: AmbientBackupEntity?) { if (b == null) dao.clearAmbientBackup() else dao.saveAmbientBackup(b) }
}
