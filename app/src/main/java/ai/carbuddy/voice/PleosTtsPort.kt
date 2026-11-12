package ai.carbuddy.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import timber.log.Timber
import java.util.Locale

/**
 * Pleos TextToSpeech SDK 3.3.1 (official guide: TextToSpeech(context, Mode) → initialize →
 * addEventListener → setLocale → speak(text, isFinal) / stop(); permission TTS_SERVICE).
 * ON_DEVICE: no network and no app registration (HYBRID/SERVER need Playground credentials).
 * Lines asked for before initialisation finishes are spoken once it does; if the SDK cannot start,
 * [fallback] (Android TextToSpeech) takes over.
 */
class PleosTtsPort(context: Context, private val fallback: () -> TtsPort) : TtsPort {
    private val ctx = context.applicationContext
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed: TtsPort? = null
    private val queued = mutableListOf<String>()
    @Volatile private var playing = false
    override val speaking: Boolean get() = failed?.speaking ?: playing

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() { playing = true; Timber.tag(TAG).d("speaking") }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { playing = false; Timber.tag(TAG).d("done") }
        override fun onStop() { playing = false }
        override fun onError(errorType: ErrorType) { playing = false; Timber.tag(TAG).w("tts error %s", errorType) }
    }

    init {
        try {
            val t = TextToSpeech(ctx, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    t.addEventListener(events)
                    t.setLocale(Locale.KOREA)
                    t.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    ready = true
                    Timber.tag(TAG).i("Pleos TTS ready")
                    synchronized(queued) { queued.forEach { t.speak(it, true) }; queued.clear() }
                }
                override fun onFailure(errorType: ErrorType) = useFallback("init $errorType")
            })
        } catch (e: Throwable) { useFallback("unavailable: ${e.message}") }
    }

    private fun useFallback(why: String) {
        Timber.tag(TAG).w("Pleos TTS %s → Android TTS", why)
        val f = fallback()
        failed = f
        synchronized(queued) { queued.forEach { f.speak(it, "queued") }; queued.clear() }
    }

    override fun speak(text: String, utteranceId: String) {
        Timber.tag("CB/voice").i("speak[%s]: %s", utteranceId, text)
        failed?.let { it.speak(text, utteranceId); return }
        val t = tts
        if (t == null || !ready) { synchronized(queued) { queued += text }; return }
        t.speak(text, true)
    }

    override fun stop() {
        failed?.stop() ?: runCatching { tts?.stop() }
    }

    private companion object { const val TAG = "CB/tts" }
}
