package ai.carbuddy.signal.adapter.replay

import ai.carbuddy.data.TripStore
import ai.carbuddy.signal.EventLine
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.SignalJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A recorded trip → replay JSONL (docs/replay format: `Meta` line, then `dt` from the start), so a
 * real drive becomes a test fixture and a demo log (SPEC 4.3, 체크리스트 '실제 주행을 재생 로그로').
 *
 * Source is signal_log (every event the pipeline saw, payload = SignalJson with its own time). The
 * window runs from [marginMs] before departure to [marginMs] after the trip ended (or its last signal).
 * Boots are kept as `PowerBoot` lines; the replay harness turns real gaps into silence by itself.
 * Location stays in the file: it is a local dev export, never uploaded (CLAUDE.md 규칙 6).
 */
object ReplayExporter {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("Asia/Seoul"))

    fun export(store: TripStore, tripId: String, marginMs: Long = 60_000L): String? {
        val trip = store.trip(tripId) ?: return null
        val from = trip.startedAt - marginMs
        val to = (trip.endedAt ?: trip.lastSignalAt ?: trip.startedAt) + marginMs
        val rows = store.signals(from, to).sortedBy { it.t }
        if (rows.isEmpty()) return null
        val start = rows.first().t
        val out = StringBuilder()
        out.append("""{"dt":0,"type":"Meta","startAt":"${iso.format(Instant.ofEpochMilli(start))}","tripId":"$tripId"}""").append('\n')
        for (row in rows) {
            val line = runCatching { SignalJson.parseLine(row.payloadJson) as? EventLine }.getOrNull() ?: continue
            val e = line.build(row.t)
            if (e is PowerBoot && row.t == start) continue                 // the log starts with the car already on
            out.append(SignalJson.encode(e, start)).append('\n')
        }
        return out.toString()
    }
}
