package ai.carbuddy.signal.adapter

import ai.carbuddy.core.time.Clock
import ai.carbuddy.signal.CameraAlert
import ai.carbuddy.signal.DestinationArrived
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.DriverBelt
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.ReRouteEnded
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteCancelled
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.SeatOccupancy
import ai.carbuddy.signal.WindowPos
import ai.carbuddy.signal.Wiper
import ai.carbuddy.signal.HvacTarget
import ai.carbuddy.signal.WaypointChanged
import ai.carbuddy.signal.SignalHub
import timber.log.Timber

/**
 * A live signal source feeding [ai.carbuddy.signal.SignalHub]. Pleos / AAOS calls happen only
 * inside implementations under the signal/adapter package (CLAUDE.md 규칙 2). Unsupported signals are
 * simply not emitted and reported to the CapabilityRegistry.
 *
 * Wired: pleos (Vehicle SDK), aaos (speed, CarUxRestrictions), navi (NaviHelper SDK),
 * location (Pleos FusedLocation, Android GPS when FusedLocation is not supported).
 */
interface SignalAdapter {
    val name: String
    /** Idempotent: called at runtime start and again when permissions may have changed. */
    fun start()
    fun stop() {}
}

/** DI holder for the live adapters (avoids generic-wildcard issues in Dagger). */
class SignalAdapters(val all: List<SignalAdapter>)

/**
 * Stamps live events with the app clock and pushes them into the hub from any SDK callback thread.
 * While a replay runs ([replayActive]), the live signals that decide the trip itself — motion,
 * location, navigation — are dropped so the car's real (on the emulator: fixed) values cannot
 * corrupt the log; cabin signals still pass, so console changes can be layered on a replay (SPEC 4.3).
 */
class LiveEmitter(
    private val hub: SignalHub,
    private val clock: Clock,
    private val replayActive: () -> Boolean = { false },
    private val guard: ReplayGuard? = null,
) {
    /** Latest real motion values (also the dropped ones), re-sent when a replay ends. */
    private val lastReal = java.util.concurrent.ConcurrentHashMap<String, (Long) -> SignalEvent>()

    init { guard?.register(this) }

    fun now(): Long = clock.now()

    fun emit(make: (Long) -> SignalEvent) {
        val e = make(clock.now())
        realKey(e)?.let { lastReal[it] = make }
        if (replayActive() && e.drivesTrip()) {
            // 체험 모드 safety: the real car starts to move → the replay stops at once
            if ((e is Gear && (e.gear == ai.carbuddy.core.model.GearPos.D || e.gear == ai.carbuddy.core.model.GearPos.R)) || (e is Speed && e.kmh > 5f)) guard?.realMotion()
            return
        }
        Timber.tag("CB/live").d("%s", e)
        if (!hub.tryEmit(e)) Timber.tag("CB/signal").w("hub buffer full, dropped %s", e)
    }

    /** After a replay: the trip context gets the car's real gear / speed / belt / position back. */
    fun resendReal() = lastReal.values.forEach { make -> val e = make(clock.now()); if (!hub.tryEmit(e)) Timber.tag("CB/signal").w("resend dropped %s", e) }

    /** Per-area events merge in TripContext, so each area keeps its own latest value. */
    private fun realKey(e: SignalEvent): String? = when (e) {
        is Gear, is Speed, is DriverBelt, is Location, is Wiper, is HvacTarget -> e::class.java.simpleName
        is SeatOccupancy -> "SeatOccupancy" + e.seats.keys.sorted()
        is WindowPos -> "WindowPos" + e.pos.keys.sorted()
        else -> null
    }

    private fun SignalEvent.drivesTrip() = when (this) {
        is Speed, is Gear, is Location, is DriverBelt, is RouteStarted, is RemainTime, is DestinationChanged,
        is WaypointChanged, is ReRouteEnded, is RouteCancelled, is DestinationArrived, is CameraAlert -> true
        else -> false
    }
}

/** Links the live emitters to the replay owner: real motion during a replay, real values afterwards. */
class ReplayGuard {
    private val emitters = java.util.concurrent.CopyOnWriteArrayList<LiveEmitter>()
    @Volatile var onRealMotion: (() -> Unit)? = null

    fun register(e: LiveEmitter) { emitters += e }
    fun realMotion() { onRealMotion?.invoke() }
    fun resendReal() = emitters.forEach { it.resendReal() }
}

/**
 * Driving-time UX restrictions (SPEC 9장 UiModeResolver). [available] = false means the platform
 * service is not reachable and callers fall back to "restricted = not parked".
 */
interface UxRestrictionsSource {
    val available: Boolean
    /** CarUxRestrictions.isRequiresDistractionOptimization */
    val restricted: Boolean
    /** CarUxRestrictions.getMaxRestrictedStringLength while restricted, else null. */
    val maxStringLength: Int?

    object None : UxRestrictionsSource {
        override val available = false
        override val restricted = false
        override val maxStringLength: Int? = null
    }
}
