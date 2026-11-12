package ai.carbuddy.episode

import ai.carbuddy.DetectorHarness
import ai.carbuddy.core.geo.Sun
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.episode.detectors.CameraCleanDetector
import ai.carbuddy.episode.detectors.DriverSwapDetector
import ai.carbuddy.episode.detectors.FullHouseDetector
import ai.carbuddy.episode.detectors.NightDriveDetector
import ai.carbuddy.episode.detectors.RevisitDetector
import ai.carbuddy.episode.detectors.SunsetDriveDetector
import ai.carbuddy.episode.detectors.TunnelParadeDetector
import ai.carbuddy.episode.detectors.WindRunDetector
import ai.carbuddy.signal.CameraAlert
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.LowBeam
import ai.carbuddy.signal.SeatOccupancy
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.Tick
import ai.carbuddy.signal.WindowPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** PT detectors (기획안 v13 3장 표, SPEC 6.2 끝): positive, boundary and noise case each. */
class PtDetectorTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()
    private val noon = at(2026, 11, 7, 12, 0)
    private fun t(min: Double, base: Long = noon) = base + (min * 60_000).toLong()
    private fun ticks(from: Double, to: Double, everySec: Int = 10, base: Long = noon): List<SignalEvent> =
        generateSequence(from) { it + everySec / 60.0 }.takeWhile { it <= to }.map { Tick(t(it, base)) }.toList()
    private fun seatsAt(min: Double, vararg occ: SeatPos, base: Long = noon) = SeatOccupancy(t(min, base), SeatPos.entries.associateWith { it in occ })
    private fun windowsAt(min: Double, pct: Int) = WindowPos(t(min), SeatPos.entries.associateWith { pct })

    // ------------------------------------------------------------------ Sun
    @Test fun sun_seoulNovemberSunset_isInTheWestSouthWest() {
        val p = Sun.position(at(2026, 11, 7, 17, 25), 37.5665, 126.978)        // sunset ≈ 17:29 KST
        assertTrue("elevation ${p.elevationDeg}", p.elevationDeg in -1.5..2.0)
        assertTrue("azimuth ${p.azimuthDeg}", p.azimuthDeg in 235.0..255.0)
        assertTrue(Sun.isDay(noon, 37.5665, 126.978))
        assertTrue(!Sun.isDay(at(2026, 11, 7, 22, 0), 37.5665, 126.978))
    }

    // ------------------------------------------------------------------ FULL_HOUSE
    @Test fun fullHouse_fourSeatsForAMinute() {
        val h = DetectorHarness(FullHouseDetector())
        h.feed(seatsAt(0.0, *SeatPos.entries.toTypedArray())); h.feed(ticks(0.0, 2.0))
        assertEquals(4, h.episodes.single().params["count"])
    }

    @Test fun fullHouse_boundary_threeSeatsOrTooShort() {
        val three = DetectorHarness(FullHouseDetector())
        three.feed(seatsAt(0.0, SeatPos.DRIVER, SeatPos.PASSENGER, SeatPos.REAR_LEFT)); three.feed(ticks(0.0, 10.0))
        assertTrue(three.episodes.isEmpty())
        val short = DetectorHarness(FullHouseDetector())
        short.feed(seatsAt(0.0, *SeatPos.entries.toTypedArray())); short.feed(ticks(0.0, 0.9))
        short.feed(seatsAt(0.95, SeatPos.DRIVER)); short.feed(ticks(1.0, 5.0))
        assertTrue(short.episodes.isEmpty())
    }

    @Test fun fullHouse_noise_flickerAndRestart_onlyOnce() {
        val a = DetectorHarness(FullHouseDetector())
        a.feed(seatsAt(0.0, *SeatPos.entries.toTypedArray())); a.feed(ticks(0.0, 2.0))
        val b = a.restart(FullHouseDetector())
        b.feed(seatsAt(3.0, SeatPos.DRIVER)); b.feed(seatsAt(4.0, *SeatPos.entries.toTypedArray())); b.feed(ticks(4.0, 7.0))
        assertEquals(1, b.episodes.size)
    }

    // ------------------------------------------------------------------ WIND_RUN
    @Test fun windRun_allWindowsOpenWhileMoving() {
        val h = DetectorHarness(WindRunDetector())
        h.feed(Speed(t(0.0), 60f), windowsAt(1.0, 60)); h.feed(ticks(1.0, 5.0)); h.feed(windowsAt(5.0, 0)); h.feed(ticks(5.0, 6.0))
        assertEquals(4, h.episodes.single().params["minutes"])
    }

    @Test fun windRun_boundary_oneWindowBelowHalfOrUnderThreeMinutes() {
        val partial = DetectorHarness(WindRunDetector())
        partial.feed(Speed(t(0.0), 60f), windowsAt(1.0, 60), WindowPos(t(1.0), mapOf(SeatPos.REAR_RIGHT to 40))); partial.feed(ticks(1.0, 8.0))
        assertTrue(partial.episodes.isEmpty())
        val short = DetectorHarness(WindRunDetector())
        short.feed(Speed(t(0.0), 60f), windowsAt(1.0, 60)); short.feed(ticks(1.0, 3.9)); short.feed(windowsAt(3.9, 0)); short.feed(ticks(3.9, 6.0))
        assertTrue(short.episodes.isEmpty())
    }

    @Test fun windRun_noise_parkedDoesNotCount() {
        val h = DetectorHarness(WindRunDetector())
        h.feed(Gear(t(0.0), GearPos.P), Speed(t(0.0), 0f), windowsAt(0.0, 100)); h.feed(ticks(0.0, 10.0))
        assertTrue(h.episodes.isEmpty())
    }

    // ------------------------------------------------------------------ TUNNEL_PARADE
    private fun tunnel(startMin: Double, sec: Int, base: Long = noon) = listOf(LowBeam(t(startMin, base), true), LowBeam(t(startMin, base) + sec * 1000L, false))
    private val gangwon = Location(noon, 37.7, 128.6, 5f, null)

    @Test fun tunnelParade_threeDaytimeTunnels() {
        val h = DetectorHarness(TunnelParadeDetector())
        h.feed(gangwon); listOf(1.0, 5.0, 9.0, 13.0).forEach { h.feed(tunnel(it, 40)) }
        assertEquals(4, h.episodes.single().params["count"])
    }

    @Test fun tunnelParade_boundary_twoTunnels_tooShortOrTooLong() {
        val h = DetectorHarness(TunnelParadeDetector())
        h.feed(gangwon); h.feed(tunnel(1.0, 40)); h.feed(tunnel(3.0, 40)); h.feed(tunnel(5.0, 14)); h.feed(tunnel(7.0, 11 * 60))
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun tunnelParade_noise_headlightsAtNightAreNotTunnels() {
        val night = at(2026, 11, 7, 21, 0)
        val h = DetectorHarness(TunnelParadeDetector())
        h.feed(Location(night, 37.7, 128.6, 5f, null)); listOf(1.0, 5.0, 9.0, 13.0).forEach { h.feed(tunnel(it, 40, night)) }
        assertTrue(h.episodes.isEmpty())
    }

    // ------------------------------------------------------------------ SUNSET_DRIVE
    private val sunset = at(2026, 11, 7, 17, 15)
    /** Drive along [bearingDeg] from Seoul, one fix every 5 s. */
    private fun drive(fromMin: Double, toMin: Double, bearingDeg: Double, base: Long = sunset): List<SignalEvent> {
        var lat = 37.5665; var lng = 126.978
        val stepM = 60.0 * 1000 / 3600 * 5                                   // 60 km/h, 5 s
        return generateSequence(fromMin) { it + 5 / 60.0 }.takeWhile { it <= toMin }.map { m ->
            lat += stepM * Math.cos(Math.toRadians(bearingDeg)) / 111_320.0
            lng += stepM * Math.sin(Math.toRadians(bearingDeg)) / (111_320.0 * Math.cos(Math.toRadians(lat)))
            Location(t(m, base), lat, lng, 5f, null) as SignalEvent
        }.toList().let { listOf(Speed(t(fromMin, base), 60f) as SignalEvent) + it }
    }

    @Test fun sunsetDrive_headingIntoTheSun() {
        val h = DetectorHarness(SunsetDriveDetector())
        h.feed(drive(0.0, 8.0, 245.0))
        assertTrue((h.episodes.single().params["minutes"] as Int) >= 7)
    }

    @Test fun sunsetDrive_boundary_awayFromTheSunOrUnderFiveMinutes() {
        val east = DetectorHarness(SunsetDriveDetector()).apply { feed(drive(0.0, 10.0, 65.0)) }
        assertTrue(east.episodes.isEmpty())
        val short = DetectorHarness(SunsetDriveDetector()).apply { feed(drive(0.0, 4.5, 245.0)) }
        assertTrue(short.episodes.isEmpty())
    }

    @Test fun sunsetDrive_noise_middayWestIsNotSunset() {
        val h = DetectorHarness(SunsetDriveDetector()).apply { feed(drive(0.0, 10.0, 200.0, noon)) }
        assertTrue(h.episodes.isEmpty())
    }

    // ------------------------------------------------------------------ DRIVER_SWAP
    private val all = SeatPos.entries.toTypedArray()

    @Test fun driverSwap_passengerTakesTheWheel_andKmAfterwards() {
        val h = DetectorHarness(DriverSwapDetector())
        h.feed(seatsAt(0.0, SeatPos.DRIVER, SeatPos.PASSENGER), Speed(t(0.0), 80f)); h.feed(ticks(0.0, 30.0, 30))
        h.feed(Gear(t(30.0), GearPos.P), Speed(t(30.0), 0f))
        h.feed(seatsAt(31.0)); h.feed(seatsAt(32.0, SeatPos.DRIVER))                       // both out, one back in front
        h.feed(Gear(t(33.0), GearPos.D), Speed(t(33.0), 60f)); h.feed(ticks(33.0, 63.0, 30))
        h.end()
        val e = h.episodes.single()
        assertEquals(30, e.params["km"])
    }

    @Test fun driverSwap_boundary_driverStepsOutAndBack_isNotASwap() {
        val h = DetectorHarness(DriverSwapDetector())
        h.feed(seatsAt(0.0, SeatPos.DRIVER, SeatPos.PASSENGER), Gear(t(10.0), GearPos.P))
        h.feed(seatsAt(11.0, SeatPos.PASSENGER)); h.feed(seatsAt(15.0, SeatPos.DRIVER, SeatPos.PASSENGER))   // rest stop: same people
        h.feed(Gear(t(16.0), GearPos.D), Speed(t(16.0), 60f)); h.feed(ticks(16.0, 30.0, 30)); h.end()
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun driverSwap_noise_seatBlinkWhileDriving_ignored() {
        val h = DetectorHarness(DriverSwapDetector())
        h.feed(seatsAt(0.0, *all), Gear(t(0.0), GearPos.D), Speed(t(0.0), 60f))
        h.feed(seatsAt(5.0, SeatPos.PASSENGER, SeatPos.REAR_LEFT)); h.feed(seatsAt(5.05, SeatPos.DRIVER, SeatPos.PASSENGER, SeatPos.REAR_LEFT))
        h.feed(ticks(5.0, 15.0, 30)); h.end()
        assertTrue(h.episodes.isEmpty())
    }

    // ------------------------------------------------------------------ CAMERA_CLEAN
    private fun camera(min: Double, limit: Int, kmh: Float) = listOf(CameraAlert(t(min), limit), Speed(t(min) + 10_000, kmh))

    @Test fun cameraClean_fiveCamerasAllWithinTheLimit() {
        val h = DetectorHarness(CameraCleanDetector())
        (0 until 6).forEach { h.feed(camera(it * 5.0, 100, 95f)) }
        h.end()
        assertEquals(6, h.episodes.single().params["count"])
    }

    @Test fun cameraClean_boundary_fourCameras() {
        val h = DetectorHarness(CameraCleanDetector())
        (0 until 4).forEach { h.feed(camera(it * 5.0, 100, 90f)) }; h.end()
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun cameraClean_onceOverTheLimit_neverCreated() {
        val h = DetectorHarness(CameraCleanDetector())
        (0 until 8).forEach { h.feed(camera(it * 5.0, 100, if (it == 3) 104f else 90f)) }; h.end()
        assertTrue(h.episodes.isEmpty())
    }

    // ------------------------------------------------------------------ REVISIT
    private val endLoc = Location(noon, 37.75, 128.9, 5f, null)

    @Test fun revisit_sameCityBefore_givesWhenWord() {
        val lastYear = at(2025, 11, 2, 15, 0)
        val h = DetectorHarness(RevisitDetector { _, _ -> RevisitDetector.Visit("강릉시", lastYear) })
        h.feed(endLoc); h.end()
        val p = h.episodes.single().params
        assertEquals("강릉시", p["city"]); assertEquals("작년 11월", p["whenWord"])
    }

    @Test fun revisit_boundary_firstVisit() {
        val h = DetectorHarness(RevisitDetector { _, _ -> null })
        h.feed(endLoc); h.end()
        assertTrue(h.episodes.isEmpty())
    }

    @Test fun revisit_whenWords() {
        val d = RevisitDetector { _, _ -> null }
        assertEquals("지난주", d.whenWord(at(2026, 11, 2, 9, 0), noon))
        assertEquals("이번 달", d.whenWord(at(2026, 11, 1, 9, 0), at(2026, 11, 20, 9, 0)))
        assertEquals("지난달", d.whenWord(at(2026, 10, 3, 9, 0), noon))
        assertEquals("8월", d.whenWord(at(2026, 8, 3, 9, 0), noon))
        assertEquals("작년 11월", d.whenWord(at(2025, 11, 3, 9, 0), noon))
    }

    // ------------------------------------------------------------------ NIGHT_DRIVE
    private val oneAm = at(2026, 11, 8, 1, 0)

    @Test fun nightDrive_twentyMinutesAfterMidnight() {
        val h = DetectorHarness(NightDriveDetector())
        h.feed(Speed(oneAm, 80f)); h.feed(ticks(0.0, 25.0, 30, oneAm))
        val p = h.episodes.single().params
        assertEquals(1, p["hour"]); assertTrue((p["minutes"] as Int) >= 25)
    }

    @Test fun nightDrive_boundary_justUnderOrBefore() {
        val short = DetectorHarness(NightDriveDetector()).apply { feed(Speed(oneAm, 80f)); feed(ticks(0.0, 19.5, 30, oneAm)); feed(Speed(oneAm + 19 * 60_000 + 40_000, 0f), Gear(oneAm + 20 * 60_000, GearPos.P)); feed(ticks(20.0, 40.0, 30, oneAm)) }
        assertTrue(short.episodes.isEmpty())
        val evening = at(2026, 11, 7, 23, 0)
        val late = DetectorHarness(NightDriveDetector()).apply { feed(Speed(evening, 80f)); feed(ticks(0.0, 50.0, 30, evening)) }   // 23:00–23:50
        assertTrue(late.episodes.isEmpty())
    }

    @Test fun nightDrive_noise_parkedTimeDoesNotCount() {
        val h = DetectorHarness(NightDriveDetector())
        h.feed(Gear(oneAm, GearPos.P), Speed(oneAm, 0f)); h.feed(ticks(0.0, 40.0, 30, oneAm))
        assertTrue(h.episodes.isEmpty())
    }
}
