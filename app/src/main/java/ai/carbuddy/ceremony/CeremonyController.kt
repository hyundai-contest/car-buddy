package ai.carbuddy.ceremony

import ai.carbuddy.card.Card
import ai.carbuddy.card.CardEpisode
import ai.carbuddy.core.model.AmbientColor
import ai.carbuddy.core.model.EpisodeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface CeremonyStep {
    /** ⓪ 오늘의 길 (SPEC 10장, 10.1) */
    data object Route : CeremonyStep
    data class Slide(val episodeId: String) : CeremonyStep
    data object Receipt : CeremonyStep
    data object Awards : CeremonyStep
    /** 중간 상영 last card: "아직 n분 남았어. 나머지는 도착해서" (NEXT_DESIGN A). */
    data object HalftimeEnd : CeremonyStep
}

data class CeremonyState(
    val tripId: String,
    val card: Card,
    val steps: List<CeremonyStep>,
    val index: Int = 0,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    val revealedAwards: Int = 0,
    val undoEpisodeId: String? = null,
    /** Loop trajectory (local only, screening visual — never shared). */
    val tracks: Map<String, List<Pair<Double, Double>>> = emptyMap(),
    val buddyPick: Boolean = true,
    /** 30-second summary (N4 / archive) instead of the full screening (SPEC 10.2) */
    val summary: Boolean = false,
    /** 중간 상영 at a rest stop: no receipt or awards, no progress saved, not counted as viewed. */
    val halftime: Boolean = false,
    /** Minutes left to the destination for the halftime end card, null when unknown. */
    val remainMin: Int? = null,
) {
    val step: CeremonyStep get() = steps[index.coerceIn(0, steps.lastIndex)]
    val slideCount: Int get() = steps.count { it is CeremonyStep.Slide }
    fun episode(id: String): CardEpisode? = card.episodes.firstOrNull { it.id == id }
    val durationMs: Long get() = when (step) {
        CeremonyStep.Route -> ROUTE_MS
        is CeremonyStep.Slide -> SLIDE_MS
        CeremonyStep.Receipt -> RECEIPT_MS
        CeremonyStep.Awards -> AWARD_REVEAL_MS * maxOf(1, card.awards.size) + AWARDS_HOLD_MS
        CeremonyStep.HalftimeEnd -> HALFTIME_END_MS
    }

    companion object {
        const val ROUTE_MS = 4_000L        // SPEC 10장: 오늘의 길 4초
        const val SLIDE_MS = 5_000L        // SPEC 10장: 개당 5초
        const val RECEIPT_MS = 6_000L      // 6초
        const val AWARD_REVEAL_MS = 3_000L // 상 발표 각 3초
        const val AWARDS_HOLD_MS = 12_000L // then wait for [카드 완성하기]
        const val HALFTIME_END_MS = 6_000L // 중간 상영 end card, then it closes by itself
    }
}

/** What the controller needs from the rest of the app. */
interface CeremonyHost {
    fun loadCard(tripId: String): Card?
    fun hideEpisode(tripId: String, episodeId: String, hidden: Boolean): Card?
    fun saveProgress(tripId: String, step: Int?)
    fun loopTrack(tripId: String, episodeId: String): List<Pair<Double, Double>>
    fun speakComment(text: String)
    /** Reaching the receipt counts as viewed (SPEC 10.2). */
    fun viewed(tripId: String)
    /** UX_SPEC 9장 측정 이벤트 (screening_started / screening_completed). */
    fun metric(tripId: String, name: String, props: Map<String, Any?>) {}
    /** UX 효과음: chime at the start, fanfare when the awards open. */
    fun sound(sfx: ai.carbuddy.voice.Sfx) {}
}

/**
 * Arrival flow V3 → V4 → V5 (SPEC 10장, UX_SPEC 3장). Only runs while the UI may be shown;
 * [interrupt] (gear D) closes it and remembers where we were for "이어 보기".
 */
