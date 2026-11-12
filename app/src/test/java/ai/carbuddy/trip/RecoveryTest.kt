package ai.carbuddy.trip

import ai.carbuddy.TestSupport
import ai.carbuddy.TestSupport.transitions
import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.TripState.DRIVING
import ai.carbuddy.core.model.TripState.ENDED
import ai.carbuddy.core.model.TripState.IDLE
import ai.carbuddy.core.model.TripState.PARKED_AT_DEST
import ai.carbuddy.core.model.TripState.PAUSED
import ai.carbuddy.data.InMemoryTripStore
import ai.carbuddy.data.PlaceEntity
import ai.carbuddy.data.SettingsEntity
import ai.carbuddy.m
import ai.carbuddy.signal.BootReason
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
import ai.carbuddy.signal.SignalJson
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.Tick
import ai.carbuddy.signal.adapter.replay.ReplayAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos

/** SPEC 5.4 T1–T17 (T14 is a server test). Synthetic drives on a 5 s grid. */
class RecoveryTest {

    private val home = 37.50 to 127.00
    private fun pt(northKm: Double, eastKm: Double) =
        (home.first + northKm / 111.32) to (home.second + eastKm / (111.32 * cos(Math.toRadians(home.first))))

    /** Drive straight from [a] to [b] at [kmh]: Speed once, Location + Tick every 5 s. */
    private fun drive(fromMin: Double, toMin: Double, a: Pair<Double, Double>, b: Pair<Double, Double>, kmh: Float = 60f): List<SignalEvent> = buildList {
        add(Gear(m(fromMin), GearPos.D)); add(DriverBelt(m(fromMin), true)); add(Speed(m(fromMin), kmh))
        val n = ((toMin - fromMin) * 12).toInt()
        for (i in 0..n) {
            val f = i.toDouble() / n
            val t = m(fromMin) + i * 5_000L
            add(Location(t, a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, 5f, null))
            add(Tick(t + 1))
        }
    }

    /** Stop at [p]: speed 0, P, belt off (unless [beltOn]); a fix every 30 s and ticks until [toMin]. */
    private fun park(fromMin: Double, toMin: Double, p: Pair<Double, Double>, beltOn: Boolean = false): List<SignalEvent> = buildList {
        add(Speed(m(fromMin), 0f)); add(Gear(m(fromMin), GearPos.P))
        if (!beltOn) add(DriverBelt(m(fromMin) + 20_000, false))
        var t = m(fromMin) + 30_000
        while (t < m(toMin)) { add(Location(t, p.first, p.second, 5f, null)); add(Tick(t + 1)); t += 30_000 }
    }

    private fun route(min: Double, dest: Pair<Double, Double>, name: String, remainSec: Int) = listOf(
        RouteStarted(m(min), name, dest.first, dest.second, m(min) + remainSec * 1000L), RemainTime(m(min), remainSec),
    )

    private fun boot(min: Double, reason: BootReason = BootReason.BOOT) = PowerBoot(m(min), reason)
    private fun cycle(min: Double, offSec: Int, mode: PowerCycleMode = PowerCycleMode.REBOOT) = PowerCycle(m(min), offSec, mode)

    private val dest = pt(60.0, 0.0)

    // ------------------------------------------------------------------ T1–T3

    @Test fun t1_restStopReboot_sameTrip_rest22_late52() {
        val run = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"))
        val trip = run.store.trips().single()
        val rest = run.store.episodes(trip.id).single { it.type == "REST_STOP" }
        assertTrue(rest.paramsJson.contains("\"minutes\":22"))
        val card = run.pipeline.card(trip.id)!!
        assertEquals("LATE", card.receipt.mode)
        assertEquals(52, card.receipt.diffMin)
    }

