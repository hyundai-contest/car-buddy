package ai.carbuddy.trip

import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.TripState
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.core.util.Geo
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.signal.CameraAlert
import ai.carbuddy.signal.DestinationArrived
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.DriverBelt
import ai.carbuddy.signal.FuelPercent
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.HvacTarget
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.LowBeam
import ai.carbuddy.signal.ReRouteEnded
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteCancelled
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SeatOccupancy
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.TrunkOpen
import ai.carbuddy.signal.TurnSignal
import ai.carbuddy.signal.WaypointChanged
import ai.carbuddy.signal.WindowPos
import ai.carbuddy.signal.Wiper
import kotlinx.serialization.Serializable

@Serializable
data class Dest(val name: String?, val lat: Double?, val lng: Double?)

@Serializable
data class Waypoint(val name: String?, val lat: Double, val lng: Double)

/**
 * Latest known vehicle / navigation state plus the active trip's identity (SPEC 6.1 TripContext).
 * Updated by [update] before the trip engine and detectors see an event.
 */
class TripContext(val config: DetectorConfig) {
    var now: Long = 0L
        private set

    // trip identity (set by TripEngine)
    var tripId: String? = null
    var tripStartedAt: Long = 0L
    var state: TripState = TripState.IDLE
    var destSetAt: Long? = null

    // navigation
    var dest: Dest? = null
    var origDest: Dest? = null
    /** when the current route was received (pending-route window, SPEC 5.5) */
    var routeT: Long = 0L
    var routeEta: Long? = null
    var remainSec: Int? = null
    var remainT: Long = 0L
    var destArrivedAt: Long? = null
    var routeCancelled = false
    /** guidance ended or was cancelled at some point (SPEC 5.6 ③) */
    var navEnded = false
    var hasNavi = false
    val waypoints = mutableListOf<Waypoint>()
    var cameraAlertAt: Long? = null

    // vehicle
    var gear: GearPos = GearPos.UNKNOWN
    var speedKmh: Float = 0f
    var speedT: Long = 0L
    var speedKnown = false
    /** Speed was above walking pace at least once since this process started. */
    private var movedSinceStart = false
    private var speedZeroSince: Long? = null
    private var parkedGearSince: Long? = null
    var driverBelt: Boolean? = null
    var seats: Map<SeatPos, Boolean> = emptyMap()
    var hvac: HvacTarget? = null
    var windows: Map<SeatPos, Int> = emptyMap()
    var wiper: WiperMode = WiperMode.OFF
    var lowBeamOn = false
    var turnSignalOn = false
    var fuelPercent: Int? = null
    var trunkOpen = false

    // location
    var lastLocation: Location? = null
    val recentLocations = ArrayDeque<Location>()

    val gearKnown: Boolean get() = gear != GearPos.UNKNOWN
    /** True once any gear or speed signal arrived (before that we know nothing about motion). */
    val motionKnown: Boolean get() = gearKnown || speedKnown

