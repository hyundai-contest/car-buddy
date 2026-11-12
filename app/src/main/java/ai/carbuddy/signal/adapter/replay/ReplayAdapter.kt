package ai.carbuddy.signal.adapter.replay

import ai.carbuddy.core.time.ReplayClock
import ai.carbuddy.signal.EventLine
import ai.carbuddy.signal.MetaLine
import ai.carbuddy.signal.ReplayLine
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.SignalSink
import ai.carbuddy.signal.Tick
import ai.carbuddy.signal.PowerCycle
import ai.carbuddy.signal.PowerCycleMode
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.BootReason
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Plays a JSONL replay log through the same path as live adapters (SPEC 4.3).
 * Speed 1x/10x/60x (dev builds also allow faster); can run alongside real adapters.
 */
class ReplayAdapter(
    private val sink: SignalSink,
    val clock: ReplayClock = ReplayClock(),
) {
    @Volatile var speed: Float = 1f
        set(value) { field = value; clock.speed = value }

    /** Virtual time of the last emitted event, for the dev panel progress bar. */
    @Volatile var position: Long = 0L
        private set

    /**
     * Emits every event at its virtual time. Suspends until the log ends; cancel to stop.
     * @param onEvent called after each emit (dev panel progress).
     */
    suspend fun play(
        lines: List<ReplayLine>,
        fallbackStart: Long,
        useLogStartTime: Boolean = true,
        tickEveryMs: Long = DEFAULT_TICK_MS,
        onEvent: (SignalEvent) -> Unit = {},
    ) {
        val events = timeline(lines, fallbackStart, useLogStartTime, tickEveryMs)
        if (events.isEmpty()) return
        var virtualNow = events.first().t
        clock.anchor(virtualNow)
        Timber.tag("CB/replay").i("play %d events at %.0fx", events.size, speed)
        for (e in events) {
            val waitMs = ((e.t - virtualNow) / speed).toLong()
            if (waitMs > 0) delay(waitMs)
            virtualNow = e.t
            clock.anchor(virtualNow)
            position = virtualNow
            sink.emit(e)
            onEvent(e)
        }
    }

    companion object {
        const val DEFAULT_TICK_MS = 5_000L

        /** Start time of a log: its Meta line if present and requested, else [fallbackStart]. */
        fun startOf(lines: List<ReplayLine>, fallbackStart: Long, useLogStartTime: Boolean = true): Long =
            lines.filterIsInstance<MetaLine>().firstOrNull()?.startAtMs?.takeIf { useLogStartTime } ?: fallbackStart

        /**
         * Absolute, time-ordered events with [Tick]s inserted every [tickEveryMs] of virtual time.
         * Lines are stably sorted by dt so hand-written logs may be slightly out of order.
         */
        fun timeline(
            lines: List<ReplayLine>,
            fallbackStart: Long,
            useLogStartTime: Boolean = true,
            tickEveryMs: Long = DEFAULT_TICK_MS,
        ): List<SignalEvent> {
            val base = startOf(lines, fallbackStart, useLogStartTime)
            val raw = lines.filterIsInstance<EventLine>().sortedBy { it.dt }.map { it.build(base + it.dt) }
            // Ignition-off windows: no events, no ticks; REBOOT / LATE_START end with a PowerBoot(BOOT).
            val cycles = raw.filterIsInstance<PowerCycle>()
            fun offWindow(t: Long) = cycles.firstOrNull { c ->
                val end = c.t + c.offSec * 1000L + (if (c.mode == PowerCycleMode.LATE_START) c.skipSec * 1000L else 0)
                t > c.t && t < end
            }
            val events = ArrayList<SignalEvent>(raw.size + cycles.size)
            for (e in raw) {
                if (e !is PowerCycle && offWindow(e.t) != null) continue
                events += e
                if (e is PowerCycle && e.mode != PowerCycleMode.SUSPEND) {
                    val bootAt = e.t + e.offSec * 1000L + (if (e.mode == PowerCycleMode.LATE_START) e.skipSec * 1000L else 0)
                    events += PowerBoot(bootAt, BootReason.BOOT)
                }
            }
            events.sortBy { it.t }
            if (tickEveryMs <= 0 || events.isEmpty()) return events
            val out = ArrayList<SignalEvent>(events.size + 4096)
            var nextTick = base + tickEveryMs
            for (e in events) {
                while (nextTick < e.t) {
                    if (offWindow(nextTick) == null) out += Tick(nextTick)
                    nextTick += tickEveryMs
                }
                out += e
            }
            return out
        }
    }
}
