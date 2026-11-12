package ai.carbuddy.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/** UX_SPEC 효과음: screening chime, award fanfare, the 1 s "지금 기억해" confirmation. */
enum class Sfx { SCREENING_CHIME, AWARD_FANFARE, MARK }

interface SoundPort {
    fun play(sfx: Sfx)

    object None : SoundPort { override fun play(sfx: Sfx) = Unit }
}

/**
 * Built-in tones (no audio assets). Short and quiet; callers decide when (SafetyGate.canSpeak:
 * not in a call, not over navigation guidance or the assistant, turn signal off).
 */
class ToneSoundPort(private val volume: Int = 60) : SoundPort {
    private val main = Handler(Looper.getMainLooper())
    private val tone: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.onFailure { Timber.tag("CB/sfx").w(it, "no tone generator") }.getOrNull()
    }

    override fun play(sfx: Sfx) {
        val t = tone ?: return
        val steps: List<Pair<Int, Int>> = when (sfx) {                     // (tone, ms)
            Sfx.SCREENING_CHIME -> listOf(ToneGenerator.TONE_PROP_BEEP to 120, ToneGenerator.TONE_PROP_BEEP2 to 220)
            Sfx.AWARD_FANFARE -> listOf(ToneGenerator.TONE_PROP_BEEP to 100, ToneGenerator.TONE_PROP_BEEP to 100, ToneGenerator.TONE_PROP_ACK to 400)
            Sfx.MARK -> listOf(ToneGenerator.TONE_PROP_ACK to 1_000)
        }
        var delay = 0L
        steps.forEach { (id, ms) ->
            main.postDelayed({ runCatching { t.startTone(id, ms) } }, delay)
            delay += ms + 40
        }
    }
}
