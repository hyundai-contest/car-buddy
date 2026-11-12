package ai.carbuddy.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager

/**
 * SafetyGate inputs from the audio system (SPEC 9장): call state and who is currently playing.
 * Navigation guidance and assistant (Gleo) sessions are detected by playback usage.
 */
class AndroidSpeakConditions(context: Context) : SpeakConditions {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun inCall(): Boolean =
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    override fun navGuidanceActive(): Boolean = playing(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)

    override fun assistantActive(): Boolean = playing(AudioAttributes.USAGE_ASSISTANT) || playing(AudioAttributes.USAGE_VOICE_COMMUNICATION)

    private fun playing(usage: Int): Boolean =
        runCatching { audio.activePlaybackConfigurations.any { it.audioAttributes.usage == usage } }.getOrDefault(false)
}
