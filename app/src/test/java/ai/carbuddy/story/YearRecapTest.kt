package ai.carbuddy.story

import ai.carbuddy.card.Award
import ai.carbuddy.card.Card
import ai.carbuddy.card.CardEpisode
import ai.carbuddy.card.Receipt
import ai.carbuddy.card.Route
import ai.carbuddy.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/** 연말 결산: sums finished cards of one KST year; nothing in it can locate the car. */
class YearRecapTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val b = YearRecapBuilder(zone)

    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private var n = 0
    private fun trip(
        t: Long, from: String = "성남시", to: String = "서울특별시", km: Int = 20, min: Int = 40,
        eps: List<Pair<String, Map<String, String>>> = emptyList(), passengers: Int = 1, routine: Boolean = false,
        grand: Boolean = false, hidden: Set<String> = emptySet(), title: String = "여정",
    ): Pair<Card, TripEntity> {
        val id = "t${n++}"
        val card = Card(
            cardId = "c$id", tripId = id, createdAt = "", title = title, route = Route(from, to, km, min), receipt = Receipt(),
            episodes = eps.mapIndexed { i, (type, p) -> CardEpisode("$id:$i", type, "", "", hidden = type in hidden, name = "이름_$type", score = 1.0, params = p) },
            passengers = passengers, routine = routine, dateLabel = "2026.01.01 목",
            awards = if (grand) listOf(Award("LOST_LOOP", "뺑뺑이상", grand = true)) else emptyList(),
        )
        return card to TripEntity(id = id, state = "ENDED", stateSince = t, startedAt = t, endedAt = t + min * 60_000L)
    }

    @Test fun fewerTripsThanTheMinimum_noRecap() {
        val items = listOf(trip(at(2026, 3, 1, 9)), trip(at(2026, 3, 2, 9)))
        assertNull(b.build(2026, items, minTrips = 3))
        assertNotNull(b.build(2026, items, minTrips = 2))                 // boundary: exactly the minimum
    }

    @Test fun onlyTripsStartedInThatKoreanYear_count() {
        val items = listOf(
            trip(at(2025, 12, 31, 23)),                                     // last year (KST)
            trip(at(2026, 1, 1, 0)), trip(at(2026, 6, 1, 12)), trip(at(2026, 12, 31, 23)),
            trip(at(2027, 1, 1, 0)),
        )
        val r = b.build(2026, items, 1)!!
        assertEquals(3, r.trips)
        assertEquals(listOf(2027, 2026, 2025), b.years(items))
    }

    @Test fun totals_days_months_timeOfDay_weekday() {
        val items = listOf(
            trip(at(2026, 8, 1, 18), km = 100, min = 90),                   // Sat evening
            trip(at(2026, 8, 1, 19), km = 50, min = 60),                    // same day
            trip(at(2026, 8, 8, 20), km = 30, min = 30),                    // Sat evening
            trip(at(2026, 3, 4, 8), km = 20, min = 25),                     // Wed morning
        )
        val r = b.build(2026, items, 1)!!
        assertEquals(200, r.km)
        assertEquals(205, r.drivingMin)
        assertEquals(3, r.days)
        assertEquals(8, r.topMonth); assertEquals(3, r.topMonthTrips)
        assertEquals(YearRecap.TimeOfDay.EVENING, r.timeOfDay)
        assertEquals(DayOfWeek.SATURDAY, r.topWeekday)
    }

    @Test fun cities_episodes_company_highlights() {
        val items = listOf(
            trip(at(2026, 5, 1, 9), "성남시", "강릉시", km = 240, eps = listOf("SUDDEN_RAIN" to emptyMap(), "LOST_LOOP" to emptyMap()), passengers = 4, title = "바다"),
            trip(at(2026, 5, 2, 9), "강릉시", "성남시", km = 230, eps = listOf("JAM_ENDURED" to mapOf("minutes" to "35")), passengers = 2),
            trip(at(2026, 5, 3, 9), "성남시", "서울특별시", eps = listOf("JAM_ENDURED" to mapOf("minutes" to "12"), "LOST_LOOP" to emptyMap()), grand = true, title = "대상"),
            trip(at(2026, 5, 4, 9), "성남시", "서울특별시", routine = true, eps = listOf("SUDDEN_RAIN" to emptyMap()), hidden = setOf("SUDDEN_RAIN")),
        )
        val r = b.build(2026, items, 1)!!
        assertEquals(3, r.cities)
        assertEquals("서울특별시", r.topCities.first().name); assertEquals(2, r.topCities.first().n)
        assertEquals(5, r.episodes)                                        // the hidden episode is left out
        assertEquals(47, r.jamMin)
        assertEquals(1, r.rainTrips)
        assertEquals(2, r.lostLoops)
        assertEquals(2, r.topEpisodes.first().n)
        assertEquals(2, r.withCompany); assertEquals(4, r.maxPassengers)
        assertEquals("바다", r.longest!!.title); assertEquals(240, r.longest!!.km)
        assertEquals("대상", r.best!!.title)                                // a grand award beats more episodes
        assertEquals(1, r.routineTrips)
    }

    @Test fun recapYear_isLastYearInJanuary() {
        assertEquals(2026, b.recapYear(at(2026, 12, 1, 9)))
        assertEquals(2026, b.recapYear(at(2027, 1, 31, 9)))
        assertEquals(2027, b.recapYear(at(2027, 2, 1, 9)))
    }

    @Test fun comparisons() {
        val r = YearRecap.sample(2026)
        assertTrue(r.sample)
        assertEquals(16, r.earthPercent)                                   // 6,480 / 40,075
        assertEquals(16, r.seoulBusan)
        assertFalse(YearRecap.sample(2026).copy(sample = false).sample)
    }
}
