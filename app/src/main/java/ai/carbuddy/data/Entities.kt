package ai.carbuddy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** SPEC 12장 trip table. All times are wall clock (SPEC 5.1). */
@Entity(tableName = "trip", indices = [Index("state")])
data class TripEntity(
    @PrimaryKey val id: String,
    val state: String,
    val stateSince: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val origDestName: String? = null,
    val origDestLat: Double? = null,
    val origDestLng: Double? = null,
    val destName: String? = null,
    val destLat: Double? = null,
    val destLng: Double? = null,
    /** destination set after departure (SPEC 5.5); null = set before / at departure */
    val destSetAt: Long? = null,
    val baseEtaAt: Long? = null,
    val hasNavi: Boolean = false,
    val navEnded: Boolean = false,
    val lastRemainSec: Int? = null,
    val arrivedAt: Long? = null,
    val arrivalKnown: Boolean = true,
    val distanceKm: Double = 0.0,
    val discarded: Boolean = false,
    val serverSynced: Boolean = false,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastSignalAt: Long? = null,
    val lastGear: String? = null,
    val lastSpeed: Float? = null,
    /** when the car last became parked (gear P or zero-speed run start) */
    val parkedSince: Long? = null,
    val pausedAt: Long? = null,
    val pauseLat: Double? = null,
    val pauseLng: Double? = null,
    val pauseRemainSec: Int? = null,
    val pauseKind: String? = null,
    val pausedTotalMs: Long = 0,
    val provisional: Boolean = false,
    val provisionalEndAt: Long? = null,
    val routine: Boolean = false,
    val recoveredAt: Long? = null,
    /** waiting for the first location after a recovery (SPEC 5.3 2단계) */
    val recovering: Boolean = false,
    val recapDone: Boolean = false,
    /** Ceremony progress: null = not started, -1 = finished, else last step index ("이어 보기"). */
    val ceremonyStep: Int? = null,
    val maxPassengers: Int = 1,
    /** navigation waypoints [{name,lat,lng}] (local only) */
    val waypointsJson: String = "[]",
    // vehicle tallies for the receipt header (SPEC 8장 여정 내역)
    val windowOpenMs: Long = 0,
    val wiperOnMs: Long = 0,
    val tunnelCount: Int = 0,
    /** driver's "지금 기억해" times [t,…] (SPEC 9장) */
    val momentsJson: String = "[]",
    /** 중간 상영 offered at (NEXT_DESIGN A): once per trip, kept across a reboot at the rest stop. */
    val halftimeShownAt: Long? = null,
)

@Entity(tableName = "trip_gap", indices = [Index("tripId")])
data class TripGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val fromT: Long,
    val toT: Long,
    val reason: String,              // LATE_START / CRASH
    val straightKm: Double,
)

/** Raw route points, local only (SPEC 10.1). Kept 30 days after the trip ends. */
@Entity(tableName = "track_point", indices = [Index("tripId")])
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val t: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
)

@Entity(tableName = "waypoint_stop", indices = [Index("tripId")])
data class WaypointStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val name: String?,
    val fromT: Long,
    val toT: Long,
    val kind: String,                // WAYPOINT / PROVISIONAL
)

/** Frequently visited places, local only (SPEC 6.4). */
@Entity(tableName = "place")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val visitCount: Int,
    val lastVisitAt: Long,
    val visitsJson: String = "[]",
    val frequent: Boolean = false,
    val name: String? = null,
)

@Entity(tableName = "notify_log", indices = [Index("date")])
data class NotifyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,                // yyyy-MM-dd (KST)
    val kind: String,                // N1 / N4
    val cardId: String,
    val t: Long,
)

@Entity(tableName = "episode", indices = [Index("tripId")])
data class EpisodeEntity(
    @PrimaryKey val id: String,          // "$tripId:$type:$key"
    val tripId: String,
    val type: String,
    val key: String,
    val startT: Long,
    val endT: Long,
    val paramsJson: String,
    val lostMinutes: Int? = null,
    val score: Double = 0.0,
    val hidden: Boolean = false,
    val star: Boolean = false,
)

@Entity(tableName = "card", indices = [Index("tripId", unique = true)])
data class CardEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val json: String,
    val status: String,               // DRAFT / PROVISIONAL / FINAL (SPEC 5.7)
    val updatedAt: Long,
    val version: Int = 1,
    val finalizeAt: Long? = null,
    val serverStatus: String? = null,
    val viewedAt: Long? = null,
    val ignoredCount: Int = 0,
)

@Entity(tableName = "detector_state", primaryKeys = ["tripId", "detector"])
data class DetectorStateEntity(
    val tripId: String,
    val detector: String,
    val stateJson: String,
)

@Entity(tableName = "signal_log", indices = [Index("t")])
data class SignalLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val t: Long,
    val type: String,
    val payloadJson: String,
)

@Entity(tableName = "buddy")
data class BuddyEntity(
    @PrimaryKey val id: Int = 1,
    val name: String = "버디",
    val stage: String = "EGG",
    val itemsJson: String = "[]",
    val equipped: String? = null,
    val tripCount: Int = 0,
    val totalKm: Double = 0.0,
    val citiesJson: String = "[]",
    val typeCountsJson: String = "{}",
)

@Entity(tableName = "ambient_backup")
data class AmbientBackupEntity(
    @PrimaryKey val id: Int = 1,
    val brightness: Int,
    val color: Int,
    val savedAt: Long,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val onboarded: Boolean = false,
    val recapOn: Boolean = true,
    val screeningVoiceOn: Boolean = true,
    val ambientOn: Boolean = true,
    val shortTripScreening: Boolean = false,
    val clipOn: Boolean = false,
    val syncOn: Boolean = false,
    val routeTrimOn: Boolean = true,
    /** "오늘은 기록 안 함": cleared on the next boot (SPEC 5.8) */
    val recordOff: Boolean = false,
    /** "기록 일시정지": until turned back on */
    val recordPaused: Boolean = false,
    val guestRecord: Boolean = false,
    val pairedPhone: Boolean = false,
    /** Dev switch until Pleos DO approval is known (SPEC 19장): lets V10 driving mode render. */
    val doApproved: Boolean = false,
)
