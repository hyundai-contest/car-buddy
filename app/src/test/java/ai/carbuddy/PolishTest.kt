package ai.carbuddy

import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.story.EpisodeTexts
import ai.carbuddy.story.TitleGenerator
import ai.carbuddy.trip.TripContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 체크리스트 '다듬을 것' fixes (11/9). */
class PolishTest {
    private val copy = TestSupport.copy()
    private val titles = TitleGenerator(copy, EpisodeTexts(copy))

    @Test fun routeTitle_withoutCity_neverReadsKkajiZeroKm() {
        assertEquals("잠깐 한 바퀴", titles.route(mapOf("fromCity" to "", "toCity" to "", "km" to 0)))
        assertEquals("12km 드라이브", titles.route(mapOf("fromCity" to "", "toCity" to "", "km" to 12)))
        assertEquals("강릉까지 3km", titles.route(mapOf("fromCity" to "강릉", "toCity" to "강릉", "km" to 3)))
        assertEquals("서울에서 강릉까지 231km", titles.route(mapOf("fromCity" to "서울", "toCity" to "강릉", "km" to 231)))
    }

    @Test fun gearUnknown_standingStillSinceStart_isParkedAtOnce_butAfterDrivingWaitsTenSeconds() {
        val ctx = TripContext(DetectorConfig())
        ctx.update(speed(0.0, 0f))
        assertTrue("boot: speed 0 before any gear → parked (no blank screen)", ctx.isParked(m(0.01)))
        ctx.update(speed(1.0, 40f)); ctx.update(speed(2.0, 0f))
        assertFalse("a traffic light right after moving is not parking", ctx.isParked(m(2.1)))
        assertTrue(ctx.isParked(m(2.0) + 10_000))
    }
}
