package ai.carbuddy.story

import ai.carbuddy.card.Card
import ai.carbuddy.data.TripEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 연말 결산 ("우리의 2026"): one year of trips summed up like a music app's year in review.
 * Built only from finished cards (city names, km, minutes, episode types), so everything here may
 * be shared: no coordinates, no speeds, no driving evaluation (CLAUDE.md 규칙 6·7).
 */
data class YearRecap(
    val year: Int,
    val trips: Int,
    val km: Int,
    val drivingMin: Int,
    val days: Int,
    /** 1..12, the month with the most trips */
    val topMonth: Int,
    val topMonthTrips: Int,
    val timeOfDay: TimeOfDay,
    val timeOfDayTrips: Int,
    val topWeekday: DayOfWeek,
    val cities: Int,
    /** most visited destination cities, most first (max 3) */
    val topCities: List<Count>,
    val episodes: Int,
    /** episode types met most often, most first (max 3); name = display name from the card */
    val topEpisodes: List<Count>,
    val jamMin: Int,
    val rainTrips: Int,
    val lostLoops: Int,
    val withCompany: Int,
    val maxPassengers: Int,
    val longest: Highlight?,
    /** the card of the year: a grand award first, then the highest episode score */
    val best: Highlight?,
    val routineTrips: Int,
    val sample: Boolean = false,
) {
    data class Count(val key: String, val name: String, val n: Int)
    data class Highlight(val tripId: String?, val title: String, val fromCity: String, val toCity: String, val km: Int, val dateLabel: String)
    enum class TimeOfDay { MORNING, DAY, EVENING, NIGHT }

    /** km around the earth, in percent (40,075 km). */
    val earthPercent: Int get() = (km * 100 / EARTH_KM).toInt()
    /** Seoul–Busan one way is about 400 km by road. */
    val seoulBusan: Int get() = km / SEOUL_BUSAN_KM

    companion object {
        const val EARTH_KM = 40_075.0
        const val SEOUL_BUSAN_KM = 400

        /**
         * A made-up year for testers who have not driven enough yet (shown with a '샘플' mark,
         * like the dimmed sample card on home).
         */
        fun sample(year: Int) = YearRecap(
            year = year, trips = 214, km = 6_480, drivingMin = 9_870, days = 151,
            topMonth = 8, topMonthTrips = 31, timeOfDay = TimeOfDay.EVENING, timeOfDayTrips = 88, topWeekday = DayOfWeek.SATURDAY,
            cities = 23, topCities = listOf(Count("성남시", "성남시", 61), Count("서울특별시", "서울특별시", 44), Count("강릉시", "강릉시", 6)),
            episodes = 342, topEpisodes = listOf(Count("JAM_ENDURED", "정체", 97), Count("TUNNEL_PARADE", "터널 행진", 41), Count("SUDDEN_RAIN", "소나기", 18)),
            jamMin = 2_310, rainTrips = 18, lostLoops = 7, withCompany = 52, maxPassengers = 4,
            longest = Highlight(null, "바다로 새 버린 주말", "성남시", "강릉시", 238, "08.15 금"),
            best = Highlight(null, "바다로 새 버린 주말", "성남시", "강릉시", 238, "08.15 금"),
            routineTrips = 131, sample = true,
        )
    }
}

