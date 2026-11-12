package ai.carbuddy.episode

import ai.carbuddy.DetectorHarness
import ai.carbuddy.belt
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.destChanged
import ai.carbuddy.episode.detectors.DetourDetector
import ai.carbuddy.episode.detectors.JamEnduredDetector
import ai.carbuddy.episode.detectors.LostLoopDetector
import ai.carbuddy.episode.detectors.RestStopDetector
import ai.carbuddy.episode.detectors.SuddenRainDetector
import ai.carbuddy.episode.detectors.TempWarDetector
import ai.carbuddy.gear
import ai.carbuddy.hvac
import ai.carbuddy.m
import ai.carbuddy.remain
import ai.carbuddy.reroute
import ai.carbuddy.route
import ai.carbuddy.seats
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.PauseClosed
import ai.carbuddy.signal.PauseKind
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.speed
import ai.carbuddy.ticks
import ai.carbuddy.window
import ai.carbuddy.wiper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class DetectorTest {

    // =================================================================== JAM_ENDURED
    private fun jam(minutes: Double, recoveryAt: Double? = null, recoverySec: Int = 0): List<SignalEvent> = buildList {
        add(speed(0.0, 60f))
        add(speed(1.0, 12f))
        recoveryAt?.let { add(speed(1.0 + it, 35f)); add(speed(1.0 + it + recoverySec / 60.0, 10f)) }
        addAll(ticks(1.0, 1.0 + minutes, 10))
        add(speed(1.0 + minutes, 60f))
        addAll(ticks(1.0 + minutes, 3.0 + minutes, 10))
    }.sortedBy { it.t }

    @Test fun jam_positive_minutesAndLost() {
        val h = DetectorHarness(JamEnduredDetector())
        h.feed(jam(25.0))
        val e = h.episodes.single()
        assertEquals(25, e.params["minutes"]); assertEquals(25, e.lostMinutes); assertEquals(m(1.0), e.startT)
    }

    @Test fun jam_boundary_justUnderTwentyMinutes() {
        val h = DetectorHarness(JamEnduredDetector())
        h.feed(jam(19.9))
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun jam_briefRecoveryBridged_longRecoveryBreaks() {
        val bridged = DetectorHarness(JamEnduredDetector()).apply { feed(jam(25.0, recoveryAt = 10.0, recoverySec = 25)) }
        assertEquals(25, bridged.episodes.single().params["minutes"])
        // a 45 s recovery splits it into 10 + 14 minutes: neither reaches 20
        val broken = DetectorHarness(JamEnduredDetector()).apply { feed(jam(25.0, recoveryAt = 10.0, recoverySec = 45)) }
        assertTrue(broken.episodes.isEmpty())
    }

    @Test fun jam_parkBreaksSegment_andRestartDoesNotDuplicate() {
        val h = DetectorHarness(JamEnduredDetector())
        h.feed(speed(0.0, 10f)); h.feed(ticks(0.0, 12.0, 30))
        h.feed(gear(12.0, GearPos.P)); h.feed(gear(13.0, GearPos.D)); h.feed(speed(13.0, 10f)); h.feed(ticks(13.0, 25.0, 30))
        assertTrue("12 + 12 minutes split by P", h.episodes.isEmpty())

        val a = DetectorHarness(JamEnduredDetector())
        a.feed(speed(0.0, 10f)); a.feed(ticks(0.0, 22.0, 30))           // provisional episode emitted
        val b = a.restart(JamEnduredDetector())
        b.feed(ticks(22.0, 30.0, 30)); b.feed(speed(30.0, 50f)); b.feed(ticks(30.0, 31.0, 10))
        assertEquals(1, b.episodes.size)
        assertEquals(30, b.episodes.single().params["minutes"])
    }

    // =================================================================== SUDDEN_RAIN
    private fun rain(riseMin: Double, highMin: Double, windowAfterMin: Double? = null) = buildList {
        add(wiper(0.0, WiperMode.OFF))
        add(wiper(1.0, WiperMode.LOW))
        add(wiper(1.0 + riseMin, WiperMode.HIGH))
        addAll(ticks(1.0 + riseMin, 1.0 + riseMin + highMin, 15))
        add(wiper(1.0 + riseMin + highMin, WiperMode.LOW))
        add(wiper(2.0 + riseMin + highMin, WiperMode.OFF))
        windowAfterMin?.let { add(window(2.0 + riseMin + highMin + it, SeatPos.PASSENGER, 40)) }
    }

    @Test fun rain_positive_withWindowAfter() {
        val h = DetectorHarness(SuddenRainDetector())
        h.feed(rain(riseMin = 2.0, highMin = 7.0, windowAfterMin = 3.0))
        val e = h.episodes.single()
        assertEquals(7, e.params["highMinutes"]); assertEquals(true, e.params["windowAfter"]); assertEquals(m(1.0), e.startT)
    }

    @Test fun rain_boundary_highTwoMinutesFiftyNine_orSlowRise() {
        assertTrue(DetectorHarness(SuddenRainDetector()).apply { feed(rain(2.0, 2.0 + 59 / 60.0)) }.episodes.isEmpty())
        assertTrue(DetectorHarness(SuddenRainDetector()).apply { feed(rain(10.5, 5.0)) }.episodes.isEmpty())
    }

    @Test fun rain_windowTooLate_noFlag_andDuplicateEventsIgnored() {
        val h = DetectorHarness(SuddenRainDetector())
        val events = rain(2.0, 4.0, windowAfterMin = 25.0)
        h.feed(events + events.filterIsInstance<ai.carbuddy.signal.Wiper>().takeLast(1))   // duplicated OFF
        val e = h.episodes.single()
        assertEquals(false, e.params["windowAfter"])
        assertEquals(4, e.params["highMinutes"])
    }

    // =================================================================== TEMP_WAR
    @Test fun tempWar_positive_keepsWidestGap() {
        val h = DetectorHarness(TempWarDetector())
        h.feed(seats(0.0, SeatPos.DRIVER, SeatPos.PASSENGER), hvac(0.0, 22f, 22f), hvac(2.0, 22f, 26f), hvac(5.0, 19f, 26f))
        h.feed(ticks(5.0, 15.0, 30)); h.feed(hvac(15.0, 22f, 22f))
        val e = h.episodes.single()
        assertEquals(19.0, e.params["driverC"]); assertEquals(26.0, e.params["passengerC"]); assertEquals(m(2.0), e.startT)
    }

    @Test fun tempWar_boundary_nineMinutesOrSmallGap() {
        val short = DetectorHarness(TempWarDetector()).apply {
            feed(seats(0.0, SeatPos.DRIVER, SeatPos.PASSENGER), hvac(1.0, 19f, 26f)); feed(ticks(1.0, 10.0, 30)); feed(hvac(10.9, 22f, 22f))
        }
        assertTrue(short.episodes.isEmpty())
        val small = DetectorHarness(TempWarDetector()).apply {
            feed(seats(0.0, SeatPos.DRIVER, SeatPos.PASSENGER), hvac(1.0, 22f, 24.5f)); feed(ticks(1.0, 30.0, 30))
        }
        assertTrue(small.episodes.isEmpty())
    }

    @Test fun tempWar_noPassenger_noEpisode_andRestartKeepsOne() {
        val solo = DetectorHarness(TempWarDetector()).apply { feed(seats(0.0, SeatPos.DRIVER), hvac(1.0, 18f, 28f)); feed(ticks(1.0, 30.0, 30)) }
        assertTrue(solo.episodes.isEmpty())
        val a = DetectorHarness(TempWarDetector())
        a.feed(seats(0.0, SeatPos.DRIVER, SeatPos.PASSENGER), hvac(1.0, 19f, 26f)); a.feed(ticks(1.0, 12.0, 30))
        val b = a.restart(TempWarDetector())
        b.feed(seats(12.0, SeatPos.DRIVER, SeatPos.PASSENGER), hvac(12.0, 19f, 26f)); b.feed(ticks(12.0, 20.0, 30)); b.feed(hvac(20.0, 22f, 22f))
        assertEquals(1, b.episodes.size)
        assertEquals(m(1.0), b.episodes.single().startT)
    }

    // =================================================================== LOST_LOOP
    private val x = 37.77 to 128.935
    private fun pt(eastM: Double, northM: Double) =
        (x.first + northM / 111_320.0) to (x.second + eastM / (111_320.0 * cos(Math.toRadians(x.first))))

    /** Drives a square lap (side metres) starting and ending at X, one fix per 5 s at ~25 km/h. */
    private fun lap(startMin: Double, side: Double, sign: Double): List<Location> {
        val corners = listOf(0.0 to 0.0, 0.0 to sign * side, sign * side to sign * side, sign * side to 0.0, 0.0 to 0.0)
        val out = mutableListOf<Location>()
        val stepM = 25 / 3.6 * 5
        var t = startMin
        for (i in 0 until 4) {
            val (ax, ay) = corners[i]; val (bx, by) = corners[i + 1]
            val n = (side / stepM).toInt().coerceAtLeast(1)
            for (k in 0 until n) {
                val f = k.toDouble() / n
                val (la, ln) = pt(ax + (bx - ax) * f, ay + (by - ay) * f)
                out += Location(m(t), la, ln, 5f, null); t += 5 / 60.0
            }
        }
        val (la, ln) = pt(0.0, 0.0); out += Location(m(t), la, ln, 5f, null)
        return out
    }

    private fun lostHarness() = DetectorHarness(LostLoopDetector(ai.carbuddy.TestSupport.cities)).apply { feed(speed(0.0, 25f)) }

    @Test fun loop_positive_threePassesIsThreeLaps() {
        val h = lostHarness()
        val l1 = lap(0.0, 365.0, 1.0)
        val l2 = lap(l1.last().t / 60_000.0, 365.0, -1.0)
        h.feed(l1 + l2.drop(1))
        val e = h.episodes.single()
        assertEquals(3, e.params["count"]); assertEquals("강릉", e.params["cityName"])
        assertEquals(e.params["minutes"], e.lostMinutes)
    }

    @Test fun loop_boundary_singleRevisitIsNotALoop() {
        val h = lostHarness()
        h.feed(lap(0.0, 365.0, 1.0))
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun loop_reRoutes_twoWithinTenMinutes_mergedWithGpsLoop() {
        val single = lostHarness().apply { feed(reroute(1.0), reroute(12.0)) }
        assertTrue("two re-routes 11 min apart", single.episodes.isEmpty())
        val h = lostHarness()
        val l1 = lap(0.0, 365.0, 1.0)
        val l2 = lap(l1.last().t / 60_000.0, 365.0, -1.0)
        h.feed(l1); h.feed(reroute(1.5), reroute(4.0)); h.feed(l2.drop(1))
        assertEquals(1, h.episodes.size)
        assertEquals(3, h.episodes.single().params["count"])
    }

    @Test fun loop_parkedOrCrawling_isNotALoop() {
        val h = DetectorHarness(LostLoopDetector())
        val (la, ln) = pt(0.0, 0.0)
        h.feed(speed(0.0, 3f))   // standing / crawling: ignored
        h.feed((0..240).map { Location(m(it * 5 / 60.0), la, ln, 5f, null) })
        h.feed(speed(20.0, 30f))
        h.feed((0..40).map { Location(m(20 + it * 5 / 60.0), la + it * 1e-5, ln, 5f, null) })   // stop-and-go, never left
        assertTrue(h.episodes.isEmpty())
    }

    // =================================================================== DETOUR
    private val cafe = 37.892 to 128.826
    private val beach = 37.7717 to 128.9473

    private fun detourHarness() = DetectorHarness(DetourDetector()).apply {
        feed(route(0.0, "카페", cafe.first, cafe.second, 190.0))
        ctx.origDest = ctx.dest
    }

    @Test fun detour_positive_returnToOriginal_lostIsEtaGrowth() {
        val h = detourHarness()
        h.feed(remain(150.0, 2400), destChanged(150.0, "안목해변", beach.first, beach.second), remain(150.02, 1300))
        h.feed(remain(189.0, 300), destChanged(189.0, "카페", cafe.first, cafe.second), remain(189.02, 1560))
        val e = h.episodes.single()
        assertEquals("카페", e.params["fromName"]); assertEquals("안목해변", e.params["toName"]); assertEquals("바다", e.params["place"])
        assertEquals(25, e.lostMinutes)   // ETA 13:11 → 13:36 in scenario A terms
    }

    @Test fun detour_boundary_changeToSameDestinationIsNotADetour() {
        val h = detourHarness()
        h.feed(remain(10.0, 3000), destChanged(10.0, "카페", cafe.first + 0.001, cafe.second), remain(10.1, 2990))
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun detour_permanentChange_endsAtNewPlace_andDuplicatesIgnored() {
        val h = detourHarness()
        val change = destChanged(60.0, "속초 해수욕장", 38.19, 128.60)
        h.feed(remain(60.0, 4000), change, change, remain(60.05, 4900))
        h.end()
        val e = h.episodes.single()
        assertEquals(15, e.lostMinutes)
        assertEquals("바다", e.params["place"])
    }

    @Test fun detour_offRouteStop_vs_restStop() {
        fun stopRun(remainAfter: Int): Pair<DetectorHarness, DetectorHarness> {
            val d = detourHarness()
            val r = DetectorHarness(RestStopDetector()).apply { feed(route(0.0, "카페", cafe.first, cafe.second, 190.0)); ctx.origDest = ctx.dest }
            for (h in listOf(d, r)) {
                h.feed(Location(m(59.9), 37.5, 128.0, 5f, null), remain(59.9, 6000), speed(60.0, 0f), gear(60.0, GearPos.P), belt(60.1, false))
                h.feed(belt(81.0, true), gear(82.0, GearPos.D))
                h.feed(PauseClosed(m(82.0), m(60.0), 37.5, 128.0, PauseKind.STOP, 6000, h.ctx.distanceToDestM(37.5, 128.0)))
                h.feed(speed(82.0, 30f), remain(82.1, remainAfter))
            }
            return d to r
        }
        val (d1, r1) = stopRun(remainAfter = 6010)     // ETA unchanged → rest area
        assertTrue(d1.episodes.isEmpty())
        assertEquals(22, r1.episodes.single().params["minutes"])
        val (d2, r2) = stopRun(remainAfter = 6600)     // ETA +10 min → we went somewhere else
        assertEquals(22, d2.episodes.single().lostMinutes)
        assertTrue(r2.episodes.isEmpty())
    }

    // =================================================================== REST_STOP
    private fun restRun(startMin: Double, stayMin: Double, h: DetectorHarness = DetectorHarness(RestStopDetector()), kind: PauseKind = PauseKind.STOP): DetectorHarness {
        h.feed(Location(m(startMin - 0.1), 37.5, 128.0, 5f, null), speed(startMin, 0f), gear(startMin, GearPos.P), belt(startMin + 0.2, false))
        h.feed(belt(startMin + stayMin - 0.5, true), gear(startMin + stayMin, GearPos.D))
        h.feed(PauseClosed(m(startMin + stayMin), m(startMin), 37.5, 128.0, kind, null, h.ctx.distanceToDestM(37.5, 128.0)))
        h.feed(speed(startMin + stayMin, 40f)); h.feed(ticks(startMin + stayMin, startMin + stayMin + 4, 30))
        return h
    }

    @Test fun rest_positive_twoStopsAggregate() {
        val h = restRun(95.0, 22.0)
        restRun(150.0, 13.0, h)
        val e = h.episodes.single()
        assertEquals(2, e.params["count"]); assertEquals(35, e.params["minutes"]); assertEquals(35, e.lostMinutes)
    }

    @Test fun rest_boundary_tooEarlyOrTooShort_noUpperBound() {
        assertTrue(restRun(29.0, 20.0).episodes.isEmpty())        // before 30 min of driving
        assertTrue(restRun(60.0, 4.9).episodes.isEmpty())         // under 5 minutes
        assertEquals(75, restRun(60.0, 75.0).episodes.single().params["minutes"])   // ignition off for an hour: still a rest
    }

    @Test fun rest_nearDestination_isNotARestStop_andTripEndingThereIsNot() {
        val near = DetectorHarness(RestStopDetector()).apply {
            feed(route(0.0, "목적지", 37.5005, 128.0005, 120.0))
        }
        assertTrue(restRun(60.0, 15.0, near).episodes.isEmpty())
        // a stop that ended the trip never produces PauseClosed; a provisional (no-destination) one is '들른 곳'
        assertTrue(restRun(60.0, 15.0, kind = PauseKind.PROVISIONAL).episodes.isEmpty())
        assertTrue(restRun(60.0, 15.0, kind = PauseKind.WAYPOINT).episodes.isEmpty())
    }

    @Test fun allMustDetectorsHaveTypesAndPersistableState() {
        val ds = EpisodeEngine.mustDetectors(null)
        assertEquals(EpisodeType.entries.filter { it.mvp }.toSet(), ds.map { it.type }.toSet())
        ds.forEach { d -> val s = d.saveState()!!; d.reset(); d.restoreState(s) }
    }
}
