package ai.carbuddy.trip

import ai.carbuddy.TestSupport
import ai.carbuddy.belt
import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.gear
import ai.carbuddy.loc
import ai.carbuddy.m
import ai.carbuddy.speed
import ai.carbuddy.ticks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V10 parked mode: [여기서 끝내기] (card from what we have) and [기록 멈추기] (no card, stop until next ignition). */
class UserTripControlTest {
    /** 20 minutes at 40 km/h, then parked with the belt still on (a rest stop / errand). */
    private val driveThenPark = listOf(gear(0.0, GearPos.D), loc(0.0, 37.50, 127.00), speed(0.01, 40f), belt(0.0, true)) +
        ticks(0.05, 20.0, 30) + listOf(speed(20.0, 0f), gear(20.1, GearPos.P)) + ticks(20.2, 22.0, 30)

    @Test fun endNow_finalCardFromWhatWeHave() {
        val p = TestSupport.pipeline()
        TestSupport.runAll(p, driveThenPark)
        val out = p.endActiveTrip(m(22.0))
        val ended = out.filterIsInstance<PipelineEvent.Ended>().single()
        assertNotNull(ended.card)
        val trip = p.store.trips().single()
        assertEquals(EndReason.USER.name, trip.endReason)
        assertEquals("FINAL", p.store.cardForTrip(trip.id)!!.status)
        assertNull(p.trips.trip)
    }

    @Test fun endNow_shortTripStillMakesACard() {
        val p = TestSupport.pipeline()
        val short = listOf(gear(0.0, GearPos.D), speed(0.05, 20f)) + ticks(0.1, 3.0) + listOf(speed(3.0, 0f), gear(3.1, GearPos.P))
        TestSupport.runAll(p, short)
        val ended = p.endActiveTrip(m(3.5)).filterIsInstance<PipelineEvent.Ended>().single()
        assertNotNull("the user asked for it: not discarded as too short", ended.card)
        assertFalse(p.store.trips().single().discarded)
    }

    @Test fun endNow_withoutATrip_doesNothing() {
        assertTrue(TestSupport.pipeline().endActiveTrip(m(1.0)).isEmpty())
    }

    @Test fun stopRecording_noCard_andNoNewTripUntilNextIgnition() {
        val p = TestSupport.pipeline()
        TestSupport.runAll(p, driveThenPark)
        p.stopRecording()
        assertTrue(p.store.trips().isEmpty())
        assertTrue(p.store.cards().isEmpty())
        // drive on: still not recording (record-off until the next boot)
        TestSupport.runAll(p, listOf(gear(23.0, GearPos.D), speed(23.05, 50f)) + ticks(23.1, 40.0, 30))
        assertTrue(p.store.trips().isEmpty())
        assertTrue(p.settings().recordOff)
    }
}
