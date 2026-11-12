package ai.carbuddy.signal

import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.signal.adapter.replay.ReplayAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayAdapterTest {

    /** SPEC 4.3 sample, 10 event lines + a Meta line. */
    private val sample = """
        {"dt":0,"type":"Meta","startAt":"2026-11-07T10:00:00+09:00"}
        {"dt":0,"type":"RouteStarted","destName":"강릉 카페","destLat":37.77,"destLng":128.94,"etaOffsetSec":11400}
        {"dt":1000,"type":"Gear","gear":"D"}
        {"dt":1500,"type":"Speed","kmh":42}
        {"dt":2000,"type":"Location","lat":37.556,"lng":126.923,"accuracyM":5,"bearing":90}
        {"dt":2400000,"type":"Speed","kmh":12}
        {"dt":7800000,"type":"DestinationChanged","destName":"안목해변","destLat":37.772,"destLng":128.947}
        {"dt":9000000,"type":"ReRouteEnded"}
        {"dt":11000000,"type":"Wiper","front":"HIGH"}
        {"dt":13900000,"type":"DestinationArrived"}
        {"dt":13901000,"type":"Gear","gear":"P"}
    """.trimIndent()

    private val start = 1_794_013_200_000L // 2026-11-07T10:00:00+09:00

    @Test fun parsesAllLinesWithTypedFields() {
        val lines = SignalJson.parseLog(sample)
        assertEquals(11, lines.size)
        val events = ReplayAdapter.timeline(lines, fallbackStart = 0, tickEveryMs = 0)
        assertEquals(10, events.size)
        val route = events[0] as RouteStarted
        assertEquals(start, route.t)
        assertEquals("강릉 카페", route.destName)
        assertEquals(start + 11_400_000, route.etaEpochMs)
        assertEquals(GearPos.D, (events[1] as Gear).gear)
        assertEquals(42f, (events[2] as Speed).kmh)
        assertEquals(5f, (events[3] as Location).accuracyM)
        assertEquals(WiperMode.HIGH, (events[7] as Wiper).front)
        assertEquals(GearPos.P, (events[9] as Gear).gear)
    }

    @Test fun eventsKeepOrderAndAbsoluteTimes() {
        val events = ReplayAdapter.timeline(SignalJson.parseLog(sample), fallbackStart = 0, tickEveryMs = 0)
        val expectedDt = listOf(0L, 1000, 1500, 2000, 2_400_000, 7_800_000, 9_000_000, 11_000_000, 13_900_000, 13_901_000)
        assertEquals(expectedDt.map { start + it }, events.map { it.t })
        val types = events.map { SignalJson.typeName(it) }
        assertEquals(
            listOf("RouteStarted", "Gear", "Speed", "Location", "Speed", "DestinationChanged", "ReRouteEnded", "Wiper", "DestinationArrived", "Gear"),
            types,
        )
    }

    @Test fun fallbackStartUsedWhenNoMetaOrDisabled() {
        val lines = SignalJson.parseLog(sample)
        val events = ReplayAdapter.timeline(lines, fallbackStart = 5_000, useLogStartTime = false, tickEveryMs = 0)
        assertEquals(5_000L, events.first().t)
    }

    @Test fun ticksAreInsertedInOrder() {
        val events = ReplayAdapter.timeline(SignalJson.parseLog(sample), fallbackStart = 0, tickEveryMs = 60_000)
        assertTrue(events.zipWithNext().all { (a, b) -> a.t <= b.t })
        val ticks = events.filterIsInstance<Tick>()
        assertEquals(13_901_000L / 60_000, ticks.size.toLong())
    }

    @Test fun playEmitsOnVirtualClockAtSpeed() = runTest {
        val got = mutableListOf<SignalEvent>()
        val adapter = ReplayAdapter({ got += it })
        adapter.speed = 10f
        adapter.play(SignalJson.parseLog(sample), fallbackStart = 0, tickEveryMs = 0)
        assertEquals(10, got.size)
        assertEquals(start + 13_901_000, got.last().t)
        // 13 901 s of log at 10x ≈ 1 390 s of (virtual coroutine) wall time
        assertEquals(1_390_100L, currentTime)
    }

    @Test fun encodeRoundTrips() {
        val events = ReplayAdapter.timeline(SignalJson.parseLog(sample), fallbackStart = 0, tickEveryMs = 0)
        val again = events.map { SignalJson.parseLine(SignalJson.encode(it, start)) as EventLine }.map { it.build(start + it.dt) }
        assertEquals(events, again)
    }
}
