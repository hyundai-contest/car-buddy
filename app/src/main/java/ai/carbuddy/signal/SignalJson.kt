package ai.carbuddy.signal

import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.WiperMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.OffsetDateTime

/** One parsed line of a replay log (SPEC 4.3). */
sealed interface ReplayLine { val dt: Long }

/** Optional first line `{"dt":0,"type":"Meta","startAt":"2026-11-07T10:00:00+09:00"}`. */
data class MetaLine(override val dt: Long, val startAtMs: Long?) : ReplayLine

/** An event line; [build] stamps the event with an absolute time. */
data class EventLine(override val dt: Long, val type: String, val build: (t: Long) -> SignalEvent) : ReplayLine

/** JSONL codec for replay logs and signal_log payloads. */
object SignalJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLine(line: String): ReplayLine? {
        val s = line.trim()
        if (s.isEmpty() || s.startsWith("//")) return null
        val o = json.parseToJsonElement(s).jsonObject
        val dt = o.long("dt") ?: 0L
        val type = o.str("type") ?: error("missing type: $s")
        if (type == "Meta") return MetaLine(dt, o.str("startAt")?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() })
        return EventLine(dt, type, decoder(type, o))
    }

    fun parseLog(text: String): List<ReplayLine> = text.lineSequence().mapNotNull(::parseLine).toList()

    private fun decoder(type: String, o: JsonObject): (Long) -> SignalEvent = when (type) {
        "Speed" -> { t -> Speed(t, o.float("kmh") ?: 0f) }
        "Gear" -> { t -> Gear(t, o.enum("gear", GearPos.UNKNOWN)) }
        "DriverBelt" -> { t -> DriverBelt(t, o.bool("buckled") ?: false) }
        "SeatOccupancy" -> { t -> SeatOccupancy(t, o.seatMap("seats") { it.jsonPrimitive.booleanOrNull ?: false }) }
        "Wiper" -> { t -> Wiper(t, o.enum("front", WiperMode.OFF)) }
        "WindowPos" -> { t -> WindowPos(t, o.seatMap("pos") { it.jsonPrimitive.intOrNull ?: 0 }) }
        "LowBeam" -> { t -> LowBeam(t, o.bool("on") ?: false) }
        "HighBeam" -> { t -> HighBeam(t, o.bool("on") ?: false) }
        "HvacTarget" -> { t -> HvacTarget(t, o.float("driverC") ?: 0f, o.float("passengerC") ?: 0f) }
        "Location" -> { t -> Location(t, o.double("lat") ?: 0.0, o.double("lng") ?: 0.0, o.float("accuracyM"), o.float("bearing")) }
        "TurnSignal" -> { t -> TurnSignal(t, o.bool("on") ?: false) }
        "FuelPercent" -> { t -> FuelPercent(t, o.int("percent") ?: 0) }
        "TrunkOpen" -> { t -> TrunkOpen(t, o.bool("open") ?: false) }
        "RouteStarted" -> { t ->
            val eta = o.long("etaEpochMs") ?: o.long("etaOffsetSec")?.let { t + it * 1000 }
            RouteStarted(t, o.str("destName"), o.double("destLat"), o.double("destLng"), eta)
        }
        "ReRouteEnded" -> { t -> ReRouteEnded(t) }
        "DestinationChanged" -> { t -> DestinationChanged(t, o.str("destName"), o.double("destLat"), o.double("destLng")) }
        "WaypointChanged" -> { t -> WaypointChanged(t, o.str("name"), o.double("lat"), o.double("lng")) }
        "RouteCancelled" -> { t -> RouteCancelled(t) }
        "RemainTime" -> { t -> RemainTime(t, o.int("remainSec") ?: 0) }
        "DestinationArrived" -> { t -> DestinationArrived(t) }
        "CameraAlert" -> { t -> CameraAlert(t, o.int("limitKmh")) }
        "PowerBoot" -> { t -> PowerBoot(t, o.enum("reason", BootReason.APP_START)) }
        "SignalResumed" -> { t -> SignalResumed(t, o.long("silentSince") ?: t) }
        "PauseClosed" -> { t -> PauseClosed(t, o.long("pausedAt") ?: t, o.double("lat"), o.double("lng"), o.enum("kind", PauseKind.STOP), o.int("remainBeforeSec"), o.double("distToDestM")) }
        "PowerCycle" -> { t -> PowerCycle(t, o.int("offSec") ?: 0, o.enum("mode", PowerCycleMode.REBOOT), o.int("skipSec") ?: 0) }
        "Tick" -> { t -> Tick(t) }
        else -> error("unknown event type: $type")
    }

    /** Encodes an event as a replay line relative to [base] (ReplayExporter, signal_log). */
    fun encode(e: SignalEvent, base: Long = 0L): String = buildJsonObject {
        put("dt", e.t - base)
        put("type", typeName(e))
        when (e) {
            is Speed -> put("kmh", e.kmh)
            is Gear -> put("gear", e.gear.name)
            is DriverBelt -> put("buckled", e.buckled)
            is SeatOccupancy -> put("seats", JsonObject(e.seats.entries.associate { it.key.name to JsonPrimitive(it.value) }))
            is Wiper -> put("front", e.front.name)
            is WindowPos -> put("pos", JsonObject(e.pos.entries.associate { it.key.name to JsonPrimitive(it.value) }))
            is LowBeam -> put("on", e.on)
            is HighBeam -> put("on", e.on)
            is HvacTarget -> { put("driverC", e.driverC); put("passengerC", e.passengerC) }
            is Location -> { put("lat", e.lat); put("lng", e.lng); put("accuracyM", e.accuracyM); put("bearing", e.bearing) }
            is TurnSignal -> put("on", e.on)
            is FuelPercent -> put("percent", e.percent)
            is TrunkOpen -> put("open", e.open)
            is RouteStarted -> { put("destName", e.destName); put("destLat", e.destLat); put("destLng", e.destLng); put("etaEpochMs", e.etaEpochMs) }
            is DestinationChanged -> { put("destName", e.destName); put("destLat", e.destLat); put("destLng", e.destLng) }
            is RemainTime -> put("remainSec", e.remainSec)
            is CameraAlert -> put("limitKmh", e.limitKmh)
            is WaypointChanged -> { put("name", e.name); put("lat", e.lat); put("lng", e.lng) }
            is PowerBoot -> put("reason", e.reason.name)
            is SignalResumed -> put("silentSince", e.silentSince)
            is PauseClosed -> { put("pausedAt", e.pausedAt); put("lat", e.lat); put("lng", e.lng); put("kind", e.kind.name); put("remainBeforeSec", e.remainBeforeSec); put("distToDestM", e.distToDestM) }
            is PowerCycle -> { put("offSec", e.offSec); put("mode", e.mode.name); put("skipSec", e.skipSec) }
            is ReRouteEnded, is RouteCancelled, is DestinationArrived, is Tick -> Unit
        }
    }.toString()

    fun typeName(e: SignalEvent): String = e::class.simpleName ?: "Unknown"

    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonObject.str(k: String) = prim(k)?.content
    private fun JsonObject.long(k: String) = prim(k)?.longOrNull ?: prim(k)?.doubleOrNull?.toLong()
    private fun JsonObject.int(k: String) = prim(k)?.intOrNull ?: prim(k)?.doubleOrNull?.toInt()
    private fun JsonObject.float(k: String) = prim(k)?.floatOrNull
    private fun JsonObject.double(k: String) = prim(k)?.doubleOrNull
    private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
    private inline fun <reified E : Enum<E>> JsonObject.enum(k: String, default: E): E =
        str(k)?.let { v -> enumValues<E>().firstOrNull { it.name.equals(v, ignoreCase = true) } } ?: default
    private fun <V> JsonObject.seatMap(k: String, value: (JsonElement) -> V): Map<SeatPos, V> =
        (this[k] as? JsonObject)?.entries?.mapNotNull { (name, v) ->
            SeatPos.entries.firstOrNull { it.name == name }?.let { it to value(v) }
        }?.toMap() ?: emptyMap()
}
