package ai.carbuddy.trip

import ai.carbuddy.TestSupport
import ai.carbuddy.signal.PowerCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 체험 모드: the scenario plays through the real pipeline but teaches nothing (buddy, places). */
class DemoModeTest {
    private fun play(demo: Boolean): TripPipeline {
        val p = TestSupport.pipeline().also { it.demoMode = demo }
        TestSupport.runAll(p, TestSupport.replay("commute_b.jsonl").filterNot { it is PowerCycle })
        return p
    }

    @Test fun demoTrip_makesACard_butTheBuddyAndPlacesStayUntouched() {
        val p = play(demo = true)
        val trip = p.store.trips().single { !it.discarded }
        assertNotNull("the tester still sees the card", p.card(trip.id))
        assertEquals(0, p.buddy.current().tripCount)
        assertTrue(p.store.places().isEmpty())
    }

    @Test fun afterTheDemo_theReplayCabinIsForgotten() {
        val ctx = TripContext(ai.carbuddy.episode.DetectorConfig())
        ctx.update(ai.carbuddy.signal.SeatOccupancy(0, ai.carbuddy.core.model.SeatPos.entries.associateWith { true }))
        assertTrue(ctx.passengerCount() > 1)
        ctx.resetMotion()
        assertEquals(1, ctx.passengerCount())
    }

    @Test fun sameLog_forReal_growsTheBuddyAndLearnsPlaces() {
        val p = play(demo = false)
        assertEquals(1, p.buddy.current().tripCount)
        assertTrue(p.store.places().isNotEmpty())
    }
}
