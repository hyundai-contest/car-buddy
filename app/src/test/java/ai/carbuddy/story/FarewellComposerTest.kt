package ai.carbuddy.story

import ai.carbuddy.TestSupport
import ai.carbuddy.card.Back
import ai.carbuddy.card.Card
import ai.carbuddy.card.CardEpisode
import ai.carbuddy.card.Receipt
import ai.carbuddy.card.Route
import ai.carbuddy.core.model.SeatPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NEXT_DESIGN 3.1 배웅: one buddy line at belt release — today's story first, then (only if any) one thing to mind. */
class FarewellComposerTest {
    private val f = FarewellComposer(TestSupport.copy())

    private fun card(vararg eps: CardEpisode) = Card(
        cardId = "c", tripId = "t", createdAt = "2026-11-07T14:02:30+09:00", title = "바다로 새 버린 주말",
        route = Route("서울 마포구", "강원 강릉시", 231, 242), receipt = Receipt(), episodes = eps.toList(),
    )

    private val loop = CardEpisode("e1", "LOST_LOOP", "2026-11-07T13:00:00+09:00", "", short = "같은 길 3바퀴", score = 7.5)
    private val jam = CardEpisode("e2", "JAM_ENDURED", "2026-11-07T10:40:00+09:00", "", short = "정체 35분", score = 4.0)

    @Test fun mindAndStory_storyFirst_onlyTheMindIsSpoken() {
        val l = f.compose(card(jam, loop), Back(windowsOpen = listOf("PASSENGER")), routine = false)!!
        assertEquals("같은 길 3바퀴, 카드로 만들어 둘게. 조수석 창문 열려 있어. 닫아 줄까?", l.text)
        assertEquals(SeatPos.PASSENGER, l.closeSeat)
        assertEquals("조수석 창문 열려 있어. 닫아 줄까?", l.speakText)
    }

    @Test fun nothingToMind_storyOnly_silent() {
        val l = f.compose(card(jam, loop), Back(), routine = false)!!
        assertEquals("같은 길 3바퀴, 카드로 만들어 둘게.", l.text)
        assertNull(l.closeSeat)
        assertNull(l.speakText)
    }

    @Test fun routineWithNothingToMind_saysNothing() {
        assertNull(f.compose(card(jam), Back(), routine = true))
    }

    @Test fun routineWithSomethingToMind_mindOnly() {
        val l = f.compose(card(jam), Back(trunkOpen = true), routine = true)!!
        assertEquals("트렁크 열려 있어. 내리면서 닫아 줘.", l.text)
        assertEquals(l.text, l.speakText)
        assertNull(l.closeSeat)
    }

    @Test fun lowFuel_isAppended_butNotSpoken() {
        val l = f.compose(card(loop), Back(fuelLow = true), routine = false)!!
        assertEquals("같은 길 3바퀴, 카드로 만들어 둘게. 다음엔 주유·충전부터 하자.", l.text)
        assertNull(l.speakText)
    }

    @Test fun windowAndTrunk_oneLine_windowFirst_thenTheTrunk() {
        val first = f.compose(null, Back(windowsOpen = listOf("REAR_LEFT"), trunkOpen = true), routine = false)!!
        assertEquals("뒷좌석 왼쪽 창문 열려 있어. 닫아 줄까?", first.text)
        assertEquals(SeatPos.REAR_LEFT, first.closeSeat)
        val next = f.compose(null, Back(trunkOpen = true), routine = false)!!
        assertEquals("트렁크 열려 있어. 내리면서 닫아 줘.", next.text)
    }

    @Test fun noCardNothingToMind_saysNothing() {
        assertNull(f.compose(null, Back(), routine = false))
        assertNull(f.compose(card(), null, routine = false))
    }

    @Test fun hiddenEpisodes_areNotTold() {
        val l = f.compose(card(jam, loop.copy(hidden = true)), Back(), routine = false)!!
        assertEquals("정체 35분, 카드로 만들어 둘게.", l.text)
    }

    @Test fun lines_passTheCopyRules() {
        val texts = listOf(
            f.compose(card(loop), Back(windowsOpen = listOf("DRIVER"), lightsOn = true, fuelLow = true), routine = false)!!.text,
            f.compose(null, Back(lightsOn = true), routine = false)!!.text,
        )
        texts.forEach { t -> listOf("만회", "빨리 왔", "칼같이").forEach { assertFalse(t, it in t) } }
        assertTrue(texts[1].startsWith("전조등"))
    }
}
