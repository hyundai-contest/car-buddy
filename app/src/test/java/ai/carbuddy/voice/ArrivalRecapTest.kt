package ai.carbuddy.voice

import ai.carbuddy.TestSupport
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.signal.TurnSignal
import ai.carbuddy.tick
import ai.carbuddy.trip.TripContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalRecapTest {
    private val cfg = DetectorConfig()
    private val ctx = TripContext(cfg)
    private val episodes = listOf(
        EpisodeEntity("a", "t", "JAM_ENDURED", "1", 0, 1, """{"minutes":35}"""),
        EpisodeEntity("b", "t", "LOST_LOOP", "2", 10, 11, """{"count":3,"minutes":7}"""),
    )

    private fun recap(conditions: SpeakConditions = SpeakConditions.None, setting: Boolean = true): Pair<ArrivalRecap, RecordingTtsPort> {
        val tts = RecordingTtsPort()
        return ArrivalRecap(SafetyGate({ ctx }, conditions, { setting }), tts, TestSupport.copy(), cfg) to tts
    }

    @Test fun speaksOnceWhenClear() {
        val (r, tts) = recap()
        r.schedule(0, episodes, emptyMap())
        r.onEvent(tick(0.0)); r.onEvent(tick(1.0))
        r.schedule(60_000, episodes, emptyMap())   // second ARRIVING in the same trip: ignored
        r.onEvent(tick(2.0))
        assertEquals(listOf("오늘 35분 막혔고, 같은 길 세 번 돌았지."), tts.spoken)
    }

    @Test fun turnSignalDefersByTenSecondsThenSpeaks() {
        val (r, tts) = recap()
        ctx.update(TurnSignal(0, true))
        r.schedule(0, episodes, emptyMap())
        r.onEvent(tick(0.0))
        assertTrue(tts.spoken.isEmpty())
        r.onEvent(tick(5 / 60.0))                  // before the 10 s retry
        assertTrue(tts.spoken.isEmpty())
        ctx.update(TurnSignal(8_000, false))
        r.onEvent(tick(10 / 60.0))
        assertEquals(1, tts.spoken.size)
    }

    @Test fun skippedWhenTheDeadlinePasses() {
        val busy = object : SpeakConditions {
            override fun inCall() = true
            override fun navGuidanceActive() = false
            override fun assistantActive() = false
        }
        val (r, tts) = recap(busy)
        r.schedule(0, episodes, emptyMap(), remainSec = 180)        // deadline: 1 minute before arrival
        (0..36).forEach { r.onEvent(tick(it * 5 / 60.0)) }
        assertTrue(tts.spoken.isEmpty())
        assertFalse(r.isPending)
        assertNull(r.spokenText)
    }

    @Test fun settingOffMeansSilence() {
        val (r, tts) = recap(setting = false)
        r.schedule(0, episodes, emptyMap())
        (0..10).forEach { r.onEvent(tick(it * 5 / 60.0)) }
        assertTrue(tts.spoken.isEmpty())
    }

    @Test fun zeroEpisodes_shortSentence_withinSeventyChars() {
        val (r, _) = recap()
        val s = r.compose(emptyList(), emptyMap())
        assertEquals("오늘은 조용히 잘 달렸어. 도착하면 오늘 이야기 보여 줄게.", s)
        assertTrue(s.length <= 35)
    }
}
