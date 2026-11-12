package ai.carbuddy.story

import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.InMemoryTripStore
import ai.carbuddy.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 확장 A (기획안 v13): stages, items, news for the card, equip. Never regresses. */
class BuddyEngineTest {
    private val store = InMemoryTripStore()
    private val engine = BuddyEngine(store)
    private val noCities = CityResolver { _, _ -> null }

    private fun trip(n: Int, km: Double = 10.0) = TripEntity(id = "t$n", state = "ENDED", stateSince = 0, startedAt = n * 1000L, distanceKm = km)
    private fun ep(n: Int, type: EpisodeType, params: String = "{}") = EpisodeEntity("t$n:$type:k", "t$n", type.name, "k", 0, 1, params)

    @Test fun fifthTrip_growsToSprout_andSaysSo() {
        repeat(4) { assertNull(engine.onTripFinal(trip(it), emptyList(), noCities).grewTo) }
        val news = engine.onTripFinal(trip(5), emptyList(), noCities)
        assertEquals("SPROUT", news.grewTo)
        assertEquals("SPROUT", engine.current().stage)
        assertNull(engine.onTripFinal(trip(6), emptyList(), noCities).grewTo)          // no repeat
    }

    @Test fun thousandKm_isCompanionEvenWithFewTrips() {
        val news = engine.onTripFinal(trip(1, km = 1000.0), emptyList(), noCities)
        assertEquals("COMPANION", news.grewTo)
    }

    @Test fun thirdLoop_unlocksCompass_withProgressBefore() {
        engine.onTripFinal(trip(1), listOf(ep(1, EpisodeType.LOST_LOOP)), noCities)
        engine.onTripFinal(trip(2), listOf(ep(2, EpisodeType.LOST_LOOP)), noCities)
        val compass = engine.itemProgress().first { it.id == "COMPASS" }
        assertEquals(2, compass.now); assertEquals(3, compass.goal); assertTrue(!compass.have)
        val news = engine.onTripFinal(trip(3), listOf(ep(3, EpisodeType.LOST_LOOP)), noCities)
        assertEquals(listOf("COMPASS"), news.newItems)
        assertEquals("COMPASS", engine.current().equipped)                              // first item is worn
    }

    @Test fun cushion_countsJamHours() {
        engine.onTripFinal(trip(1), listOf(ep(1, EpisodeType.JAM_ENDURED, """{"minutes":100}""")), noCities)
        assertEquals(1, engine.itemProgress().first { it.id == "CUSHION" }.now)
        val news = engine.onTripFinal(trip(2), listOf(ep(2, EpisodeType.JAM_ENDURED, """{"minutes":85}""")), noCities)
        assertEquals(listOf("CUSHION"), news.newItems)
    }

    @Test fun equip_onlyOwnedItems_orNone() {
        engine.equip("STARLIGHT")
        assertNull(engine.current().equipped)
        repeat(2) { engine.onTripFinal(trip(it), listOf(ep(it, EpisodeType.SUDDEN_RAIN)), noCities) }
        engine.equip(null); assertNull(engine.current().equipped)
        engine.equip("RAINCOAT"); assertEquals("RAINCOAT", engine.current().equipped)
    }
}