    @Test fun t2_restStopSuspend_sameResultAsReboot() {
        val text = File(generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/replay").isDirectory }, "docs/replay/scenario_a.jsonl")
            .readText().replace("\"mode\":\"REBOOT\"", "\"mode\":\"SUSPEND\"")
        val events = ReplayAdapter.timeline(SignalJson.parseLog(text), fallbackStart = 0)
        val run = TestSupport.runWithPowerCycles(events)
        assertEquals(0, run.restarts)
        assertTrue(run.out.any { it is PipelineEvent.Booted })      // the gap came back as SignalResumed
        val trip = run.store.trips().single()
        assertEquals(22 * 60_000L, trip.pausedTotalMs)
        val card = run.pipeline.card(trip.id)!!
        assertEquals(52, card.receipt.diffMin)
        assertEquals("바다로 새 버린 주말", card.title)
    }

    @Test fun t3_lateStart_missedDriving_isAGap_notARestStop() {
        val b = pt(40.0, 0.0); val c = pt(55.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 40.0, home, b) + park(40.0, 41.0, b) +
            listOf(cycle(41.0, 600, PowerCycleMode.LATE_START), boot(66.0)) +
            listOf(Gear(m(66.0), GearPos.D), DriverBelt(m(66.0), true), Speed(m(66.0), 60f)) + drive(66.1, 71.0, c, dest).drop(3) + park(71.0, 73.0, dest)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = run.store.trips().single()
        assertEquals(1, run.store.gaps(trip.id).size)
        assertTrue(run.store.episodes(trip.id).none { it.type == "REST_STOP" })
        val card = run.pipeline.card(trip.id)!!
        assertEquals(1, card.gaps.size)
        assertTrue(card.receipt.gapNote != null)
        assertTrue(card.routeShape?.gapsAfter?.isNotEmpty() == true)
    }

    // ------------------------------------------------------------------ T4–T8

    @Test fun t4_destinationTrip_sevenHourStop_endsMaxGap_thenNewTrip() {
        val b = pt(40.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 40.0, home, b) + park(40.0, 41.0, b) +
            listOf(cycle(41.0, 7 * 3600), boot(7 * 60 + 41.0)) + park(7 * 60 + 41.0, 7 * 60 + 42.0, b) + drive(7 * 60 + 42.0, 7 * 60 + 60.0, b, dest)
        val run = TestSupport.runWithPowerCycles(events)
        val trips = run.store.trips()
        assertEquals(2, trips.size)
        assertEquals(EndReason.MAX_GAP.name, trips[0].endReason)
        assertEquals("NO_DEST", run.pipeline.card(trips[0].id)!!.receipt.mode)
    }

    @Test fun t5_restStopThenOtherDestination_isADetour() {
        val b = pt(40.0, 0.0); val other = pt(45.0, 10.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 40.0, home, b) + park(40.0, 60.0, b) +
            drive(60.0, 61.0, b, pt(41.0, 0.0)) + route(61.0, other, "바닷가 해변", 1500) + drive(61.1, 80.0, pt(41.0, 0.0), other).drop(3)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = run.store.trips().single()
        assertEquals(1, run.store.episodes(trip.id).count { it.type == "DETOUR" })
    }

    @Test fun t6_arrivedThenIgnitionOffBeforeBelt_endsArrivedPowerOff() {
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 58.0, home, dest) +
            listOf(Speed(m(58.0), 0f), Gear(m(58.0), GearPos.P), Location(m(58.1), dest.first, dest.second, 5f, null)) +
            listOf(cycle(58.5, 600), boot(68.5))
        val run = TestSupport.runWithPowerCycles(events)
        val tr = transitions(run.out)
        assertEquals(listOf(IDLE to DRIVING, DRIVING to PARKED_AT_DEST, PARKED_AT_DEST to ENDED), tr.map { it.from to it.to })
        assertEquals(EndReason.ARRIVED_POWER_OFF, tr.last().endReason)
        assertEquals("FINAL", run.store.cardForTrip(run.store.trips().single().id)!!.status)
    }

