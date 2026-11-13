package ai.carbuddy.voice

import android.os.Handler
import android.os.Looper

/** Pleos voice-guidance guide: 150 to 200 ms of silence before every guidance, so the first syllable is not clipped. */
object TtsPreroll {
    const val MIN_MS = 150L
    const val DEFAULT_MS = 200L
}

/** Runs [task] after [delayMs]; returns a handle that cancels it. Injectable so tests need no Looper. */
fun interface TtsDelayer {
    fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit
}

private class MainLooperDelayer : TtsDelayer {
    private val handler = Handler(Looper.getMainLooper())
    override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return { handler.removeCallbacks(r) }
    }
}

/**
 * Wraps any [TtsPort] and starts each utterance after a fixed silence. The Pleos TTS SDK gives no PCM,
 * so the silence is a delay before speak (the guide's third choice; fine for a single short guidance).
 * Utterances keep their order because every one is delayed by the same amount. [stop] drops the ones
 * still waiting, and [speaking] is true while one is waiting.
 */
class PrerollTtsPort(
    private val inner: TtsPort,
    private val prerollMs: Long = TtsPreroll.DEFAULT_MS,
    private val delayer: TtsDelayer = MainLooperDelayer(),
) : TtsPort {
    private val pending = mutableListOf<() -> Unit>()

    init { require(prerollMs >= 0) }

    override fun speak(text: String, utteranceId: String) {
        if (prerollMs == 0L) { inner.speak(text, utteranceId); return }
        var cancel: () -> Unit = {}
        val task: () -> Unit = {
            synchronized(pending) { pending.remove(cancel) }
            inner.speak(text, utteranceId)
        }
        // register the cancel handle before the task can run
        synchronized(pending) {
            cancel = delayer.postDelayed(prerollMs, task)
            pending += cancel
        }
    }

    override fun stop() {
        val toCancel = synchronized(pending) { pending.toList().also { pending.clear() } }
        toCancel.forEach { it() }
        inner.stop()
    }

    override val speaking: Boolean get() = synchronized(pending) { pending.isNotEmpty() } || inner.speaking
}
