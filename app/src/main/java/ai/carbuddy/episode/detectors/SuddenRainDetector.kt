package ai.carbuddy.episode.detectors

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.WindowPos
import ai.carbuddy.signal.Wiper
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable

/**
 * SUDDEN_RAIN (SPEC 6.2): wiper OFF → on, HIGH reached within rainRiseMin, HIGH for rainHighMin
 * in total. After the wiper turns OFF, any window opening ≥ rainWindowOpenPct within
 * rainWindowOpenMin adds windowAfter=true.
 */
class SuddenRainDetector : StatefulDetector<SuddenRainDetector.State>(State.serializer(), ::State) {

    @Serializable data class State(
        val mode: WiperMode = WiperMode.OFF,
        val onSince: Long? = null,          // OFF → on
        val reachedHigh: Boolean = false,
        val highSince: Long? = null,
        val highMs: Long = 0,
        val confirmed: Boolean = false,
        val offAt: Long? = null,
        val windowAfter: Boolean = false,
        val windows: Map<SeatPos, Int> = emptyMap(),
    )

    override val type = EpisodeType.SUDDEN_RAIN
    override val requires = setOf(Capability.WIPER)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val out = mutableListOf<EpisodeCandidate>()
        if (e is WindowPos) {
            val opened = e.pos.any { (seat, pct) -> pct >= cfg.rainWindowOpenPct && (s.windows[seat] ?: 0) < cfg.rainWindowOpenPct }
            s = s.copy(windows = s.windows + e.pos)
            val off = s.offAt
            if (opened && s.confirmed && off != null && !s.windowAfter && e.t - off <= cfg.rainWindowOpenMin * 60_000L) {
                s = s.copy(windowAfter = true)
                out += emit(e.t)
            }
        }
        if (e is Wiper && e.front != s.mode) {
            val prev = s.mode
            if (prev == WiperMode.HIGH) s = s.copy(highMs = s.highMs + (e.t - (s.highSince ?: e.t)), highSince = null)
            when {
                prev == WiperMode.OFF -> {
                    // a new shower only if the previous one is over
                    if (!s.confirmed || s.offAt != null) s = State(windows = s.windows, onSince = e.t)
                }
                e.front == WiperMode.OFF -> {
                    if (s.confirmed) { s = s.copy(offAt = e.t, mode = e.front); out += emit(e.t); return out }
                    s = State(windows = s.windows)
                }
            }
            if (e.front == WiperMode.HIGH) {
                val on = s.onSince
                val inRise = on != null && (s.reachedHigh || e.t - on <= cfg.rainRiseMin * 60_000L)
                if (inRise) s = s.copy(reachedHigh = true, highSince = e.t)
            }
            s = s.copy(mode = e.front)
        }
        // confirm as soon as HIGH has lasted long enough (also on Tick)
        if (!s.confirmed && s.reachedHigh && highTotal(e.t) >= cfg.rainHighMin * 60_000L) {
            s = s.copy(confirmed = true)
            out += emit(e.t)
        }
        return out
    }

    private fun highTotal(now: Long) = s.highMs + (s.highSince?.let { now - it } ?: 0)

    private fun emit(now: Long): EpisodeCandidate {
        val start = s.onSince ?: now
        val params = mapOf<String, Any>(
            "highMinutes" to Math.round(highTotal(s.offAt ?: now) / 60_000.0).toInt(),
            "windowAfter" to s.windowAfter,
        )
        return EpisodeCandidate(type, start, s.offAt ?: now, params, key = start.toString())
    }
}
