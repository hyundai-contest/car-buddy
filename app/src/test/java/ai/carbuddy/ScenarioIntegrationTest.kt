package ai.carbuddy

import ai.carbuddy.TestSupport.kst
import ai.carbuddy.card.Card
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.episode.Params
import ai.carbuddy.trip.PipelineEvent
import ai.carbuddy.voice.RecordingTtsPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** SPEC 6.3 integration + Step 4/5/6 DoD on the replay logs. */
class ScenarioIntegrationTest {

    private fun runA(tts: RecordingTtsPort? = null) = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"), tts = tts).let { r ->
        Triple(r.pipeline, r.out, r.store.trips().single())
    }

    // ------------------------------------------------------------------ Step 4
    @Test fun scenarioA_sixTypesExactlyOnceEach() {
        val (p, _, trip) = runA()
        val eps = p.store.episodes(trip.id)
        // the six Must types + FULL_HOUSE (four people aboard in the log), each exactly once
        assertEquals((EpisodeType.entries.filter { it.mvp } + EpisodeType.FULL_HOUSE).map { it.name }.sorted(), eps.map { it.type }.sorted())

        fun params(t: EpisodeType) = Params.decode(eps.single { it.type == t.name }.paramsJson)
        fun ep(t: EpisodeType) = eps.single { it.type == t.name }
        assertEquals(35, params(EpisodeType.JAM_ENDURED)["minutes"])
        assertEquals(kst("2026-11-07T10:40:00"), ep(EpisodeType.JAM_ENDURED).startT)
        assertEquals(19.0, params(EpisodeType.TEMP_WAR)["driverC"]); assertEquals(26.0, params(EpisodeType.TEMP_WAR)["passengerC"])
        assertEquals(kst("2026-11-07T10:52:00"), ep(EpisodeType.TEMP_WAR).startT)
        assertEquals(22, params(EpisodeType.REST_STOP)["minutes"]); assertEquals(1, params(EpisodeType.REST_STOP)["count"])
        assertEquals("안목해변", params(EpisodeType.DETOUR)["toName"]); assertEquals("카페", params(EpisodeType.DETOUR)["fromName"])
        assertEquals(kst("2026-11-07T12:31:00"), ep(EpisodeType.DETOUR).startT)
        assertEquals(3, params(EpisodeType.LOST_LOOP)["count"]); assertEquals(7, params(EpisodeType.LOST_LOOP)["minutes"])
        assertEquals("강릉", params(EpisodeType.LOST_LOOP)["cityName"])
        assertEquals(7, params(EpisodeType.SUDDEN_RAIN)["highMinutes"]); assertEquals(true, params(EpisodeType.SUDDEN_RAIN)["windowAfter"])
        assertEquals(kst("2026-11-07T13:22:00"), ep(EpisodeType.SUDDEN_RAIN).startT)
    }

    @Test fun scenarioA_episodesAreCapturedWhileDriving_notOnlyAtTheEnd() {
        val (_, out, _) = runA()
        val firstSeen = out.filterIsInstance<PipelineEvent.EpisodeCaptured>().filter { it.isNew }.associate { it.episode.type to it.episode }
        assertEquals(7, firstSeen.size)
        val arrivedAt = kst("2026-11-07T14:02:05")
        // everything except the final aggregation is known before arrival (DevOverlay "포착" toasts)
        assertTrue(firstSeen.values.all { it.endT <= arrivedAt })
    }

    // ------------------------------------------------------------------ Step 5
    @Test fun scenarioA_cardMatchesSpecExample() {
        val (p, out, trip) = runA()
        val card = out.filterIsInstance<PipelineEvent.Ended>().single().card!!
        assertEquals("바다로 새 버린 주말", card.title)
        assertTrue("receipt ${card.receipt.totalMin}", abs(card.receipt.totalMin - 52) <= 2)
        assertEquals("LATE", card.receipt.mode)
        assertEquals("지각", card.receipt.stamp)
        assertEquals("예정보다 52분 늦게 도착", card.receipt.totalLabel)
        assertEquals("늦은 만큼 이야기가 생겼어.", card.receipt.buddyLine)
        assertEquals("13:10", card.receipt.etaLabel); assertEquals("14:02", card.receipt.arrivedLabel)
        assertEquals(
            listOf("정체" to 35, "휴게소" to 22, "즉흥 바다" to 18, "뺑뺑이" to 7, "덜 막힌 구간" to -30),
            card.receipt.lines.map { it.label to it.min },
        )
        val sum = card.receipt.summary
        assertEquals(231, sum.distanceKm); assertEquals(22, sum.restMin); assertEquals(220, sum.drivingMin); assertEquals(6, sum.episodeCount)
        // today's route: ≤ 400 points, trimmed, the loop coloured and zoomed
        val shape = card.routeShape!!
        assertTrue(shape.points.size in 10..400)
        assertTrue(shape.trimmed)
        assertTrue(shape.segments.any { it.kind == "LOOP" })
        assertTrue(shape.segments.any { it.kind == "JAM" })
        assertEquals(1, shape.insets.size)
        assertTrue(shape.points.all { it.size == 2 && it[0] in 0.0..1.0 && it[1] in 0.0..1.0 })
        // no votes yet → buddy pick; ranked by score (the mockup order 길치·온도전쟁·우중 is after votes 3/1/0)
        assertEquals(listOf("길치상", "우중 드라이버상", "온도전쟁상"), card.awards.map { it.name })
        assertEquals("LOST_LOOP", card.grand?.type)
        assertEquals("CYAN", card.ambient)
        assertEquals("서울 마포구", card.route.fromCity); assertEquals("강원 강릉시", card.route.toCity)
        assertEquals(231, card.route.distanceKm); assertEquals(242, card.route.durationMin)
        assertEquals("2026.11.07 토", card.dateLabel)
        assertEquals(
            listOf(
                "35분 거북이. 다 같이 버팀.",
                "운전석 19도 vs 조수석 26도. 오늘의 온도 전쟁.",
                "휴게소 1번, 합쳐서 22분.",
                "원래 카페였는데, 안목해변으로 새 버렸어요.",
                "같은 길을 3번 돌았어. 나도 어지러웠어.",
                "갑자기 쏟아진 비, 와이퍼 최고속 7분. 그치자마자 창문 열었어.",
            ),
            card.episodes.map { it.text },
        )
        assertTrue(card.episodes.single { it.type == "LOST_LOOP" }.star)
        assertEquals("FINAL", p.store.cardForTrip(trip.id)!!.status)
        // shared JSON has no coordinates (CLAUDE.md 규칙 6)
        val json = card.toJson()
        assertFalse(Regex(""""(lat|lng|latitude|longitude|track|path)[A-Za-z]*"\s*:""").containsMatchIn(json))
        assertEquals(card, Card.fromJson(json))
    }

    @Test fun scenarioA_hidingAnEpisodeMovesItsMinutesToOther_andTitleFollows() {
        val (p, _, trip) = runA()
        val detour = p.store.episodes(trip.id).single { it.type == "DETOUR" }
        val card = p.setEpisodeHidden(trip.id, detour.id, true)!!
        assertEquals("강릉에서 길을 잃은 날", card.title)
        assertEquals(52, card.receipt.totalMin)
        assertFalse(card.receipt.lines.any { it.label.startsWith("즉흥") })
        assertEquals(-12, card.receipt.lines.single { it.type == "LESS_JAM" }.min)
        assertTrue(card.episodes.single { it.type == "DETOUR" }.hidden)
        assertFalse(card.forShare().episodes.any { it.type == "DETOUR" })
    }

    @Test fun scenarioA_mockupVotes_orderAwardsByVotes() {
        val (p, _, trip) = runA()
        val card = p.updateOverrides(trip.id) { it.copy(votes = mapOf("LOST_LOOP" to 3, "TEMP_WAR" to 1), voteClosed = true) }!!
        assertEquals(listOf("길치상" to 3, "온도전쟁상" to 1, "우중 드라이버상" to 0), card.awards.map { it.name to it.votes })
        assertTrue(card.awards.first().grand)
    }

    @Test fun scenarioA_votesPickTheGrandPrize() {
        val (p, _, trip) = runA()
        val card = p.updateOverrides(trip.id) { it.copy(votes = mapOf("TEMP_WAR" to 3, "LOST_LOOP" to 1), voteClosed = true) }!!
        assertEquals("TEMP_WAR", card.grand?.type)
        assertEquals("온도전쟁상", card.awards.first().name)
        assertEquals("CYAN", card.ambient)   // TEMP_WAR is White → title (DETOUR) colour
    }

    @Test fun commuteB_singleJam_titleAndTimeReceipt_exitCheck() {
        val out = TestSupport.runWithPowerCycles(TestSupport.replay("commute_b.jsonl")).out
        val ended = out.filterIsInstance<PipelineEvent.Ended>().single()
        val card = ended.card!!
        assertEquals("25분을 버틴 수요일", card.title)
        assertEquals("NO_DEST", card.receipt.mode)
        assertEquals(null, card.receipt.stamp)
        assertEquals("목적지 없이 달린 날.", card.receipt.buddyLine)
        assertEquals(1, card.passengers)
        assertEquals(listOf("PASSENGER"), card.back.windowsOpen)
        assertTrue(card.back.fuelLow)
        assertTrue("garage: weak GPS → parking memo prompt", card.back.parkingPrompt)
        assertEquals(1, card.awards.size)
    }

    @Test fun secondTripOfSameType_isLessRare() {
        val store = ai.carbuddy.data.InMemoryTripStore()
        val p1 = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"), store).pipeline
        val first = p1.card(store.trips().single().id)!!
        assertEquals(7.5, first.episodes.first { it.type == "LOST_LOOP" }.score, 0.01)
        assertEquals(1, p1.buddy.current().tripCount)
        assertEquals(1, p1.buddy.typeCounts()[EpisodeType.LOST_LOOP])
    }

    // ------------------------------------------------------------------ Step 6
    @Test fun scenarioA_recapSpokenOnceAfterTurnSignal() {
        val tts = RecordingTtsPort()
        val (_, out, trip) = runA(tts)
        assertEquals(1, tts.spoken.size)
        assertEquals("오늘 같은 길 세 번 돌았고, 소나기도 뚫었지.", tts.spoken.single())
        assertTrue(tts.spoken.single().length <= 35)
        val spokenAt = out.indexOfFirst { it is PipelineEvent.RecapSpoken }
        val arrivingAt = out.indexOfFirst { it is PipelineEvent.StateChanged && it.transition.to.name == "ARRIVING" }
        assertTrue(spokenAt > arrivingAt)
        assertTrue(trip.recapDone)
        assertNotNull(trip)
    }
}
