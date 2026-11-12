package ai.carbuddy.trip

import ai.carbuddy.TestSupport
import ai.carbuddy.core.model.TripState
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.signal.DriverBelt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEXT_DESIGN A 중간 상영: a stop on the way (not a provisional end), at least halftimeMinPauseMin
 * long, the driver buckles up again → one Halftime per trip, even across a reboot.
 * Scenario A: belt off 11:35:30, ignition off 20 min (REBOOT), belt on 11:56:30, D 11:57.
 */
class HalftimeTest {
    private val a = TestSupport.replay("scenario_a.jsonl")

    private fun halftimes(run: TestSupport.Run) = run.out.filterIsInstance<PipelineEvent.Halftime>()

    private fun pausedAt(run: TestSupport.Run) =
        TestSupport.transitions(run.out).first { it.to == TripState.PAUSED }.t

    @Test fun restStop_afterReboot_oneHalftime_whenTheBeltGoesBackOn() {
        val run = TestSupport.runWithPowerCycles(a)
        val h = halftimes(run)
        assertEquals(1, h.size)
        assertEquals(TestSupport.kst("2026-11-07T11:56:30"), h.single().t)
        assertEquals(run.store.trips().single().id, h.single().tripId)
        assertTrue("at least two episodes so far", h.single().card.visibleEpisodes.size >= 2)
        // the arrival screening is untouched: still one Arrived, the card ends FINAL
        assertEquals(1, run.out.count { it is PipelineEvent.Arrived })
        assertEquals("FINAL", run.store.cards().single().status)
    }

    @Test fun pauseLength_boundary() {
        val run = TestSupport.runWithPowerCycles(a)
        val stoppedMin = ((halftimes(run).single().t - pausedAt(run)) / 60_000).toInt()
        assertEquals(1, halftimes(TestSupport.runWithPowerCycles(a, config = DetectorConfig(halftimeMinPauseMin = stoppedMin))).size)
        assertEquals(0, halftimes(TestSupport.runWithPowerCycles(a, config = DetectorConfig(halftimeMinPauseMin = stoppedMin + 1))).size)
    }

    @Test fun tooFewEpisodes_noHalftime() {
        assertEquals(0, halftimes(TestSupport.runWithPowerCycles(a, config = DetectorConfig(halftimeMinEpisodes = 99))).size)
    }

    @Test fun buckleTwiceAtTheSameStop_stillOnce() {
        val on = a.indexOfFirst { it is DriverBelt && it.buckled && it.t == TestSupport.kst("2026-11-07T11:56:30") }
        val t = a[on].t
        val events = a.take(on + 1) + listOf(DriverBelt(t + 5_000, false), DriverBelt(t + 10_000, true)) + a.drop(on + 1)
        assertEquals(1, halftimes(TestSupport.runWithPowerCycles(events)).size)
    }

    @Test fun noDestinationStop_isAProvisionalEnd_noHalftime() {
        assertEquals(0, halftimes(TestSupport.runWithPowerCycles(TestSupport.replay("commute_b.jsonl"))).size)
    }
}