class YearRecapBuilder(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {

    /** The year a recap is for at [now]: this year in December and later, last year in January. */
    fun recapYear(now: Long): Int {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (d.monthValue == 1) d.year - 1 else d.year
    }

    /** Years that have at least one finished card, newest first. */
    fun years(items: List<Pair<Card, TripEntity?>>): List<Int> = items.map { yearOf(it) }.distinct().sortedDescending()

    /**
     * @param items finished cards with their trips (the archive). Trips started in [year] (KST) count.
     * @return null when fewer than [minTrips] trips.
     */
    fun build(year: Int, items: List<Pair<Card, TripEntity?>>, minTrips: Int): YearRecap? {
        val mine = items.filter { yearOf(it) == year }
        if (mine.size < minTrips || mine.isEmpty()) return null
        val times = mine.map { Instant.ofEpochMilli(startOf(it)).atZone(zone) }
        val cards = mine.map { it.first }

        val byMonth = times.groupingBy { it.monthValue }.eachCount()
        val topMonth = byMonth.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { -it.key })
        val tod = times.groupingBy { timeOfDay(it.hour) }.eachCount()
        val topTod = tod.entries.maxWith(compareBy<Map.Entry<YearRecap.TimeOfDay, Int>> { it.value }.thenBy { -it.key.ordinal })
        val weekday = times.groupingBy { it.dayOfWeek }.eachCount().entries.maxWith(compareBy<Map.Entry<DayOfWeek, Int>> { it.value }.thenBy { -it.key.value }).key

        val cities = cards.flatMap { listOf(it.route.fromCity, it.route.toCity) }.filter { it.isNotBlank() }.toSet()
        val topCities = cards.map { it.route.toCity }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3)
            .map { YearRecap.Count(it.key, it.key, it.value) }

        val eps = cards.flatMap { it.visibleEpisodes }
        val names = eps.associate { it.type to it.name.ifBlank { it.type } }
        val topEpisodes = eps.groupingBy { it.type }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(3)
            .map { YearRecap.Count(it.key, names[it.key] ?: it.key, it.value) }
        val jamMin = eps.filter { it.type == "JAM_ENDURED" }.sumOf { it.params["minutes"]?.toIntOrNull() ?: 0 }

        val longest = mine.maxWithOrNull(compareBy<Pair<Card, TripEntity?>> { it.first.route.distanceKm }.thenBy { -startOf(it) })
            ?.takeIf { it.first.route.distanceKm > 0 }?.let(::highlight)
        val best = mine.filter { !it.first.routine && it.first.visibleEpisodes.isNotEmpty() }
            .maxWithOrNull(compareBy<Pair<Card, TripEntity?>> { if (it.first.grand != null) 1 else 0 }
                .thenBy { p -> p.first.visibleEpisodes.sumOf { it.score } }.thenBy { -startOf(it) })
            ?.let(::highlight)

        return YearRecap(
            year = year,
            trips = mine.size,
            km = cards.sumOf { it.route.distanceKm },
            drivingMin = cards.sumOf { it.route.durationMin },
            days = times.map { it.toLocalDate() }.toSet().size,
            topMonth = topMonth.key, topMonthTrips = topMonth.value,
            timeOfDay = topTod.key, timeOfDayTrips = topTod.value,
            topWeekday = weekday,
            cities = cities.size, topCities = topCities,
            episodes = eps.size, topEpisodes = topEpisodes,
            jamMin = jamMin,
            rainTrips = cards.count { c -> c.visibleEpisodes.any { it.type == "SUDDEN_RAIN" } },
            lostLoops = eps.count { it.type == "LOST_LOOP" },
            withCompany = cards.count { it.passengers > 1 },
            maxPassengers = cards.maxOf { it.passengers },
            longest = longest, best = best,
            routineTrips = cards.count { it.routine },
        )
    }

    private fun highlight(p: Pair<Card, TripEntity?>): YearRecap.Highlight {
        val c = p.first
        val label = c.dateLabel.let { if (it.length > 5) it.substring(5) else it }
        return YearRecap.Highlight(c.tripId, c.title, c.route.fromCity, c.route.toCity, c.route.distanceKm, label)
    }

    private fun startOf(p: Pair<Card, TripEntity?>): Long = p.second?.startedAt
        ?: runCatching { OffsetDateTime.parse(p.first.createdAt).toInstant().toEpochMilli() }.getOrDefault(0L)

    private fun yearOf(p: Pair<Card, TripEntity?>): Int = Instant.ofEpochMilli(startOf(p)).atZone(zone).year

    private fun timeOfDay(hour: Int) = when (hour) {
        in 5..10 -> YearRecap.TimeOfDay.MORNING
        in 11..16 -> YearRecap.TimeOfDay.DAY
        in 17..20 -> YearRecap.TimeOfDay.EVENING
        else -> YearRecap.TimeOfDay.NIGHT
    }
}
