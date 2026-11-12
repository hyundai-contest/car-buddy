package ai.carbuddy.core.metrics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File

/**
 * UX_SPEC 9장 측정 이벤트 (사업성 검증): trip_ended, episode_hidden, screening_started/completed,
 * notify_shown/notify_action. Kept on the car as JSONL until the companion server exists (PT, 13장);
 * properties are counts and labels only — never a position (CLAUDE.md 규칙 6).
 */
interface MetricsLog {
    fun log(name: String, props: Map<String, Any?> = emptyMap())

    object None : MetricsLog { override fun log(name: String, props: Map<String, Any?>) = Unit }
}

/** Appends one line per event to [file]; trimmed to the last [maxLines] when it grows. */
class FileMetricsLog(private val file: File, private val now: () -> Long, private val maxLines: Int = 5_000) : MetricsLog {
    @Synchronized override fun log(name: String, props: Map<String, Any?>) {
        runCatching {
            val line = buildJsonObject {
                put("t", now()); put("event", name)
                props.forEach { (k, v) ->
                    when (v) {
                        null -> Unit
                        is Number -> put(k, JsonPrimitive(v))
                        is Boolean -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            }.toString()
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
            if (file.length() > maxLines * 200L) file.writeText(file.readLines().takeLast(maxLines).joinToString("\n", postfix = "\n"))
        }.onFailure { Timber.tag("CB/metrics").w(it, "metrics write failed") }
    }
}
