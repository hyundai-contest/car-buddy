package ai.carbuddy.signal

import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.WiperMode

/** Common input of every module (SPEC 4.1). t = epoch ms (virtual clock during replay). */
sealed interface SignalEvent { val t: Long }

data class Speed(override val t: Long, val kmh: Float) : SignalEvent
data class Gear(override val t: Long, val gear: GearPos) : SignalEvent
data class DriverBelt(override val t: Long, val buckled: Boolean) : SignalEvent
data class SeatOccupancy(override val t: Long, val seats: Map<SeatPos, Boolean>) : SignalEvent
data class Wiper(override val t: Long, val front: WiperMode) : SignalEvent
/** Open percentage per seat, 0 = closed … 100 = fully open. */
data class WindowPos(override val t: Long, val pos: Map<SeatPos, Int>) : SignalEvent
data class LowBeam(override val t: Long, val on: Boolean) : SignalEvent
data class HighBeam(override val t: Long, val on: Boolean) : SignalEvent
data class HvacTarget(override val t: Long, val driverC: Float, val passengerC: Float) : SignalEvent
data class Location(override val t: Long, val lat: Double, val lng: Double, val accuracyM: Float?, val bearing: Float?) : SignalEvent
data class TurnSignal(override val t: Long, val on: Boolean) : SignalEvent
data class FuelPercent(override val t: Long, val percent: Int) : SignalEvent
/** Trunk / door state for the exit check (확장 B). */
data class TrunkOpen(override val t: Long, val open: Boolean) : SignalEvent

// Navi
data class RouteStarted(override val t: Long, val destName: String?, val destLat: Double?, val destLng: Double?, val etaEpochMs: Long?) : SignalEvent
data class ReRouteEnded(override val t: Long) : SignalEvent
data class DestinationChanged(override val t: Long, val destName: String?, val destLat: Double?, val destLng: Double?) : SignalEvent
/** Waypoint added/changed. Coordinates let a stop near it count as a planned stop (SPEC 5.5). Field names TODO(SDK). */
data class WaypointChanged(override val t: Long, val name: String? = null, val lat: Double? = null, val lng: Double? = null) : SignalEvent
data class RouteCancelled(override val t: Long) : SignalEvent
data class RemainTime(override val t: Long, val remainSec: Int) : SignalEvent
data class DestinationArrived(override val t: Long) : SignalEvent
data class CameraAlert(override val t: Long, val limitKmh: Int?) : SignalEvent

enum class BootReason { BOOT, APP_START }

/** App process (re)start: BootReceiver (BOOT) or Application.onCreate (APP_START). Enters TripRecovery. */
data class PowerBoot(override val t: Long, val reason: BootReason = BootReason.APP_START) : SignalEvent

/** Signals were silent for more than signalGapMin and came back (Suspend-to-RAM). Made by the pipeline. */
data class SignalResumed(override val t: Long, val silentSince: Long) : SignalEvent

enum class PauseKind {
    /** an ordinary stop inside a trip (rest area, unplanned place) */
    STOP,
    /** a stop near a navigation waypoint: planned, shown as '들른 곳' */
    WAYPOINT,
    /** a provisional arrival of a no-destination trip that was resumed: '들른 곳' */
    PROVISIONAL,
}

/** Derived by TripEngine when a PAUSED stretch ends and the same trip resumes (SPEC 4.1, 5.3). */
data class PauseClosed(
    override val t: Long,
    val pausedAt: Long,
    val lat: Double?,
    val lng: Double?,
    val kind: PauseKind = PauseKind.STOP,
    val remainBeforeSec: Int? = null,
    val distToDestM: Double? = null,
) : SignalEvent {
    val stayMinutes: Double get() = (t - pausedAt) / 60_000.0
}

enum class PowerCycleMode { REBOOT, SUSPEND, LATE_START }

/** Replay-only marker for an ignition-off (SPEC 4.3). Test harnesses act on it; the pipeline ignores it. */
data class PowerCycle(override val t: Long, val offSec: Int, val mode: PowerCycleMode, val skipSec: Int = 0) : SignalEvent

/**
 * Internal heartbeat from the clock source (1 s live, every few virtual seconds in replay).
 * Lets time-based rules (pause timeout, retry, "lasted N minutes") fire without a new signal.
 * Not written to signal_log.
 */
data class Tick(override val t: Long) : SignalEvent
