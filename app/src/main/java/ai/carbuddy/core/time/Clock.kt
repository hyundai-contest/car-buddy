package ai.carbuddy.core.time

import javax.inject.Inject
import javax.inject.Singleton

fun interface Clock { fun now(): Long }

/**
 * App-wide clock. While a replay runs, every adapter stamps its events with the replay's
 * virtual time so console input and log playback can be mixed (SPEC 4.3).
 */
@Singleton
class AppClock @Inject constructor() : Clock {
    @Volatile var override: Clock? = null
    override fun now(): Long = override?.now() ?: System.currentTimeMillis()
}

/** Virtual clock driven by [ai.carbuddy.signal.adapter.replay.ReplayAdapter]. */
class ReplayClock(private val realNanos: () -> Long = System::nanoTime) : Clock {
    @Volatile private var virtualAnchor = 0L
    @Volatile private var realAnchor = 0L
    @Volatile var speed = 1f
        set(value) { anchor(now()); field = value }

    fun anchor(virtualNow: Long) {
        virtualAnchor = virtualNow
        realAnchor = realNanos()
    }

    override fun now(): Long = virtualAnchor + ((realNanos() - realAnchor) / 1_000_000L * speed).toLong()
}
