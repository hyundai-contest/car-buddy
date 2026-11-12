package ai.carbuddy.receipt

import ai.carbuddy.TestSupport
import ai.carbuddy.card.Card
import ai.carbuddy.ceremony.NotificationBudget
import ai.carbuddy.ceremony.NotifyKind
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.InMemoryTripStore
import ai.carbuddy.data.TrackPointEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.route.RouteShaper
import ai.carbuddy.signal.SignalJson
import ai.carbuddy.signal.adapter.replay.ReplayAdapter
import ai.carbuddy.story.StoryEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReceiptAndPolicyTest {
    private val cfg = DetectorConfig()
    private val builder = ReceiptBuilder(TestSupport.copy(), cfg)
    private fun min(x: Double) = (x * 60_000).toLong()

    /** A destination trip: planned [plannedMin], arrived [diffMin] off. */
    private fun trip(plannedMin: Int, diffMin: Int) = TripEntity(
        id = "t", state = "ENDED", stateSince = 0, startedAt = 0, baseEtaAt = min(plannedMin.toDouble()),
        arrivedAt = min((plannedMin + diffMin).toDouble()), destName = "목적지", destLat = 37.0, destLng = 127.0, distanceKm = 50.0,
    )

    private fun ep(type: EpisodeType, start: Double, lost: Int) =
        StoryEpisode(type, listOf("e-$type"), min(start), min(start + lost), mapOf("minutes" to lost, "count" to 1), lost)

    @Test fun modeBoundaries_longTripTolFive() {
        assertEquals("ON_TIME", builder.build(trip(120, 5), emptyList()).mode)
        assertEquals("LATE", builder.build(trip(120, 6), emptyList()).mode)
        assertEquals("ON_TIME", builder.build(trip(120, -5), emptyList()).mode)
        assertEquals("EARLY", builder.build(trip(120, -6), emptyList()).mode)
    }

    @Test fun modeBoundaries_shortTripTolThree() {
        assertEquals("ON_TIME", builder.build(trip(45, 3), emptyList()).mode)
        assertEquals("LATE", builder.build(trip(45, 4), emptyList()).mode)
        assertEquals("ON_TIME", builder.build(trip(45, -3), emptyList()).mode)
        assertEquals("EARLY", builder.build(trip(45, -4), emptyList()).mode)
    }

    @Test fun early_isAlwaysThanksToTheRoad() {
        val r = builder.build(trip(120, -12), emptyList())
        assertEquals(listOf("덜 막힌 구간" to -12), r.lines.map { it.label to it.min })
        assertEquals("예정보다 12분 일찍 도착", r.totalLabel)
        assertEquals("일찍 도착", r.stamp)
        assertEquals("길이 잘 뚫린 날. 12분 벌었으니 커피 한 잔?", r.buddyLine)
    }

    @Test fun onTime_withAndWithoutLines() {
        val busy = builder.build(trip(90, 2), listOf(ep(EpisodeType.REST_STOP, 30.0, 15), ep(EpisodeType.LOST_LOOP, 70.0, 4)))
        assertEquals(listOf("휴게소" to 15, "뺑뺑이" to 4, "덜 막힌 구간" to -17), busy.lines.map { it.label to it.min })
        assertEquals("결국 오차 2분", busy.totalLabel)
        assertEquals("쉬고 헤맸는데도 길이 도와줬어.", busy.buddyLine)
        val plain = builder.build(trip(90, 1), emptyList())
        assertEquals(listOf("기타" to 1), plain.lines.map { it.label to it.min })
        val zero = builder.build(trip(90, 0), emptyList())
        assertTrue(zero.lines.isEmpty())
        assertEquals("예정 그대로, 오차 0분", zero.totalLabel)
        assertEquals("오늘은 예정 그대로.", zero.buddyLine)
    }

    @Test fun noDestination_noStamp_andVehicleTalliesWhenNoEpisodes() {
        val t = TripEntity(id = "n", state = "ENDED", stateSince = 0, startedAt = 0, endedAt = min(40.0), distanceKm = 12.0, tunnelCount = 4, windowOpenMs = min(12.0))
        val r = builder.build(t, emptyList())
        assertEquals("NO_DEST", r.mode)
        assertNull(r.stamp)
        assertEquals("총 40분", r.totalLabel)
        assertEquals(listOf("터널 4개", "창문 연 시간 12분"), r.summary.tallies)
    }

    @Test fun receiptJson_hasNoSpeedFields() {
        val r = builder.build(trip(120, -12), emptyList())
        val json = Card.JSON.encodeToString(ai.carbuddy.card.Receipt.serializer(), r)
        assertFalse(Regex("""(?i)"(avg|average|max|top)?speed""").containsMatchIn(json))
    }

    /** SPEC 8장 안전 규칙: no praise for the driver's speed anywhere in the copy. */
    @Test fun copyLint_noSpeedPraise() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/replay").isDirectory }
        val text = File(root, "app/src/main/res/values/strings_copy.xml").readText()
        for (banned in listOf("만회", "빨리 왔", "칼같이", "과속", "급정거")) assertFalse("CopyBook contains \"$banned\"", text.contains(banned))
    }

    // ------------------------------------------------------------------ SPEC 10.2

    @Test fun notificationBudget_noDailyCap_andThreeIgnoresStopAsking() {
        val store = InMemoryTripStore()
        val budget = NotificationBudget(store, cfg)
        val run = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"), store)
        val trip = store.trips().single()
        val card = run.pipeline.card(trip.id)!!
        val now = trip.endedAt!!
        assertTrue(budget.n1Eligible(trip, card, now))
        budget.record(NotifyKind.N1, card.cardId, now)
        assertEquals(card.cardId, budget.n4Candidate(now + 3_600_000)?.id)
        budget.record(NotifyKind.N4, card.cardId, now + 60_000)
        budget.record(NotifyKind.N1, "other", now + 90_000)
        assertEquals(card.cardId, budget.n4Candidate(now + 120_000)?.id)  // no daily cap: 3 today, still asks
        val tomorrow = now + 86_400_000
        repeat(3) { budget.ignored(trip.id) }
        assertNull(budget.n4Candidate(tomorrow))                          // ignored 3 times → archive only
        budget.viewed(trip.id, tomorrow)
        assertTrue(store.cardForTrip(trip.id)!!.viewedAt != null)
    }

    // ------------------------------------------------------------------ SPEC 6.4

    @Test fun commuteThreeTimes_thirdIsRoutine_andHomeBecomesFrequent() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/replay").isDirectory }
        val base = ReplayAdapter.timeline(SignalJson.parseLog(File(root, "docs/replay/commute_b.jsonl").readText()), fallbackStart = 0)
        val day = 86_400_000L
        val events = (0..2).flatMap { k -> base.map { e -> shift(e, k * day) } }
        val run = TestSupport.runWithPowerCycles(events)
        val trips = run.store.trips().filter { !it.discarded }
        assertEquals(3, trips.size)
        assertEquals(listOf(false, false, true), trips.map { it.routine })
        assertTrue(run.pipeline.card(trips[2].id)!!.routine)
        val home = trips[2]
        assertTrue(run.pipeline.places.isFrequent(home.lastLat!!, home.lastLng!!))
    }

    private fun shift(e: ai.carbuddy.signal.SignalEvent, by: Long): ai.carbuddy.signal.SignalEvent =
        (SignalJson.parseLine(SignalJson.encode(e, e.t)) as ai.carbuddy.signal.EventLine).build(e.t + by)

    // ------------------------------------------------------------------ SPEC 10.1

    @Test fun routeShaper_trimsBothEnds_andSkipsShortTrips() {
        val pts = (0..200).map { i -> TrackPointEntity(tripId = "r", t = i * 5_000L, lat = 37.5 + i * 0.0003, lng = 127.0 + i * 0.0002, accuracyM = 5f) }
        val shaper = RouteShaper(cfg)
        val trimmed = shaper.shape(pts, emptyList())!!
        val full = shaper.shape(pts, emptyList(), trim = false)!!
        assertTrue(trimmed.trimmed && !full.trimmed)
        assertTrue(trimmed.points.size <= cfg.routeMaxPoints)
        val shortTrip = pts.take(20)    // ~0.7 km
        assertNull(shaper.shape(shortTrip, emptyList()))
    }

}
