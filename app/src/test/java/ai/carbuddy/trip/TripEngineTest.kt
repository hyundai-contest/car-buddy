package ai.carbuddy.trip

import ai.carbuddy.TestSupport
import ai.carbuddy.TestSupport.kst
import ai.carbuddy.TestSupport.transitions
import ai.carbuddy.belt
import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.TripState.ARRIVING
import ai.carbuddy.core.model.TripState.DRIVING
import ai.carbuddy.core.model.TripState.ENDED
import ai.carbuddy.core.model.TripState.IDLE
import ai.carbuddy.core.model.TripState.PARKED_AT_DEST
import ai.carbuddy.core.model.TripState.PAUSED
import ai.carbuddy.data.InMemoryTripStore
import ai.carbuddy.gear
import ai.carbuddy.loc
import ai.carbuddy.signal.BootReason
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.PowerCycle
import ai.carbuddy.speed
import ai.carbuddy.ticks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    @Test fun scenarioA_fullStateSequence_acrossTheRestAreaReboot() {
        val run = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"))
        val tr = transitions(run.out)
        assertEquals(1, run.restarts)
        assertEquals(
            listOf(IDLE to DRIVING, DRIVING to PAUSED, PAUSED to DRIVING, DRIVING to ARRIVING, ARRIVING to PARKED_AT_DEST, PARKED_AT_DEST to ENDED),
            tr.map { it.from to it.to },
        )
        assertEquals(kst("2026-11-07T10:00:10"), tr[0].t)        // Gear D and > 5 km/h for 3 s (SPEC 5.5)
        assertEquals(kst("2026-11-07T11:35:30"), tr[1].t)        // belt off at the rest area
        assertEquals(kst("2026-11-07T11:57:00"), tr[2].t)        // resumed after the reboot, same trip
        assertEquals(kst("2026-11-07T13:59:00"), tr[3].t)
        assertEquals(kst("2026-11-07T14:02:05"), tr[4].t)
        assertEquals(kst("2026-11-07T14:04:00"), tr[5].t)
        assertEquals(EndReason.ARRIVED, tr[5].endReason)
        val trip = run.store.trips().single()
        assertEquals(22 * 60_000L, trip.pausedTotalMs)             // ignition-off time included
        assertEquals(231.0, trip.distanceKm, 1.0)
        assertEquals(kst("2026-11-07T13:10:00"), trip.baseEtaAt)   // route re-sent after boot: unchanged
        assertFalse(trip.discarded)
        assertEquals(4, trip.maxPassengers)
    }

    @Test fun scenarioA_killAndRestartAnywhere_sameResult() {
        val events = TestSupport.replay("scenario_a.jsonl")
        val ref = TestSupport.runWithPowerCycles(events)
        val refCard = ref.pipeline.card(ref.store.trips().single().id)!!
        for (killAt in listOf("2026-11-07T10:50:00", "2026-11-07T13:02:00", "2026-11-07T14:03:00")) {
            val cut = kst(killAt)
            val store = InMemoryTripStore()
            val first = TestSupport.runWithPowerCycles(events.filter { it.t < cut }, store)
            val second = TestSupport.pipeline(store)
            val boot = second.process(PowerBoot(cut + 1_000, BootReason.APP_START))
            val snap = TestSupport.adapterSnapshot(events, cut, cut + 1_000).flatMap { second.process(it) }
            val rest = TestSupport.runAll(second, events.filter { it.t >= cut + 1_000 && it !is PowerCycle })
            val tr = transitions(first.out + boot + snap + rest)
            assertEquals("kill at $killAt", ENDED, tr.last().to)
            val trip = store.trips().single()
            val card = second.card(trip.id)!!
            assertEquals("kill at $killAt", refCard.title, card.title)
            assertEquals("kill at $killAt", refCard.receipt.totalMin, card.receipt.totalMin)
            assertEquals("kill at $killAt", refCard.episodes.map { it.type }, card.episodes.map { it.type })
            assertEquals("kill at $killAt: no duplicate episodes", 7, store.episodes(trip.id).size)
        }
    }

    @Test fun commuteB_provisionalStopResumed_thenNextDayBootEndsIt() {
        val run = TestSupport.runWithPowerCycles(TestSupport.replay("commute_b.jsonl"))
        val tr = transitions(run.out)
        assertEquals(
            listOf(IDLE to DRIVING, DRIVING to PAUSED, PAUSED to DRIVING, DRIVING to PAUSED, PAUSED to ENDED),
            tr.map { it.from to it.to },
        )
        val trip = run.store.trips().single()
        assertEquals(EndReason.NO_DEST_TIMEOUT.name, trip.endReason)
        assertEquals(kst("2026-11-04T19:31:30"), trip.endedAt)     // ended where we parked, not at boot
        val stop = run.store.waypointStops(trip.id).single()
        assertEquals(10L, (stop.toT - stop.fromT) / 60_000)        // '들른 곳 10분' (T12)
        assertNotNull(run.out.filterIsInstance<PipelineEvent.Ended>().single().card)
    }

    @Test fun shortTrip_isDiscarded() {
        val p = TestSupport.pipeline()
        val events = listOf(gear(0.0, GearPos.D), speed(0.05, 20f)) + ticks(0.1, 3.0) +
            listOf(speed(3.0, 0f), gear(3.1, GearPos.P), belt(3.2, false)) + ticks(3.3, 40.0, 60)
        val out = TestSupport.runAll(p, events)
        val ended = out.filterIsInstance<PipelineEvent.Ended>().single()
        assertNull(ended.card)
        assertTrue(p.store.trips().single().discarded)
    }

    @Test fun gearUnreadable_zeroSpeedTenSecondsCountsAsPark() {
        val p = TestSupport.pipeline()
        val events = listOf(belt(0.0, true), speed(0.0, 30f)) + ticks(0.05, 9.99) +
            listOf(speed(10.0, 0f)) + listOf(belt(10.1, false)) + ticks(10.1, 10.3, 2)
        val tr = transitions(TestSupport.runAll(p, events))
        assertEquals(listOf(IDLE to DRIVING, DRIVING to PAUSED), tr.map { it.from to it.to })
        assertEquals(ai.carbuddy.m(10.0), p.store.trips().single().pausedAt)
    }

    @Test fun reverseOnly_isNotADeparture() {
        val p = TestSupport.pipeline()
        val events = listOf(gear(0.0, GearPos.R), speed(0.01, 8f)) + ticks(0.05, 1.0, 1)
        assertTrue(transitions(TestSupport.runAll(p, events)).isEmpty())
    }

    @Test fun noDestination_resumeWithin30Min_anywhere_isTheSameTrip() {
        val p = TestSupport.pipeline()
        val events = listOf(gear(0.0, GearPos.D), loc(0.0, 37.50, 127.00), speed(0.01, 40f)) + ticks(0.05, 10.0, 30) +
            listOf(loc(10.0, 37.55, 127.00), speed(10.0, 0f), gear(10.0, GearPos.P), belt(10.1, false)) + ticks(10.2, 19.9, 30) +
            listOf(loc(20.0, 37.60, 127.00), belt(20.0, true), gear(20.0, GearPos.D), speed(20.01, 30f)) + ticks(20.05, 20.2, 2)
        val tr = transitions(TestSupport.runAll(p, events))
        assertEquals(listOf(IDLE to DRIVING, DRIVING to PAUSED, PAUSED to DRIVING), tr.map { it.from to it.to })
        assertEquals(1, p.store.trips().size)
    }

    @Test fun parkedWithBeltOn_30Minutes_becomesAPauseFromItsStart() {
        val p = TestSupport.pipeline()
        val events = listOf(gear(0.0, GearPos.D), speed(0.01, 40f)) + ticks(0.05, 10.0, 30) +
            listOf(speed(10.0, 0f), gear(10.0, GearPos.P)) + ticks(10.0, 41.0, 30)
        val tr = transitions(TestSupport.runAll(p, events))
        // no destination: the pause is a provisional arrival from 10 min, already 30 min old → final
        assertEquals(listOf(IDLE to DRIVING, DRIVING to PAUSED, PAUSED to ENDED), tr.map { it.from to it.to })
        assertEquals(kst("1970-01-01T09:40:00"), tr[1].t)
        assertEquals(ai.carbuddy.m(10.0), p.store.trips().single().endedAt)
    }

    @Test fun beltOnShortStop_doesNotPause() {
        val p = TestSupport.pipeline()
        val events = listOf(gear(0.0, GearPos.D), speed(0.01, 40f)) + ticks(0.05, 10.0, 30) +
            listOf(speed(10.0, 0f), gear(10.0, GearPos.P)) + ticks(10.0, 15.0, 30)   // drive-through, belt stays on
        val tr = transitions(TestSupport.runAll(p, events))
        assertEquals(listOf(IDLE to DRIVING), tr.map { it.from to it.to })
    }
}
