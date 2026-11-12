package ai.carbuddy.place

import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.util.Geo
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.PlaceEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.episode.Params
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Learns frequently visited places, local only (SPEC 6.4): a stay of placeMinStayMin or more is a
 * visit; visits within placeRadiusM are one place; placeMinVisits within placeWindowDays → frequent.
 * Coordinates never leave the car; only a user-given name may appear on a card.
 */
class PlaceLearner(private val store: TripStore, private val config: DetectorConfig) {
    private val ser = ListSerializer(Long.serializer())
    private val json = Json

    fun isFrequent(lat: Double, lng: Double): Boolean = find(lat, lng)?.frequent == true

    fun nameAt(lat: Double, lng: Double): String? = find(lat, lng)?.name

    fun find(lat: Double, lng: Double): PlaceEntity? =
        store.places().filter { Geo.distanceM(lat, lng, it.lat, it.lng) <= it.radiusM }
            .minByOrNull { Geo.distanceM(lat, lng, it.lat, it.lng) }

    /** Records a stay [from]..[to] at a point if it was long enough. */
    fun onStay(lat: Double, lng: Double, from: Long, to: Long) {
        if (to - from < config.placeMinStayMin * 60_000L) return
        val window = config.placeWindowDays * 86_400_000L
        val p = find(lat, lng) ?: PlaceEntity(lat = lat, lng = lng, radiusM = config.placeRadiusM, visitCount = 0, lastVisitAt = from)
        val visits = (runCatching { json.decodeFromString(ser, p.visitsJson) }.getOrDefault(emptyList()) + from)
            .filter { from - it <= window }.distinct().sorted()
        store.savePlace(
            p.copy(
                visitCount = p.visitCount + 1, lastVisitAt = from, visitsJson = json.encodeToString(ser, visits),
                frequent = p.frequent || visits.size >= config.placeMinVisits,
            ),
        )
    }

    fun rename(id: Long, name: String?) { store.places().firstOrNull { it.id == id }?.let { store.savePlace(it.copy(name = name)) } }
}

/**
 * ROUTINE trips (SPEC 6.4): same start and end (each within routineRadiusM) routineMinTrips times
 * within placeWindowDays. A special episode lifts the trip back to normal.
 */
class RoutineClassifier(private val store: TripStore, private val config: DetectorConfig) {
    fun isRoutine(trip: TripEntity, episodes: List<EpisodeEntity>, score: (EpisodeEntity) -> Double): Boolean {
        val sLat = trip.startLat ?: return false; val sLng = trip.startLng ?: return false
        val eLat = trip.lastLat ?: return false; val eLng = trip.lastLng ?: return false
        val since = trip.startedAt - config.placeWindowDays * 86_400_000L
        val similar = store.trips().count { o ->
            o.id != trip.id && !o.discarded && o.endedAt != null && o.startedAt in since until trip.startedAt &&
                o.startLat != null && o.startLng != null && o.lastLat != null && o.lastLng != null &&
                Geo.distanceM(sLat, sLng, o.startLat, o.startLng) <= config.routineRadiusM &&
                Geo.distanceM(eLat, eLng, o.lastLat, o.lastLng) <= config.routineRadiusM
        }
        if (similar + 1 < config.routineMinTrips) return false
        val special = episodes.any { !it.hidden && (EpisodeType.valueOf(it.type) in PROMOTE || score(it) >= config.promoteScore) }
        return !special
    }

    private companion object {
        val PROMOTE = setOf(EpisodeType.LOST_LOOP, EpisodeType.DETOUR, EpisodeType.SUDDEN_RAIN, EpisodeType.FULL_HOUSE,
            EpisodeType.TUNNEL_PARADE, EpisodeType.SUNSET_DRIVE, EpisodeType.WIND_RUN)
    }
}

data class WeeklyDigest(val trips: Int, val jamMinutes: Int, val rainDays: Int)

/** Last week's routine trips, shown on Monday's home (SPEC 6.4 주간 요약). No notification. */
class WeeklyDigestBuilder(private val store: TripStore, private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    fun build(now: Long): WeeklyDigest? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (today.dayOfWeek != DayOfWeek.MONDAY) return null
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val from = thisMonday.minusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = thisMonday.atStartOfDay(zone).toInstant().toEpochMilli()
        val week = store.trips().filter { !it.discarded && it.endedAt != null && it.startedAt in from until to }
        val trips = week.filter { it.routine }
        if (trips.isEmpty()) return null
        var jam = 0
        val rainDays = mutableSetOf<java.time.LocalDate>()
        for (t in week) for (e in store.episodes(t.id)) {
            if (e.type == EpisodeType.JAM_ENDURED.name && t.routine) jam += Params.int(Params.decode(e.paramsJson), "minutes")
            if (e.type == EpisodeType.SUDDEN_RAIN.name) rainDays += Instant.ofEpochMilli(e.startT).atZone(zone).toLocalDate()
        }
        return WeeklyDigest(trips.size, jam, rainDays.size)
    }
}
