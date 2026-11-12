package ai.carbuddy.ceremony

import ai.carbuddy.card.Card
import ai.carbuddy.data.CardEntity
import ai.carbuddy.data.NotifyLogEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.DetectorConfig
import java.time.Instant
import java.time.ZoneId

enum class NotifyKind { N1, N4 }

/**
 * Exposure policy (SPEC 10.2, no daily cap: IMPLEMENTATION_NOTES): only special trips ask, a card
 * ignored notifyMaxIgnores times is only kept in the archive, routine trips never ask. The log is
 * kept for metrics.
 */
class NotificationBudget(private val store: TripStore, private val config: DetectorConfig, private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {

    private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString()

    fun usedToday(now: Long): Int = store.notifyLogs(day(now)).size

    /** N1: arrived + ≥2 episodes + ≥20 min + not routine. */
    fun n1Eligible(trip: TripEntity, card: Card, now: Long): Boolean {
        val minutes = ((trip.arrivedAt ?: now) - trip.startedAt) / 60_000
        return !trip.routine && card.visibleEpisodes.size >= config.n1MinEpisodes && minutes >= config.n1MinTripMin
    }

    /** N4: the newest special card not yet viewed and not ignored too often. */
    fun n4Candidate(now: Long): CardEntity? {
        return store.cards().firstOrNull { c ->
            c.status == "FINAL" && c.viewedAt == null && c.ignoredCount < config.notifyMaxIgnores && run {
                val trip = store.trip(c.tripId)
                val card = runCatching { Card.fromJson(c.json) }.getOrNull()
                trip != null && !trip.routine && card != null && card.visibleEpisodes.size >= config.n1MinEpisodes
            }
        }
    }

    fun record(kind: NotifyKind, cardId: String, now: Long) =
        store.addNotifyLog(NotifyLogEntity(date = day(now), kind = kind.name, cardId = cardId, t = now))

    /** [나중에], ignored, or cancelled by Gear D. */
    fun ignored(tripId: String) {
        store.cardForTrip(tripId)?.let { store.saveCard(it.copy(ignoredCount = it.ignoredCount + 1)) }
    }

    /** Reaching the receipt step counts as viewed (SPEC 10.2). */
    fun viewed(tripId: String, now: Long) {
        store.cardForTrip(tripId)?.takeIf { it.viewedAt == null }?.let { store.saveCard(it.copy(viewedAt = now)) }
    }
}
