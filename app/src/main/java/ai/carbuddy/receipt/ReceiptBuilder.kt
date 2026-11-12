package ai.carbuddy.receipt

import ai.carbuddy.card.Receipt
import ai.carbuddy.card.ReceiptLine
import ai.carbuddy.card.ReceiptSummary
import ai.carbuddy.core.model.EndReason
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.TripEntity
import ai.carbuddy.data.TripGapEntity
import ai.carbuddy.data.WaypointStopEntity
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.story.CopyBook
import ai.carbuddy.story.StoryEpisode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ReceiptMode { LATE, ON_TIME, EARLY, NO_DEST }

/**
 * 여정 영수증 (SPEC 8장). The header ('여정 내역') is the same whatever happened; the time
 * settlement, total line and stamp depend on the mode.
 *
 * Safety rules (enforced by tests): no awards, scores or speeds on EARLY/ON_TIME; time saved is
 * always "덜 막힌 구간" (thanks to the road), never the driver.
 */
class ReceiptBuilder(
    private val copy: CopyBook,
    private val config: DetectorConfig,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun build(
        trip: TripEntity,
        episodes: List<StoryEpisode>,
        stops: List<WaypointStopEntity> = emptyList(),
        gaps: List<TripGapEntity> = emptyList(),
    ): Receipt {
        val summary = summary(trip, episodes)
        val gapMin = gaps.sumOf { it.toT - it.fromT } / 60_000.0
        val gapNote = if (gapMin >= 1) copy.get("receipt_gapNote", "n" to gapMin.roundToInt()) else null
        val eta = trip.baseEtaAt
        val arrived = trip.arrivedAt
        val noDest = eta == null || arrived == null || !trip.arrivalKnown || trip.endReason == EndReason.MAX_GAP.name
        if (noDest) return timeReceipt(trip, episodes, summary, gapNote, stops)

        val diff = ((arrived!! - eta!!) / 60_000.0).roundToInt()
        val plannedMin = (eta - (trip.destSetAt ?: trip.startedAt)) / 60_000.0
        val tol = if (plannedMin < config.shortTripMin) config.onTimeTolShortMin else config.onTimeTolMin
        val mode = when {
            abs(diff) <= tol -> ReceiptMode.ON_TIME
            diff > tol -> ReceiptMode.LATE
            else -> ReceiptMode.EARLY
        }
        val settled = (lostLines(episodes, trip.destSetAt) + stopLines(stops, trip.destSetAt))
            .sortedWith(compareByDescending<ReceiptLine> { it.min })
        val residual = diff - settled.sumOf { it.min }
        val lines = when {
            residual < 0 -> settled + ReceiptLine(copy.get("receipt_line_lessJam"), residual, copy.get("receipt_line_lessJam"), "LESS_JAM")
            residual > 0 -> settled + ReceiptLine(copy.get("receipt_line_other"), residual, copy.get("receipt_line_other"), "OTHER")
            else -> settled
        }
        val (totalKey, stampKey, buddyKey) = when (mode) {
            ReceiptMode.LATE -> Triple("receipt_total_late", "receipt_stamp_late", "receipt_buddy_late")
            ReceiptMode.ON_TIME -> if (lines.isEmpty()) Triple("receipt_total_ontimePlain", "receipt_stamp_ontime", "receipt_buddy_ontimePlain")
                else Triple("receipt_total_ontime", "receipt_stamp_ontime", "receipt_buddy_ontime")
            ReceiptMode.EARLY -> Triple("receipt_total_early", "receipt_stamp_early", "receipt_buddy_early")
            ReceiptMode.NO_DEST -> error("unreachable")
        }
        return Receipt(
            mode = mode.name, stamp = copy.get(stampKey),
            baseEtaAt = fmt(eta), arrivedAt = fmt(arrived), diffMin = diff, summary = summary,
            lines = lines, totalMin = diff,
            totalLabel = copy.get(totalKey, "n" to abs(diff)),
            buddyLineKey = buddyKey.replace('_', '.'), buddyLine = copy.get(buddyKey, "n" to abs(diff)),
            gapNote = gapNote, etaLabel = label(eta), arrivedLabel = label(arrived),
        )
    }

    /** Episode lines. DETOUR excludes loop/jam time spent inside it (no double count). */
    private fun lostLines(episodes: List<StoryEpisode>, destSetAt: Long?): List<ReceiptLine> {
        val withLost = episodes.filter { (it.lostMinutes ?: 0) > 0 && it.type in LINE_TYPES && (destSetAt == null || it.startT >= destSetAt) }
            .sortedBy { it.startT }
        return withLost.mapNotNull { e ->
            var min = e.lostMinutes ?: 0
            if (e.type == EpisodeType.DETOUR) {
                val inside = withLost.filter { it.type == EpisodeType.LOST_LOOP || it.type == EpisodeType.JAM_ENDURED || it.type == EpisodeType.PARKING_HUNT }
                    .sumOf { maxOf(0L, minOf(e.endT, it.endT) - maxOf(e.startT, it.startT)) }
                min = maxOf(0, min - (inside / 60_000.0).roundToInt())
            }
            if (min <= 0) return@mapNotNull null
            val suffix = if (e.type == EpisodeType.DETOUR && e.params["place"] == null) "_noname" else ""
            ReceiptLine(
                label = copy.get("receipt_label_${e.type.name}$suffix", e.params),
                min = min,
                detail = copy.get("receipt_detail_${e.type.name}$suffix", e.params),
                type = e.type.name,
            )
        }
    }

    /** '들른 곳' lines: waypoint stops and resumed provisional stops (SPEC 5.5, 5.6). */
    private fun stopLines(stops: List<WaypointStopEntity>, destSetAt: Long?): List<ReceiptLine> =
        stops.filter { destSetAt == null || it.fromT >= destSetAt }.mapNotNull { s ->
            val min = ((s.toT - s.fromT) / 60_000.0).roundToInt()
            if (min <= 0) null else {
                val label = copy.get("receipt_line_waypoint", "name" to (s.name ?: "")).trim()
                ReceiptLine(label, min, label, "WAYPOINT")
            }
        }

    private fun summary(trip: TripEntity, episodes: List<StoryEpisode>): ReceiptSummary {
        val end = trip.arrivedAt ?: trip.endedAt ?: trip.lastSignalAt ?: trip.startedAt
        val totalMin = ((end - trip.startedAt) / 60_000.0).roundToInt()
        val restMin = (trip.pausedTotalMs / 60_000.0).roundToInt()
        // "에피소드 n개" = what the card shows (at most maxEpisodesOnCard)
        val visible = minOf(episodes.count { !it.hidden }, config.maxEpisodesOnCard)
        val tallies = if (visible > 0) emptyList() else listOfNotNull(
            trip.tunnelCount.takeIf { it > 0 }?.let { copy.get("receipt_tally_tunnel", "n" to it) },
            (trip.windowOpenMs / 60_000).toInt().takeIf { it > 0 }?.let { copy.get("receipt_tally_window", "n" to it) },
            (trip.wiperOnMs / 60_000).toInt().takeIf { it > 0 }?.let { copy.get("receipt_tally_wiper", "n" to it) },
        ).take(3)
        return ReceiptSummary(trip.distanceKm.roundToInt(), totalMin - restMin, restMin, trip.maxPassengers, visible, tallies)
    }

    /** No destination (or arrival unknown): driving / resting / lost time, no stamp. */
    private fun timeReceipt(trip: TripEntity, episodes: List<StoryEpisode>, summary: ReceiptSummary, gapNote: String?, stops: List<WaypointStopEntity>): Receipt {
        val end = trip.arrivedAt ?: trip.endedAt ?: trip.lastSignalAt ?: trip.startedAt
        val totalMin = ((end - trip.startedAt) / 60_000.0).roundToInt()
        val lostMin = episodes.filter { it.type == EpisodeType.LOST_LOOP || it.type == EpisodeType.PARKING_HUNT }.sumOf { it.lostMinutes ?: 0 }
        val stopLines = stopLines(stops, null)
        val otherRest = summary.restMin - stopLines.sumOf { it.min }
        val lines = buildList {
            add(ReceiptLine(copy.get("receipt_time_drive"), summary.drivingMin))
            addAll(stopLines)
            if (otherRest > 0) add(ReceiptLine(copy.get("receipt_time_rest"), otherRest))
            if (lostMin > 0) add(ReceiptLine(copy.get("receipt_time_lost"), lostMin))
        }
        return Receipt(
            mode = ReceiptMode.NO_DEST.name, stamp = null, baseEtaAt = null, arrivedAt = fmt(end), diffMin = null,
            summary = summary, lines = lines, totalMin = totalMin,
            totalLabel = copy.get("receipt_total_nodest", "n" to totalMin),
            buddyLineKey = "receipt.buddy.nodest", buddyLine = copy.get("receipt_buddy_nodest"),
            gapNote = gapNote, arrivedLabel = label(end),
        )
    }

    private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().format(iso)
    private fun label(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(hm)

    private companion object {
        val LINE_TYPES = setOf(EpisodeType.JAM_ENDURED, EpisodeType.REST_STOP, EpisodeType.DETOUR, EpisodeType.LOST_LOOP, EpisodeType.PARKING_HUNT)
    }
}
