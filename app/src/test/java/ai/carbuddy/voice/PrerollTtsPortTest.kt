package ai.carbuddy.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerollTtsPortTest {
    /** Manual clock: tasks run when [advance] reaches their time. */
    private class FakeDelayer : TtsDelayer {
        var now = 0L
        private class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override fun postDelayed(delayMs: Long, task: () -> Unit): () -> Unit {
            val j = Job(now + delayMs, task); jobs += j
            return { j.cancelled = true }
        }
        fun advance(ms: Long) {
            now += ms
            jobs.filter { !it.cancelled && it.at <= now }.sortedBy { it.at }.forEach { jobs.remove(it); it.task() }
        }
    }

    @Test fun defaultPrerollIsWithinGuideRange() {
        assertTrue(TtsPreroll.DEFAULT_MS in 150L..200L)
        assertEquals(150L, TtsPreroll.MIN_MS)
    }

    @Test fun speaksOnlyAfterPreroll() {
        val inner = RecordingTtsPort(); val d = FakeDelayer()
        val port = PrerollTtsPort(inner, 200, d)
        port.speak("안녕", "a")
        d.advance(199)
        assertTrue(inner.spoken.isEmpty())
        d.advance(1)
        assertEquals(listOf("안녕"), inner.spoken)
    }

    @Test fun keepsOrder() {
        val inner = RecordingTtsPort(); val d = FakeDelayer()
        val port = PrerollTtsPort(inner, 200, d)
        port.speak("하나", "1"); port.speak("둘", "2")
        d.advance(200)
        assertEquals(listOf("하나", "둘"), inner.spoken)
    }

    @Test fun stopDropsWaitingUtterances() {
        val inner = RecordingTtsPort(); val d = FakeDelayer()
        val port = PrerollTtsPort(inner, 200, d)
        port.speak("취소될 말", "a")
        assertTrue(port.speaking)
        port.stop()
        assertFalse(port.speaking)
        d.advance(500)
        assertTrue(inner.spoken.isEmpty())
    }

    @Test fun speakingFalseAfterItHasStartedAndInnerIdle() {
        val inner = RecordingTtsPort(); val d = FakeDelayer()
        val port = PrerollTtsPort(inner, 200, d)
        port.speak("x", "a"); d.advance(200)
        assertFalse(port.speaking)
    }

    @Test fun zeroPrerollSpeaksImmediately() {
        val inner = RecordingTtsPort()
        PrerollTtsPort(inner, 0, FakeDelayer()).speak("바로", "a")
        assertEquals(listOf("바로"), inner.spoken)
    }
}
