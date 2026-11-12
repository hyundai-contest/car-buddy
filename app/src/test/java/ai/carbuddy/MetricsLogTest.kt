package ai.carbuddy

import ai.carbuddy.core.metrics.FileMetricsLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** UX_SPEC 9장 측정 이벤트: one JSON line per event, labels and counts only. */
class MetricsLogTest {
    @Test fun appendsJsonLines_andTrims() {
        val f = File.createTempFile("metrics", ".jsonl").apply { delete() }
        val log = FileMetricsLog(f, now = { 1000L }, maxLines = 10)
        log.log("trip_ended", mapOf("episodes" to 3, "hasDest" to true, "endReason" to "ARRIVED", "skip" to null))
        val line = f.readLines().single()
        assertTrue(line, line.contains("\"event\":\"trip_ended\"") && line.contains("\"episodes\":3") && line.contains("\"hasDest\":true"))
        assertFalse(line.contains("skip"))
        repeat(200) { log.log("notify_shown", mapOf("kind" to "N1")) }
        assertTrue(f.readLines().size <= 210)
        assertTrue(f.readLines().last().contains("\"event\":\"notify_shown\""))
        f.delete()
    }
}
