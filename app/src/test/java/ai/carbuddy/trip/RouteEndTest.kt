package ai.carbuddy.trip

import ai.carbuddy.TestSupport
import ai.carbuddy.TestSupport.transitions
import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.TripState.DRIVING
import ai.carbuddy.core.model.TripState.ENDED
import ai.carbuddy.core.model.TripState.PARKED_AT_DEST
import ai.carbuddy.core.model.TripState.PAUSED
import ai.carbuddy.data.InMemoryTripStore
import ai.carbuddy.data.PlaceEntity
import ai.carbuddy.m
import ai.carbuddy.signal.BootReason
import ai.carbuddy.signal.DestinationArrived
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.DriverBelt
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.PowerCycle
import ai.carbuddy.signal.PowerCycleMode
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteCancelled
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * When the card is completed, for every route situation (IMPLEMENTATION_NOTES '여정 끝 판정').
 *  1. arrival can be judged (destination + P + belt off) → at once
 *  2. the user ends it → at once (UserTripControlTest)
 *  3. arrival judged but the power went off first → at the next start
 *  4. power off before arrival and no restart within the stop's limit → at the next start; restart within → same trip
 */
class RouteEndTest {

    private val home = 37.50 to 127.00
    private fun pt(northKm: Double, eastKm: Double) =
        (home.first + northKm / 111.32) to (home.second + eastKm / (111.32 * cos(Math.toRadians(home.first))))

    private fun drive(fromMin: Double, toMin: Double, a: Pair<Double, Double>, b: Pair<Double, Double>, kmh: Float = 60f, belt: Boolean? = true): List<SignalEvent> = buildList {
        add(Gear(m(fromMin), GearPos.D)); belt?.let { add(DriverBelt(m(fromMin), it)) }; add(Speed(m(fromMin), kmh))
        val n = ((toMin - fromMin) * 12).toInt()
        for (i in 0..n) {
            val f = i.toDouble() / n
            val t = m(fromMin) + i * 5_000L
            add(Location(t, a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, 5f, null))
            add(Tick(t + 1))
        }
    }

    /** P at [p]; belt off 20 s later unless [beltOff] is false (stays on) or null (never reported). */
    private fun park(fromMin: Double, toMin: Double, p: Pair<Double, Double>, beltOff: Boolean? = true): List<SignalEvent> = buildList {
        add(Speed(m(fromMin), 0f)); add(Gear(m(fromMin), GearPos.P))
        if (beltOff == true) add(DriverBelt(m(fromMin) + 20_000, false))
        var t = m(fromMin) + 30_000
        while (t < m(toMin)) { add(Location(t, p.first, p.second, 5f, null)); add(Tick(t + 1)); t += 30_000 }
    }

    private fun route(min: Double, d: Pair<Double, Double>, name: String, remainSec: Int) = listOf(
        RouteStarted(m(min), name, d.first, d.second, m(min) + remainSec * 1000L), RemainTime(m(min), remainSec),
    )

    private fun off(min: Double, offMin: Int) = listOf(PowerCycle(m(min), offMin * 60, PowerCycleMode.REBOOT), PowerBoot(m(min + offMin), BootReason.BOOT))

    private val dest = pt(60.0, 0.0)          // 60 km north
    private val gangneung = pt(30.0, 40.0)    // somewhere else entirely

    private fun single(run: TestSupport.Run) = run.store.trips().single()

    // ------------------------------------------------------------------ 1. arrival

    @Test fun arrival_destinationPBeltOff_completesAtOnce() {
        val run = TestSupport.runWithPowerCycles(route(0.0, dest, "목적지", 3600) + drive(0.0, 58.0, home, dest) + park(58.0, 59.0, dest))
        val trip = single(run)
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertEquals(m(58.0), trip.arrivedAt)
        assertTrue(trip.endedAt!! <= m(58.0) + 25_000)
        assertEquals("FINAL", run.store.cardForTrip(trip.id)!!.status)
    }