class CeremonyController(
    private val scope: CoroutineScope,
    private val host: CeremonyHost,
    private val ambient: AmbientController,
    private val ambientOn: () -> Boolean,
) {
    private val _state = MutableStateFlow<CeremonyState?>(null)
    val state: StateFlow<CeremonyState?> = _state.asStateFlow()
    private var ticker: Job? = null

    /** All public actions run on [scope] (the pipeline thread) because they read/write the store. */
    /** @param entry where it was opened from (UX 9장: N1 / N4 / app / archive / auto / end_now). */
    /** @param halftime 중간 상영 (NEXT_DESIGN A): route + the top [maxSlides] slides + the end card. */
    fun start(tripId: String, fromStep: Int = 0, summary: Boolean = false, entry: String = "app",
              halftime: Boolean = false, maxSlides: Int = 2, remainSec: Int? = null) {
        scope.launch {
            startNow(tripId, fromStep, summary, halftime, maxSlides, remainSec)
            if (_state.value?.tripId == tripId) host.sound(ai.carbuddy.voice.Sfx.SCREENING_CHIME)
            if (_state.value?.tripId == tripId) host.metric(tripId, "screening_started", mapOf("entry" to entry, "mode" to modeName(summary, halftime), "fromStep" to fromStep))
        }
    }

    private fun modeName(summary: Boolean, halftime: Boolean) = when { halftime -> "halftime"; summary -> "summary"; else -> "full" }

    /** [전체 보기] from the summary. */
    fun showFull() { scope.launch { val s = _state.value ?: return@launch; if (s.halftime) return@launch; stop(keepProgress = true); startNow(s.tripId, 0, false) } }

    private fun startNow(tripId: String, fromStep: Int, summary: Boolean = false, halftime: Boolean = false, maxSlides: Int = 2, remainSec: Int? = null) {
        val card = host.loadCard(tripId) ?: return
        val route = if (card.routeShape != null) listOf(CeremonyStep.Route) else emptyList()
        val slides = when {
            halftime -> card.visibleEpisodes.sortedByDescending { it.score }.take(maxSlides).map { it.id }.toSet()
                .let { top -> card.visibleEpisodes.filter { it.id in top }.map { CeremonyStep.Slide(it.id) } }   // keep time order
            summary -> listOfNotNull((card.visibleEpisodes.firstOrNull { it.star } ?: card.visibleEpisodes.maxByOrNull { it.score })?.let { CeremonyStep.Slide(it.id) })
            else -> card.visibleEpisodes.map { CeremonyStep.Slide(it.id) }
        }
        val tail = if (halftime) listOf(CeremonyStep.HalftimeEnd)
            else listOf(CeremonyStep.Receipt) + (if (card.awards.isNotEmpty()) listOf(CeremonyStep.Awards) else emptyList())
        val steps = route + slides + tail
        val tracks = card.visibleEpisodes.filter { it.type == "LOST_LOOP" }.associate { it.id to host.loopTrack(tripId, it.id) }
        _state.value = CeremonyState(tripId, card, steps, index = fromStep.coerceIn(0, steps.lastIndex), tracks = tracks, summary = summary,
            halftime = halftime, remainMin = remainSec?.let { (it + 59) / 60 })
        painted = null
        if (ambientOn()) runCatching { ambient.save() }.onFailure { Timber.tag("CB/ceremony").w(it, "ambient") }
        Timber.tag("CB/ceremony").i("start %s at step %d/%d", tripId, fromStep, steps.size)
        onStepEntered()
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val s = _state.value ?: break
                if (s.paused) continue
                val elapsed = s.elapsedMs + TICK_MS
                val reveal = if (s.step == CeremonyStep.Awards) minOf(s.card.awards.size, (elapsed / CeremonyState.AWARD_REVEAL_MS).toInt() + 1) else s.revealedAwards
                _state.value = s.copy(elapsedMs = elapsed, revealedAwards = reveal)
                if (elapsed >= s.durationMs && s.step != CeremonyStep.Awards) nextNow()   // HalftimeEnd is last → closes
            }
        }
    }

    fun next() { scope.launch { nextNow() } }

    private fun nextNow() {
        val s = _state.value ?: return
        if (s.index >= s.steps.lastIndex) { stop(keepProgress = false); return }
        _state.value = s.copy(index = s.index + 1, elapsedMs = 0, paused = false, revealedAwards = 0)
        if (!s.halftime) host.saveProgress(s.tripId, s.index + 1)     // the halftime never becomes '이어 보기'
        onStepEntered()
    }

    fun previous() { scope.launch { previousNow() } }

    private fun previousNow() {
        val s = _state.value ?: return
        if (s.index == 0) return
        _state.value = s.copy(index = s.index - 1, elapsedMs = 0, paused = false, revealedAwards = 0)
        onStepEntered()
    }

    fun togglePause() = _state.update { it?.copy(paused = !it.paused) }

    /** [숨기기]: no confirmation, toast with undo (UX_SPEC V3). */
    fun hideCurrent() { scope.launch { hideNow() } }

    private fun hideNow() {
        val s = _state.value ?: return
        val step = s.step as? CeremonyStep.Slide ?: return
        val card = host.hideEpisode(s.tripId, step.episodeId, true) ?: s.card
        _state.value = s.copy(card = card, undoEpisodeId = step.episodeId)
        nextNow()
        scope.launch { delay(UNDO_MS); _state.update { if (it?.undoEpisodeId == step.episodeId) it.copy(undoEpisodeId = null) else it } }
    }

    fun undoHide() {
        scope.launch {
            val s = _state.value ?: return@launch
            val id = s.undoEpisodeId ?: return@launch
            val card = host.hideEpisode(s.tripId, id, false) ?: s.card
            _state.value = s.copy(card = card, undoEpisodeId = null)
        }
    }

    fun refreshCard() {
        val s = _state.value ?: return
        host.loadCard(s.tripId)?.let { c -> _state.value = s.copy(card = c) }
    }

    /** Closed by the user: the card is complete anyway; remember the step for "이어 보기". */
    fun close() { scope.launch { stop(keepProgress = true) } }

    /** Restart from the first slide ([다시 보기]). */
    fun replay() {
        scope.launch {
            val st = _state.value ?: return@launch
            val slides = st.slideCount
            stop(keepProgress = true); startNow(st.tripId, 0, st.summary, st.halftime, slides, st.remainMin?.times(60))
        }
    }

    /** Jump to the slide of one episode (tapping a pin on 오늘의 길). */
    fun jumpTo(episodeId: String) {
        scope.launch {
            val s = _state.value ?: return@launch
            val i = s.steps.indexOfFirst { it is CeremonyStep.Slide && it.episodeId == episodeId }
            if (i >= 0) { _state.value = s.copy(index = i, elapsedMs = 0, paused = false); onStepEntered() }
        }
    }

    /** Gear left P / UI no longer allowed. */
    fun interrupt() { if (_state.value != null) { Timber.tag("CB/ceremony").i("interrupted (driving)"); stop(keepProgress = true) } }

    fun finish() { scope.launch { stop(keepProgress = false) } }

    private fun stop(keepProgress: Boolean) {
        val s = _state.value ?: return
        ticker?.cancel()
        val done = !keepProgress || s.index >= s.steps.lastIndex
        host.metric(s.tripId, if (done) "screening_completed" else "screening_stopped",
            mapOf("mode" to modeName(s.summary, s.halftime), "completedSteps" to s.index + 1, "steps" to s.steps.size))
        if (!s.halftime) host.saveProgress(s.tripId, if (keepProgress && s.index < s.steps.lastIndex) s.index else FINISHED)
        if (ambientOn()) runCatching { ambient.restore() }
        painted = null
        _state.value = null
    }

    /** Colour of the light last sent this screening; a scene with the same colour sends nothing. */
    private var painted: AmbientColor? = null

    /**
     * NEXT_DESIGN D: route, receipt and awards use the card's colour; an episode slide uses its own
     * type's colour (EpisodeType.ambient). The save happened once in [startNow]; restore is in [stop].
     */
    private fun paint(s: CeremonyState) {
        if (!ambientOn()) return
        val colour = (s.step as? CeremonyStep.Slide)?.let { s.episode(it.episodeId) }
            ?.let { e -> runCatching { EpisodeType.valueOf(e.type).ambient }.getOrNull() }
            ?: runCatching { AmbientColor.valueOf(s.card.ambient) }.getOrDefault(AmbientColor.WHITE)
        if (colour == painted) return
        painted = colour
        runCatching { ambient.apply(colour) }.onFailure { Timber.tag("CB/ceremony").w(it, "ambient") }
    }

    private fun onStepEntered() {
        val s = _state.value ?: return
        paint(s)
        if (s.step == CeremonyStep.Receipt) host.viewed(s.tripId)
        if (s.step == CeremonyStep.Awards) host.sound(ai.carbuddy.voice.Sfx.AWARD_FANFARE)
        val comment = (s.step as? CeremonyStep.Slide)?.let { s.episode(it.episodeId)?.buddyComment }
        if (!comment.isNullOrEmpty()) host.speakComment(comment)
    }

    companion object {
        const val TICK_MS = 100L
        const val UNDO_MS = 5_000L
        const val FINISHED = -1
    }
}
