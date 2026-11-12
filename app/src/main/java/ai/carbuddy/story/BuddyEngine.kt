package ai.carbuddy.story

import ai.carbuddy.card.Card
import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.BuddyEntity
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.Params
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 확장 A growing buddy (기획안 v13): stages from trip count / km / new cities, items from
 * episode counts. Never punishes or regresses. Also owns the per-type history used for rarity.
 */
class BuddyEngine(private val store: TripStore) {
    /** What changed on this trip, for the card's buddy line (기획안: 버디의 변화는 카드 앞면에). */
    data class News(val grewTo: String? = null, val newItems: List<String> = emptyList())

    /** One of the 8 items (기획안 v13 확장 A 표) and how far along it is. */
    data class ItemProgress(val id: String, val have: Boolean, val now: Int, val goal: Int)

    fun itemProgress(): List<ItemProgress> {
        val counts = typeCounts()
        val have = items().toSet()
        return ITEM_RULES.map { (id, rule) ->
            val now = if (rule.type == null) jamSoFar(current()) / 60 else counts[rule.type] ?: 0
            ItemProgress(id, id in have, minOf(now, rule.goal), rule.goal)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val countsSer = MapSerializer(String.serializer(), Int.serializer())
    private val listSer = ListSerializer(String.serializer())

    fun current(): BuddyEntity = store.buddy()

    fun typeCounts(): Map<EpisodeType, Int> = runCatching {
        json.decodeFromString(countsSer, current().typeCountsJson)
    }.getOrDefault(emptyMap()).mapNotNull { (k, v) -> EpisodeType.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()

    fun items(): List<String> = runCatching { json.decodeFromString(listSer, current().itemsJson) }.getOrDefault(emptyList())
    fun cities(): List<String> = runCatching { json.decodeFromString(listSer, current().citiesJson) }.getOrDefault(emptyList())

    /** A trip is counted once its card is FINAL. */
    fun isCounted(tripId: String): Boolean = store.cardForTrip(tripId)?.status.let { it == "FINAL" || it == "SYNCED" }

    fun tripNumber(tripId: String): Int =
        store.cardForTrip(tripId)?.takeIf { isCounted(tripId) }?.let { runCatching { Card.fromJson(it.json).tripNo }.getOrNull() }
            ?: (current().tripCount + 1)

    fun onTripFinal(trip: TripEntity, episodes: List<EpisodeEntity>, cities: CityResolver): News {
        val b = current()
        val counts = typeCounts().toMutableMap()
        val types = episodes.map { EpisodeType.valueOf(it.type) }.toSet()
        types.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val jamMinutes = episodes.filter { it.type == EpisodeType.JAM_ENDURED.name }
            .sumOf { Params.int(Params.decode(it.paramsJson), "minutes") }
        val visited = cities().toMutableSet()
        listOfNotNull(
            trip.startLat?.let { la -> trip.startLng?.let { cities.resolve(la, it)?.full } },
            trip.lastLat?.let { la -> trip.lastLng?.let { cities.resolve(la, it)?.full } },
        ).forEach { visited += it }
        val tripCount = b.tripCount + 1
        val totalKm = b.totalKm + trip.distanceKm
        val totalJam = jamSoFar(b) + jamMinutes
        val items = items().toMutableList()
        val before = items.toSet()
        ITEM_RULES.forEach { (id, rule) ->
            val now = if (rule.type == null) totalJam / 60 else counts[rule.type] ?: 0
            if (now >= rule.goal && id !in items) items += id
        }
        val stage = when {
            tripCount >= 60 && visited.size >= 10 -> "VETERAN"
            tripCount >= 20 || totalKm >= 1000 -> "COMPANION"
            tripCount >= 5 -> "SPROUT"
            else -> "EGG"
        }
        store.saveBuddy(
            b.copy(
                stage = stage, tripCount = tripCount, totalKm = totalKm,
                typeCountsJson = json.encodeToString(countsSer, counts.mapKeys { it.key.name } + (JAM_KEY to totalJam)),
                citiesJson = json.encodeToString(listSer, visited.toList()),
                itemsJson = json.encodeToString(listSer, items),
                equipped = b.equipped ?: items.lastOrNull(),
            ),
        )
        return News(grewTo = stage.takeIf { it != b.stage }, newItems = items.filter { it !in before })
    }

    /** Equip one owned item, or none (null). Only from the parked buddy screen (기획안 확장 A). */
    fun equip(item: String?) {
        val b = current()
        if (item == null || item in items()) store.saveBuddy(b.copy(equipped = item))
    }

    private fun jamSoFar(b: BuddyEntity): Int = runCatching {
        json.decodeFromString(countsSer, b.typeCountsJson)[JAM_KEY] ?: 0
    }.getOrDefault(0)

    fun nextStageGoal(): Pair<String, Int>? {
        val b = current()
        return when (b.stage) {
            "EGG" -> "SPROUT" to 5
            "SPROUT" -> "COMPANION" to 20
            "COMPANION" -> "VETERAN" to 60
            else -> null
        }
    }

    private data class Rule(val type: EpisodeType?, val goal: Int)

    private companion object {
        const val JAM_KEY = "_jamMinutes"
        /** 기획안 v13 확장 A: item ← episode count (CUSHION: total jam hours, type = null). */
        val ITEM_RULES = linkedMapOf(
            "COMPASS" to Rule(EpisodeType.LOST_LOOP, 3),
            "MAP" to Rule(EpisodeType.DETOUR, 3),
            "RAINCOAT" to Rule(EpisodeType.SUDDEN_RAIN, 2),
            "HANDY_FAN" to Rule(EpisodeType.TEMP_WAR, 3),
            "CUSHION" to Rule(null, 3),
            "WAVE" to Rule(EpisodeType.WIND_RUN, 3),
            "STARLIGHT" to Rule(EpisodeType.NIGHT_DRIVE, 3),
            "FRIEND_BADGE" to Rule(EpisodeType.FULL_HOUSE, 3),
        )
    }
}