    @Test fun arrival_beltNeverReported_completesAfterTheShortWait() {
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 58.0, home, dest, belt = null) + park(58.0, 62.0, dest, beltOff = null)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertTrue(trip.endedAt!! in m(59.9)..m(60.6))      // arriveBeltUnknownSec = 120
    }

    @Test fun arrival_beltStaysOn_completesAfterIdleBeltOnMin() {
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 58.0, home, dest) + park(58.0, 90.0, dest, beltOff = false)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertEquals(m(58.0), trip.arrivedAt)
    }

    @Test fun destinationChangedWhileDriving_arrivalAtTheNewPlace() {
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 20.0, home, pt(20.0, 0.0)) +
            listOf(DestinationChanged(m(20.0), "강릉", gangneung.first, gangneung.second), RemainTime(m(20.0), 2700)) +
            drive(20.1, 60.0, pt(20.0, 0.0), gangneung).drop(3) + park(60.0, 61.0, gangneung)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = single(run)
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertEquals("강릉", trip.destName)
        assertEquals("목적지", trip.origDestName)
    }

    @Test fun cancelThenNewRoute_arrivalAtTheNewPlace() {
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 20.0, home, pt(20.0, 0.0)) + listOf(RouteCancelled(m(20.0))) +
            route(21.0, gangneung, "강릉", 2700) + drive(21.1, 60.0, pt(20.0, 0.0), gangneung).drop(3) + park(60.0, 61.0, gangneung)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertEquals("강릉", trip.destName)
    }

    // ------------------------------------------------------------------ the route says one thing, the car does another

    @Test fun drovePastTheRouteAwayFromTheDestination_endsElsewhereAfter30Min() {
        // route north, turn back south 10 km and stop: the navigation is still guiding
        val turn = pt(20.0, 0.0); val stop = pt(10.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 20.0, home, turn) + drive(20.0, 30.0, turn, stop).drop(3) + park(30.0, 62.0, stop)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = single(run)
        assertEquals(EndReason.ARRIVED_ELSEWHERE.name, trip.endReason)
        assertEquals(m(30.0), trip.endedAt)
        assertEquals("NO_DEST", run.pipeline.card(trip.id)!!.receipt.mode)   // no settlement against a place we never went to
    }

    @Test fun awayStop_resumedWithin30Min_isTheSameTrip() {
        val turn = pt(20.0, 0.0); val stop = pt(10.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 20.0, home, turn) + drive(20.0, 30.0, turn, stop).drop(3) +
            park(30.0, 50.0, stop) + drive(50.0, 110.0, stop, dest) + park(110.0, 111.0, dest)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = single(run)
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertTrue(transitions(run.out).any { it.from == PAUSED && it.to == DRIVING })
    }

    @Test fun guidanceCancelled_thenParkedFarAway_endsElsewhereAfter30Min() {
        val stop = pt(25.0, 5.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 20.0, home, pt(20.0, 0.0)) + listOf(RouteCancelled(m(20.0))) +
            drive(20.1, 26.0, pt(20.0, 0.0), stop).drop(3) + park(26.0, 57.0, stop)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED_ELSEWHERE.name, trip.endReason)
        assertEquals(m(26.0), trip.endedAt)
    }

    @Test fun navigationSaysArrived_butParked3kmAway_isNotAnArrival() {
        val stop = pt(60.0, 3.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 57.0, home, pt(59.9, 0.0)) + listOf(DestinationArrived(m(57.0))) +
            drive(57.1, 60.0, pt(59.9, 0.0), stop).drop(3) + park(60.0, 61.0, stop)
        val run = TestSupport.runWithPowerCycles(events)
        assertTrue(transitions(run.out).none { it.to == PARKED_AT_DEST })
        assertEquals(PAUSED.name, single(run).state)
    }

    @Test fun parkedNearTheDestination_guidanceStillOn_isAnArrivalAfter10Min() {
        val lot = pt(60.0, 0.7)       // 700 m away, guidance never ended
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 58.0, home, lot) + park(58.0, 70.0, lot)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = single(run)
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertEquals(m(58.0), trip.arrivedAt)
        assertTrue(run.pipeline.card(trip.id)!!.receipt.mode != "NO_DEST")   // arrival time known → settled
    }

    @Test fun nearDestinationStop_resumedWithin10Min_continues() {
        val shop = pt(59.2, 0.0)      // convenience store 800 m before the destination
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 57.0, home, shop) + park(57.0, 63.0, shop) +
            drive(63.0, 65.0, shop, dest) + park(65.0, 66.0, dest)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
        assertEquals(m(65.0), trip.arrivedAt)
    }

    @Test fun frequentPlaceOffTheRoute_endsAfter10Min() {
        val store = InMemoryTripStore()
        val homeStop = pt(15.0, 8.0)
        store.savePlace(PlaceEntity(lat = homeStop.first, lng = homeStop.second, radiusM = 200.0, visitCount = 5, lastVisitAt = 0, frequent = true))
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 17.0, home, homeStop) + park(17.0, 29.0, homeStop)
        TestSupport.runWithPowerCycles(events, store)
        val trip = store.trips().single()
        assertEquals(EndReason.ARRIVED_ELSEWHERE.name, trip.endReason)
        assertEquals(m(17.0), trip.endedAt)
    }

    // ------------------------------------------------------------------ rest stops on the way

    @Test fun onRouteRestStop_90Min_isStillTheSameTrip() {
        val b = pt(30.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 30.0, home, b) + park(30.0, 120.0, b) + drive(120.0, 150.0, b, dest) + park(150.0, 151.0, dest)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
    }

    @Test fun onRouteStop_over2Hours_endsAtTheStop() {
        val b = pt(30.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 30.0, home, b) + park(30.0, 152.0, b)
        val trip = TestSupport.runWithPowerCycles(events).store.trips().single()
        assertEquals(EndReason.MAX_GAP.name, trip.endReason)
        assertEquals(m(30.0), trip.endedAt)
    }

    // ------------------------------------------------------------------ 3·4. the power went off first

    @Test fun arrivedButPowerOffBeforeBelt_gearUnreadable_arrivalTimeKnown() {
        // no Gear signal: parked = speed 0 for 10 s; the power goes off 5 s after stopping at the destination
        val events = route(0.0, dest, "목적지", 3600) +
            drive(0.0, 58.0, home, dest).filterNot { it is Gear } +
            listOf(Speed(m(58.0), 0f), Location(m(58.0) + 3_000, dest.first, dest.second, 5f, null), Tick(m(58.0) + 5_000)) +
            off(58.1, 10)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = single(run)
        assertEquals(EndReason.ARRIVED_POWER_OFF.name, trip.endReason)
        assertNotNull(trip.arrivedAt)
        assertEquals("FINAL", run.store.cardForTrip(trip.id)!!.status)
    }

    @Test fun powerOffAtAnAwayStop_noRestart_completesAtTheNextStart() {
        val turn = pt(20.0, 0.0); val stop = pt(10.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 20.0, home, turn) + drive(20.0, 30.0, turn, stop).drop(3) +
            park(30.0, 31.0, stop) + off(31.0, 240)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = single(run)
        assertEquals(EndReason.ARRIVED_ELSEWHERE.name, trip.endReason)
        assertEquals(m(30.0), trip.endedAt)
        assertEquals("FINAL", run.store.cardForTrip(trip.id)!!.status)
    }

    @Test fun powerOffAtARestStop_restartWithinTheLimit_continues() {
        val b = pt(30.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 30.0, home, b) + park(30.0, 31.0, b) + off(31.0, 60) +
            drive(91.5, 121.5, b, dest) + park(121.5, 122.5, dest)
        val trip = single(TestSupport.runWithPowerCycles(events))
        assertEquals(EndReason.ARRIVED.name, trip.endReason)
    }

    @Test fun powerOffAtARestStop_restartAfterTheLimit_isANewTrip() {
        val b = pt(30.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 30.0, home, b) + park(30.0, 31.0, b) + off(31.0, 180) +
            drive(211.5, 241.5, b, dest)
        val trips = TestSupport.runWithPowerCycles(events).store.trips()
        assertEquals(2, trips.size)
        assertEquals(EndReason.MAX_GAP.name, trips.first { it.state == ENDED.name }.endReason)
        assertNull(trips.first { it.state != ENDED.name }.endReason)
    }
}
