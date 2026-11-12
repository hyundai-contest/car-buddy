package ai.carbuddy.ceremony

import ai.carbuddy.TestSupport
import ai.carbuddy.card.Card
import ai.carbuddy.core.model.AmbientColor
import ai.carbuddy.core.model.EpisodeType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 10장 / Step 9: the ceremony saves the car's light, applies the card colour, restores on close. */
@OptIn(ExperimentalCoroutinesApi::class)
class CeremonyAmbientTest {
    private class FakeAmbient : AmbientController {
        val calls = mutableListOf<String>()
        override fun save() { calls += "save" }
        override fun apply(color: AmbientColor) { calls += "apply:$color" }
        override fun restore() { calls += "restore" }
    }

    private val run = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"))
    private val trip = run.store.trips().single()
    private val card: Card = run.pipeline.card(trip.id)!!

    private fun host() = object : CeremonyHost {
        override fun loadCard(tripId: String) = card
        override fun hideEpisode(tripId: String, episodeId: String, hidden: Boolean) = card
        override fun saveProgress(tripId: String, step: Int?) = Unit
        override fun loopTrack(tripId: String, episodeId: String) = emptyList<Pair<Double, Double>>()
        override fun speakComment(text: String) = Unit
        override fun viewed(tripId: String) = Unit
    }

    @Test fun saveBeforeApply_thenRestoreWhenInterrupted() {
        val scope = TestScope()
        val ambient = FakeAmbient()
        val ceremony = CeremonyController(scope, host(), ambient) { true }
        ceremony.start(trip.id)
        scope.runCurrent()
        assertEquals(listOf("save", "apply:${card.ambient}"), ambient.calls)
        ceremony.interrupt()                       // gear D during the screening
        scope.runCurrent()
        assertEquals("restore", ambient.calls.last())
    }

    /** NEXT_DESIGN D: each scene paints its own colour; same colour twice in a row is not re-sent. */
    @Test fun eachSlidePaintsItsEpisodeColour_saveAndRestoreOnce() {
        val scope = TestScope()
        val ambient = FakeAmbient()
        val ceremony = CeremonyController(scope, host(), ambient) { true }
        ceremony.start(trip.id)
        scope.runCurrent()
        val steps = ceremony.state.value!!.steps
        repeat(steps.size - 1) { ceremony.next(); scope.runCurrent() }
        ceremony.finish()
        scope.runCurrent()

        val cardColour = AmbientColor.valueOf(card.ambient)
        val expected = buildList {
            steps.forEach { step ->
                add(when (step) {
                    is CeremonyStep.Slide -> AmbientColor.valueOf(EpisodeType.valueOf(card.episodes.first { it.id == step.episodeId }.type).ambient.name)
                    else -> cardColour
                })
            }
        }.fold(mutableListOf<AmbientColor>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
        assertEquals(listOf("save") + expected.map { "apply:$it" } + "restore", ambient.calls)
        // scenario A has the rain (blue) and the detour (cyan) slides
        assertTrue("apply:BLUE" in ambient.calls)
        assertTrue("apply:CYAN" in ambient.calls)
        assertEquals(1, ambient.calls.count { it == "save" })
        assertEquals(1, ambient.calls.count { it == "restore" })
    }

    @Test fun settingOff_neverTouchesTheLight() {
        val scope = TestScope()
        val ambient = FakeAmbient()
        val ceremony = CeremonyController(scope, host(), ambient) { false }
        ceremony.start(trip.id)
        scope.runCurrent()
        ceremony.interrupt()
        scope.runCurrent()
        assertTrue(ambient.calls.isEmpty())
    }
}