    @Test fun t7_ignitionOffBeforeBeltAtARestArea_becomesAPauseFromP() {
        val b = pt(40.0, 0.0)
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 40.0, home, b) + park(40.0, 41.0, b, beltOn = true) +
            listOf(cycle(41.0, 600), boot(51.0))
        val run = TestSupport.runWithPowerCycles(events)
        val trip = run.store.trips().single()
        assertEquals(PAUSED.name, trip.state)
        assertEquals(m(40.0), trip.pausedAt)
    }

    @Test fun t8_bootAndAppStartTogether_recoverOnce() {
        val b = pt(40.0, 0.0)
        val store = InMemoryTripStore()
        val first = TestSupport.pipeline(store)
        TestSupport.runAll(first, route(0.0, dest, "목적지", 3600) + drive(0.0, 40.0, home, b) + park(40.0, 41.0, b, beltOn = true))
        val second = TestSupport.pipeline(store)
        val out = second.process(boot(51.0, BootReason.BOOT)) + second.process(boot(51.0, BootReason.APP_START))
        assertEquals(1, transitions(out).size)
    }

    // ------------------------------------------------------------------ T9–T13

    @Test fun t9_noDestination_40MinuteStop_endsTimeout_thenNewTrip() {
        val b = pt(10.0, 0.0)
        val events = drive(0.0, 15.0, home, b) + park(15.0, 55.0, b) + drive(55.0, 70.0, b, pt(25.0, 0.0))
        val run = TestSupport.runWithPowerCycles(events)
        val trips = run.store.trips()
        assertEquals(2, trips.size)
        assertEquals(EndReason.NO_DEST_TIMEOUT.name, trips[0].endReason)
        assertEquals(m(15.0), trips[0].endedAt)
    }

    @Test fun t10_routeSetBeforeDeparture_baseEtaIsAtDeparture() {
        val events = listOf(RouteStarted(0, "목적지", dest.first, dest.second, m(60.0)), RemainTime(0, 3600), RemainTime(m(8.0), 3600)) +
            drive(8.0, 30.0, home, pt(20.0, 0.0))
        val run = TestSupport.runWithPowerCycles(events)
        assertEquals(m(68.0), run.store.trips().single().baseEtaAt)
    }

    @Test fun t11_destinationSetAfterDeparture_earlierJamOnlyInTheHeader() {
        val j = pt(4.0, 0.0); val c = pt(10.0, 0.0)
        val events = drive(0.0, 25.0, home, j, kmh = 10f) + drive(25.0, 30.0, j, c, kmh = 60f).drop(2) +
            route(30.0, dest, "목적지", 2400) + drive(30.1, 74.0, c, dest).drop(3) + park(74.0, 76.0, dest)
        val run = TestSupport.runWithPowerCycles(events)
        val trip = run.store.trips().single()
        val card = run.pipeline.card(trip.id)!!
        assertEquals(m(30.0), trip.destSetAt)
        assertTrue(run.store.episodes(trip.id).any { it.type == "JAM_ENDURED" })
        assertTrue(card.receipt.lines.none { it.type == "JAM_ENDURED" })
        assertTrue(card.receipt.summary.drivingMin >= 70)
    }

    @Test fun t12_commuteB_convenienceStore_isAPlannedStopLine() {
        val run = TestSupport.runWithPowerCycles(TestSupport.replay("commute_b.jsonl"))
        val card = run.pipeline.card(run.store.trips().single().id)!!
        assertTrue(card.receipt.lines.any { it.label.startsWith("들른 곳") && it.min == 10 })
        assertTrue(card.episodes.none { it.type == "REST_STOP" })
    }

    @Test fun t13_frequentPlace_endsAtOnce() {
        val store = InMemoryTripStore()
        val b = pt(10.0, 0.0)
        store.savePlace(PlaceEntity(lat = b.first, lng = b.second, radiusM = 200.0, visitCount = 3, lastVisitAt = 0, frequent = true))
        val run = TestSupport.runWithPowerCycles(drive(0.0, 15.0, home, b) + park(15.0, 16.0, b), store)
        val trip = store.trips().single()
        assertEquals(EndReason.NO_DEST_PLACE.name, trip.endReason)
        assertEquals("FINAL", store.cardForTrip(trip.id)!!.status)
        assertTrue(run.out.any { it is PipelineEvent.Ended })
    }

    @Test fun provisionalArrival_cardIsProvisionalWithFinalizeAt() {
        val b = pt(10.0, 0.0)
        val run = TestSupport.runWithPowerCycles(drive(0.0, 15.0, home, b) + park(15.0, 20.0, b))
        val c = run.store.cardForTrip(run.store.trips().single().id)!!
        assertEquals("PROVISIONAL", c.status)
        assertEquals(m(15.0) + 30 * 60_000L, c.finalizeAt)
    }

    // ------------------------------------------------------------------ T15–T17

    @Test fun t15_parkingLotFarFromDestination_afterGuidanceEnds() {
        val lot = pt(60.0, 0.63)        // ~700 m east of the destination
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 58.0, home, lot) +
            listOf(RouteCancelled(m(58.0))) + park(58.0, 59.0, lot)
        val run = TestSupport.runWithPowerCycles(events)
        assertTrue(transitions(run.out).any { it.to == PARKED_AT_DEST })
    }

    @Test fun t16_loopsNearTheDestination_areAParkingHunt() {
        val near = pt(59.64, 0.0)       // 400 m before the destination
        fun lap(startMin: Double, sign: Double): List<SignalEvent> {
            val side = 0.3
            val corners = listOf(0.0 to 0.0, 0.0 to sign * side, sign * side to sign * side, sign * side to 0.0, 0.0 to 0.0)
            return buildList {
                var t = startMin
                for (i in 0 until 4) for (k in 0 until 9) {
                    val f = k / 9.0
                    val (ax, ay) = corners[i]; val (bx, by) = corners[i + 1]
                    val p = pt(59.64 + ay + (by - ay) * f, ax + (bx - ax) * f)
                    add(Location(m(t), p.first, p.second, 5f, null)); add(Tick(m(t) + 1)); t += 5 / 60.0
                }
            }
        }
        val events = route(0.0, dest, "목적지", 3600) + drive(0.0, 50.0, home, near) + listOf(Speed(m(50.0), 25f)) +
            lap(50.0, 1.0) + lap(53.0, -1.0) + drive(56.5, 58.0, near, dest, 25f) + park(58.0, 59.0, dest)
        val run = TestSupport.runWithPowerCycles(events)
        val eps = run.store.episodes(run.store.trips().single().id)
        assertEquals(1, eps.count { it.type == "PARKING_HUNT" })
        assertTrue(eps.none { it.type == "LOST_LOOP" })
    }

    @Test fun t17_recordOff_recordsNothing_untilNextBoot() {
        val store = InMemoryTripStore()
        store.saveSettings(SettingsEntity(recordOff = true))
        val run = TestSupport.runWithPowerCycles(drive(0.0, 20.0, home, pt(20.0, 0.0)), store)
        assertTrue(store.trips().isEmpty())
        assertTrue(store.trackPoints("any").isEmpty())
        run.pipeline.process(PowerBoot(m(30.0), BootReason.BOOT))
        assertEquals(false, store.settings().recordOff)
        assertNull(store.activeTrip())
        assertTrue(transitions(run.out).none { it.to == ENDED })
    }
}