    fun update(e: SignalEvent) {
        now = maxOf(now, e.t)
        when (e) {
            is Speed -> {
                speedKmh = e.kmh; speedT = e.t; speedKnown = true
                if (e.kmh > config.trip.drivingSpeedKmh) movedSinceStart = true
                if (e.kmh <= 0.5f) { if (speedZeroSince == null) speedZeroSince = e.t } else speedZeroSince = null
            }
            is Gear -> {
                if (e.gear == GearPos.P && gear != GearPos.P) parkedGearSince = e.t
                if (e.gear != GearPos.P) parkedGearSince = null
                gear = e.gear
            }
            is DriverBelt -> driverBelt = e.buckled
            is SeatOccupancy -> seats = seats + e.seats
            is HvacTarget -> hvac = e
            is WindowPos -> windows = windows + e.pos
            is Wiper -> wiper = e.front
            is LowBeam -> lowBeamOn = e.on
            is TurnSignal -> turnSignalOn = e.on
            is FuelPercent -> fuelPercent = e.percent
            is TrunkOpen -> trunkOpen = e.open
            is Location -> {
                lastLocation = e
                recentLocations.addLast(e)
                val cutoff = e.t - 30 * 60_000L
                while (recentLocations.isNotEmpty() && recentLocations.first().t < cutoff) recentLocations.removeFirst()
            }
            is RouteStarted -> {
                hasNavi = true
                val d = Dest(e.destName, e.destLat, e.destLng)
                if (routeCancelled || dest == null || state == TripState.IDLE) {
                    dest = d; routeT = e.t; routeEta = e.etaEpochMs
                    if (origDest == null || state == TripState.IDLE) origDest = d
                }
                destArrivedAt = null; routeCancelled = false; navEnded = false
            }
            is DestinationChanged -> { hasNavi = true; dest = Dest(e.destName, e.destLat, e.destLng); destArrivedAt = null; routeCancelled = false; navEnded = false }
            is RouteCancelled -> { hasNavi = true; routeCancelled = true; navEnded = true }
            is WaypointChanged -> { hasNavi = true; if (e.lat != null && e.lng != null) waypoints += Waypoint(e.name, e.lat, e.lng) }
            is ReRouteEnded -> hasNavi = true
            is RemainTime -> { hasNavi = true; remainSec = e.remainSec; remainT = e.t }
            is DestinationArrived -> { hasNavi = true; destArrivedAt = e.t; navEnded = true }
            is CameraAlert -> cameraAlertAt = e.t
            else -> Unit
        }
    }

    /** "P" per SPEC 5장: gear P, or (gear unreadable) Speed=0 for parkedSpeedZeroSec. */
    fun isParked(t: Long = now): Boolean =
        if (gearKnown) gear == GearPos.P
        // standing still since the app started (speed arrives before the gear at boot): parked at once;
        // after moving, a zero-speed stop counts only after parkedSpeedZeroSec (traffic lights)
        else speedZeroSince?.let { !movedSinceStart || t - it >= config.trip.parkedSpeedZeroSec * 1000L } ?: false

    /** When the car became parked (gear P time, or start of the zero-speed run). */
    fun parkedSince(): Long? = if (gearKnown) parkedGearSince else speedZeroSince

    /** Recovery: the persisted "parked since" survives a reboot. */
    fun restoreParkedSince(t: Long?) { if (gear == GearPos.P && parkedGearSince == null) parkedGearSince = t }

    /** Departure condition (SPEC 5.5): Gear D (or unreadable) and moving. Reverse alone never starts a trip. */
    fun isDeparting(): Boolean =
        (gear == GearPos.D || gear == GearPos.UNKNOWN) && speedKmh > config.trip.drivingSpeedKmh

    fun isMoving(): Boolean = gear == GearPos.D || gear == GearPos.R || speedKmh > config.trip.drivingSpeedKmh

    fun passengerCount(): Int = maxOf(1, seats.count { it.value })

    fun distanceToDestM(lat: Double, lng: Double, d: Dest? = dest): Double? {
        val dl = d?.lat ?: return null
        val dn = d.lng ?: return null
        return Geo.distanceM(lat, lng, dl, dn)
    }

    fun nearWaypoint(lat: Double, lng: Double): Waypoint? =
        waypoints.firstOrNull { Geo.distanceM(lat, lng, it.lat, it.lng) <= config.trip.waypointRadiusM }

    /** After a replay: motion and cabin are unknown again until the car's real values arrive (re-sent by ReplayGuard). */
    fun resetMotion() {
        gear = GearPos.UNKNOWN; speedKmh = 0f; speedKnown = false; speedZeroSince = null; parkedGearSince = null
        driverBelt = null; movedSinceStart = false
        seats = emptyMap(); hvac = null; windows = emptyMap(); wiper = WiperMode.OFF
    }

    fun resetTrip() {
        tripId = null; tripStartedAt = 0L; destSetAt = null
        dest = null; origDest = null; routeT = 0L; routeEta = null; remainSec = null; destArrivedAt = null
        routeCancelled = false; navEnded = false; hasNavi = false; waypoints.clear()
        recentLocations.clear()
    }
}
