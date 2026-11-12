package ai.carbuddy.voice

import ai.carbuddy.signal.adapter.UxRestrictionsSource
import ai.carbuddy.trip.TripContext

/** Audio-side conditions read from the platform (call state, audio focus holders). */
interface SpeakConditions {
    fun inCall(): Boolean
    /** Navigation voice guidance is playing (audio focus / playback usage). */
    fun navGuidanceActive(): Boolean
    /** Gleo / voice assistant session active. */
    fun assistantActive(): Boolean

    object None : SpeakConditions {
        override fun inCall() = false
        override fun navGuidanceActive() = false
        override fun assistantActive() = false
    }
}

enum class SpeakPurpose { ARRIVAL_RECAP, SCREENING, EXIT_CHECK }

/**
 * Single gate for every utterance and every screen (SPEC 9장, CLAUDE.md 규칙 3).
 * - canSpeak: not in a call, no nav guidance, no assistant, turn signal off, user setting on.
 * - canShowUi: parked (P, or Speed=0 for 10 s when gear is unreadable) and no CarUxRestrictions
 *   requiring distraction optimization. Without the restrictions service, "restricted = not parked".
 */
class SafetyGate(
    private val ctx: () -> TripContext,
    private val conditions: SpeakConditions = SpeakConditions.None,
    private val settingOn: (SpeakPurpose) -> Boolean = { true },
    private val ux: UxRestrictionsSource = UxRestrictionsSource.None,
    /** 체험 모드: the trip context is the replay's, so only the car's own restriction decides. */
    private val demo: () -> Boolean = { false },
) {
    fun speakBlockers(now: Long, purpose: SpeakPurpose = SpeakPurpose.ARRIVAL_RECAP): List<String> = buildList {
        if (!settingOn(purpose)) add("setting off")
        if (conditions.inCall()) add("in call")
        if (conditions.navGuidanceActive()) add("nav guidance")
        if (conditions.assistantActive()) add("assistant")
        val c = ctx()
        if (c.turnSignalOn) add("turn signal")
        c.cameraAlertAt?.let { if (now - it < c.config.cameraAlertQuietSec * 1000L) add("camera alert") }
    }

    /** Driving-mode text cap from CarUxRestrictions.getMaxRestrictedStringLength, when known. */
    val maxDrivingChars: Int? get() = ux.maxStringLength?.takeIf { ux.available }

    fun navGuidanceActive(): Boolean = conditions.navGuidanceActive()

    fun canSpeak(now: Long, purpose: SpeakPurpose = SpeakPurpose.ARRIVAL_RECAP): Boolean = speakBlockers(now, purpose).isEmpty()

    fun uiMode(now: Long, doApproved: Boolean): UiMode = UiModeResolver.resolve(canShowUi(now), doApproved)

    fun canShowUi(now: Long): Boolean {
        if (demo()) return !(ux.available && ux.restricted)       // real motion also stops the demo (ReplayGuard)
        val c = ctx()
        // no motion signal at all yet (adapters not reporting): the system's own DO block still applies
        val parked = if (c.motionKnown) c.isParked(now) else true
        // CarUxRestrictions when reachable; parked-ness is kept as a second, conservative check
        return parked && !(ux.available && ux.restricted)
    }
}

/** Screen modes (SPEC 9장, CLAUDE.md 규칙 3). */
enum class UiMode {
    /** parked, no restriction: every screen */
    PARKED,
    /** restricted + DO approved: only the V10 driving mode */
    DRIVING_DO,
    /** restricted, DO not approved: the system shows its block screen; we draw nothing */
    BLOCKED,
}

/** Picks the screen mode from [SafetyGate.canShowUi] (CarUxRestrictions + parked) and the DO approval. */
object UiModeResolver {
    fun resolve(unrestricted: Boolean, doApproved: Boolean): UiMode = when {
        unrestricted -> UiMode.PARKED
        doApproved -> UiMode.DRIVING_DO
        else -> UiMode.BLOCKED
    }
}
