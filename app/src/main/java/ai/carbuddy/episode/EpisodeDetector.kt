package ai.carbuddy.episode

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** SPEC 6.1 + trip-state hook and persistable state (detector_state table). */
interface EpisodeDetector {
    val type: EpisodeType
    val requires: Set<Capability>
    val id: String get() = type.name

    fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate>
    fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> = emptyList()

    fun saveState(): String? = null
    fun restoreState(json: String) {}
    fun reset() {}
}

/**
 * @param key identifies the episode within a trip; re-emitting the same key updates it
 *   (an episode can grow while it lasts, and a replay across a reboot does not duplicate it).
 */
data class EpisodeCandidate(
    val type: EpisodeType,
    val startT: Long,
    val endT: Long,
    val params: Map<String, Any>,
    val lostMinutes: Int? = null,
    val key: String = startT.toString(),
)

/** Base class keeping detector state in one @Serializable object. */
abstract class StatefulDetector<S : Any>(
    private val serializer: KSerializer<S>,
    private val initial: () -> S,
) : EpisodeDetector {
    protected var s: S = initial()
    override fun saveState(): String = JSON.encodeToString(serializer, s)
    override fun restoreState(json: String) { s = JSON.decodeFromString(serializer, json) }
    override fun reset() { s = initial() }

    protected fun minutes(fromMs: Long, toMs: Long): Int = Math.round((toMs - fromMs) / 60_000.0).toInt()

    companion object { val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true } }
}

/** params ⇄ JSON (paramsJson column, card text variables). */
object Params {
    fun encode(p: Map<String, Any>): String = JsonObject(p.mapValues { (_, v) ->
        when (v) {
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }).toString()

    fun decode(json: String): Map<String, Any> = Json.parseToJsonElement(json).jsonObject.mapValues { (_, el) ->
        val p = el as JsonPrimitive
        when {
            p.isString -> p.content
            p.booleanOrNull != null -> p.booleanOrNull!!
            p.intOrNull != null -> p.intOrNull!!
            p.longOrNull != null -> p.longOrNull!!
            else -> p.doubleOrNull ?: p.content
        }
    }

    fun int(p: Map<String, Any>, k: String): Int = (p[k] as? Number)?.toInt() ?: 0
}
