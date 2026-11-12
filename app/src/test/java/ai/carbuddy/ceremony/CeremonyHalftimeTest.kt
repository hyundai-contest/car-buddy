package ai.carbuddy.ceremony

import ai.carbuddy.TestSupport
import ai.carbuddy.card.Card
import ai.carbuddy.core.model.AmbientColor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NEXT_DESIGN A: the halftime screening is route + top slides + an end card, and leaves the arrival screening alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class CeremonyHalftimeTest {
    private val run = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"))
    private val trip = run.store.trips().single()
    private val card: Card = run.pipeline.card(trip.id)!!

    private val progress = mutableListOf<Int?>()
    private val viewed = mutableListOf<String>()

    private fun host() = object : CeremonyHost {
        override fun loadCard(tripId: String) = card
        override fun hideEpisode(tripId: String, episodeId: String, hidden: Boolean) = card
        override fun saveProgress(tripId: String, step: Int?) { progress += step }
        override fun loopTrack(tripId: String, episodeId: String) = emptyList<Pair<Double, Double>>()
        override fun speakComment(text: String) = Unit
        override fun viewed(tripId: String) { viewed += tripId }
    }

    private val noLight = object : AmbientController {
        override fun save() = Unit
        override fun apply(color: AmbientColor) = Unit
        override fun restore() = Unit
    }

    @Test fun routeTopTwoSlidesAndTheEnd_inTimeOrder() {
        val scope = TestScope()
        val ceremony = CeremonyController(scope, host(), noLight) { false }
        ceremony.start(trip.id, halftime = true, maxSlides = 2, remainSec = 25 * 60)
        scope.runCurrent()
        val s = ceremony.state.value!!
        assertTrue(s.halftime)
        assertEquals(25, s.remainMin)
        assertEquals(CeremonyStep.Route, s.steps.first())
        assertEquals(CeremonyStep.HalftimeEnd, s.steps.last())
        val slides = s.steps.filterIsInstance<CeremonyStep.Slide>().map { id -> card.visibleEpisodes.first { it.id == id.episodeId } }
        assertEquals(2, slides.size)
        val top2 = card.visibleEpisodes.sortedByDescending { it.score }.take(2).map { it.id }.toSet()
        assertEquals(top2, slides.map { it.id }.toSet())
        assertEquals(card.visibleEpisodes.filter { it.id in top2 }.map { it.id }, slides.map { it.id })   // card (time) order
    }

    @Test fun neverTouchesTheArrivalProgress_orTheViewedMark() {
        val scope = TestScope()
        val ceremony = CeremonyController(scope, host(), noLight) { false }
        ceremony.start(trip.id, halftime = true, maxSlides = 2, remainSec = null)
        scope.runCurrent()
        repeat(ceremony.state.value!!.steps.size) { ceremony.next(); scope.runCurrent() }
        assertNull(ceremony.state.value)                    // ran past the end card → closed
        assertTrue("no progress saved: $progress", progress.isEmpty())
        assertTrue(viewed.isEmpty())
    }
}
