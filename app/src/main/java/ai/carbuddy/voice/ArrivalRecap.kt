package ai.carbuddy.voice

import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.story.CopyBook
import ai.carbuddy.story.EpisodeTexts
import ai.carbuddy.story.Scorer
import ai.carbuddy.story.StoryEpisode
import timber.log.Timber

/** Navigation turn-by-turn: is the next turn far enough to speak? TODO(SDK): onTBTInfo fields. */
fun interface TurnInfoPort { fun nextTurnFar(): Boolean }

/**
 * RecapScheduler + ArrivalRecap (SPEC 9장): one retrospective for destination trips, between
 * ARRIVING and recapDeadlineBeforeArrivalSec before arrival. Tries every recapRetrySec while
 * [SafetyGate.canSpeak] and the next turn allow; past the deadline it is skipped. ≤ ~35 chars,
 * never a question.
 */
class ArrivalRecap(
    private val gate: SafetyGate,
    private val tts: TtsPort,
    private val copy: CopyBook,
    private val config: DetectorConfig,
    private val turns: TurnInfoPort = TurnInfoPort { true },
) {
    private val texts = EpisodeTexts(copy)
    private var pending: String? = null
    private var nextAttempt = 0L
    private var deadline = Long.MAX_VALUE
    var spokenText: String? = null
        private set

    val isPending get() = pending != null

    fun reset() { pending = null; spokenText = null; deadline = Long.MAX_VALUE }

    /** Called on entering ARRIVING. [remainSec] = navigation remaining time at that moment. */
    fun schedule(t: Long, episodes: List<EpisodeEntity>, history: Map<EpisodeType, Int>, remainSec: Int? = null) {
        if (pending != null || spokenText != null) return
        pending = compose(episodes, history)
        nextAttempt = t
        deadline = remainSec?.let { t + (it - config.recapDeadlineBeforeArrivalSec) * 1000L } ?: Long.MAX_VALUE
    }

    /** Drives attempts from the event stream (Ticks included). @return text if spoken now. */
    fun onEvent(e: SignalEvent): String? {
        val text = pending ?: return null
        if (e.t > deadline) {
            Timber.tag("CB/voice").i("recap skipped: deadline passed")
            pending = null
            return null
        }
        if (e.t < nextAttempt) return null
        val blockers = gate.speakBlockers(e.t, SpeakPurpose.ARRIVAL_RECAP) + (if (turns.nextTurnFar()) emptyList() else listOf("turn soon"))
        if (blockers.isEmpty()) {
            tts.speak(text, "recap")
            spokenText = text
            pending = null
            Timber.tag("CB/voice").i("recap spoken: %s", text)
            return text
        }
        nextAttempt = e.t + config.recapRetrySec * 1000L
        Timber.tag("CB/voice").d("recap deferred (%s)", blockers)
        return null
    }

    fun compose(episodes: List<EpisodeEntity>, history: Map<EpisodeType, Int>): String {
        // recap favours fresh memories: score first, then the more recent one; told in time order
        val ranked = Scorer.merge(episodes.filterNot { it.hidden }, history)
            .sortedWith(compareByDescending<StoryEpisode> { it.score }.thenByDescending { it.startT })
        val two = ranked.take(2).sortedBy { it.startT }
        fun sentence(list: List<StoryEpisode>) = when (list.size) {
            0 -> copy.get("recap_zero")
            1 -> copy.get("recap_one", "b" to copy.get("recap_b_${list[0].type.name}", texts.vars(list[0])))
            else -> copy.get(
                "recap_two",
                "a" to copy.get("recap_a_${list[0].type.name}", texts.vars(list[0])),
                "b" to copy.get("recap_b_${list[1].type.name}", texts.vars(list[1])),
            )
        }
        val main = sentence(two).let { if (it.length <= config.recapMaxChars || two.size < 2) it else sentence(ranked.take(1)) }
        val withEnd = "$main ${copy.get("recap_end")}"
        return if (withEnd.length <= config.recapMaxChars) withEnd else main
    }
}
