package ai.carbuddy.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/**
 * Text-to-speech port. Production: [PleosTtsPort] (Pleos TextToSpeech SDK); Android TextToSpeech
 * below is its fallback when the Pleos speech service is not available.
 */
interface TtsPort {
    fun speak(text: String, utteranceId: String)
    fun stop()
    /** An utterance is playing right now (used to cut it off when nav guidance starts). */
    val speaking: Boolean get() = false
}

/** Records utterances (tests, devices without a TTS engine — the Pleos emulator has none). */
class RecordingTtsPort : TtsPort {
    val spoken = mutableListOf<String>()
    override fun speak(text: String, utteranceId: String) { spoken += text }
    override fun stop() = Unit
}

class AndroidTtsPort(context: Context) : TtsPort, TextToSpeech.OnInitListener {
    // onInit may run synchronously inside the constructor (e.g. no engine installed), before `tts` is set
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var status: Int? = null
    @Volatile private var ready = false
    private val queued = mutableListOf<Pair<String, String>>()

    init {
        tts = TextToSpeech(context.applicationContext, this)
        status?.let { finishInit(it) }
    }

    override fun onInit(status: Int) {
        this.status = status
        if (tts != null) finishInit(status)
    }

    private fun finishInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS && runCatching { engine.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        if (ready) {
            engine.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            synchronized(queued) { queued.forEach { (t, id) -> engine.speak(t, TextToSpeech.QUEUE_ADD, null, id) }; queued.clear() }
        }
        Timber.tag("CB/voice").i("Android TTS ready=%s (status=%d)", ready, status)
    }

    override fun speak(text: String, utteranceId: String) {
        Timber.tag("CB/voice").i("speak[%s]: %s", utteranceId, text)
        val engine = tts
        if (engine == null || !ready) { if (status == null) synchronized(queued) { queued += text to utteranceId }; return }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    override fun stop() { tts?.stop() }

    override val speaking: Boolean get() = tts?.isSpeaking == true
}
