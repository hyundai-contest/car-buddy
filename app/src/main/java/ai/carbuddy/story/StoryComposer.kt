package ai.carbuddy.story

import ai.carbuddy.card.Award
import ai.carbuddy.card.Back
import ai.carbuddy.card.BuddyInfo
import ai.carbuddy.card.Card
import ai.carbuddy.card.CardEpisode
import ai.carbuddy.card.Overrides
import ai.carbuddy.card.Gap
import ai.carbuddy.data.TrackPointEntity
import ai.carbuddy.data.TripGapEntity
import ai.carbuddy.data.WaypointStopEntity
import ai.carbuddy.route.RouteMark
import ai.carbuddy.route.RouteShaper
import ai.carbuddy.card.Route
import ai.carbuddy.core.geo.City
import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.model.AmbientColor
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.util.Korean
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.data.TripEntity
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.episode.Params
import ai.carbuddy.receipt.ReceiptBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/** Everything besides episodes that shapes a card: '들른 곳' stops, gaps and raw route points (local only). */
data class StoryExtras(
    val stops: List<WaypointStopEntity> = emptyList(),
    val gaps: List<TripGapEntity> = emptyList(),
    val track: List<TrackPointEntity> = emptyList(),
    val routeTrim: Boolean = true,
)

/** Per-episode sentences from the CopyBook (SPEC 7장 표, UX_SPEC 4장). */
class EpisodeTexts(private val copy: CopyBook) {
    fun vars(e: StoryEpisode, base: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val v = base.toMutableMap()
        v.putAll(e.params)
        (e.params["count"] as? Number)?.let { v["countWord"] = Korean.countWord(it.toInt()) }
        (e.params["cityName"] as? String)?.let { v["city"] = it }
        return v
    }

    fun name(t: EpisodeType) = copy.get("name_${t.name}")

    fun timeline(e: StoryEpisode): String {
        val v = vars(e)
        return when (e.type) {
            EpisodeType.DETOUR -> when {
                e.params["toName"] != null && e.params["fromName"] != null -> copy.get("tl_DETOUR", v)
                e.params["variant"] == "stop" -> copy.get("tl_DETOUR_stop", v)
                else -> copy.get("tl_DETOUR_waypoint", v)
            }
            EpisodeType.SUDDEN_RAIN -> copy.get("tl_SUDDEN_RAIN", v) +
                if (e.params["windowAfter"] == true) " " + copy.get("tl_SUDDEN_RAIN_window") else ""
            else -> copy.first(listOf("tl_${e.type.name}", "name_${e.type.name}"), v)
        }
    }

    fun short(e: StoryEpisode): String {
        val v = vars(e)
        return when (e.type) {
            EpisodeType.DETOUR -> copy.get(if (e.params["fromName"] != null && e.params["place"] != null) "short_DETOUR" else "short_DETOUR_noname", v)
            else -> copy.first(listOf("short_${e.type.name}", "name_${e.type.name}"), v)
        }
    }

    fun buddy(e: StoryEpisode): String {
        val key = if (e.type == EpisodeType.SUDDEN_RAIN && e.params["windowAfter"] != true) "buddy_SUDDEN_RAIN_dry" else "buddy_${e.type.name}"
        return if (copy.has(key)) copy.get(key, vars(e)) else ""
    }

    fun award(e: StoryEpisode): String = copy.get("award_${e.type.name}")
    fun awardDesc(e: StoryEpisode): String = if (copy.has("award_desc_${e.type.name}")) copy.get("award_desc_${e.type.name}", vars(e)) else ""
    fun hasAward(t: EpisodeType) = copy.has("award_${t.name}")
}

/** SPEC 7장 title rule: the top-scoring type's template, else the route summary. */
class TitleGenerator(private val copy: CopyBook, private val texts: EpisodeTexts) {
    fun forEpisode(e: StoryEpisode, base: Map<String, Any?>): String? =
        "title_${e.type.name}".takeIf { copy.has(it) }?.let { copy.get(it, texts.vars(e, base)) }

    fun route(base: Map<String, Any?>): String {
        val to = base["toCity"]?.toString()?.takeIf { it.isNotBlank() }
        val km = (base["km"] as? Number)?.toInt() ?: 0
        val key = when {
            to == null && km < 1 -> "title_route_tiny"          // no place known and barely moved ("까지 0km" before)
            to == null -> "title_route_noplace"
            base["fromCity"] != null && base["fromCity"] != base["toCity"] -> "title_route"
            else -> "title_route_short"
        }
        return copy.get(key, base)
    }
}

/** Awards (SPEC 7장 시상식): top types by score, max 3. */
object AwardBook {
    /**
     * The title type is left out when there are ≥ 3 awardable types so the honours spread across
     * the card (card mockup: title "바다로 새 버린 주말" → 길치상·온도전쟁상·우중 드라이버상).
     */
    fun pick(ranked: List<StoryEpisode>, titleType: EpisodeType?, max: Int, awardable: (EpisodeType) -> Boolean): List<StoryEpisode> {
        val pool = ranked.filter { awardable(it.type) }
        val trimmed = if (pool.size >= 3 && titleType != null) pool.filter { it.type != titleType } else pool
        return trimmed.take(max)
    }
}

/**
 * Builds the card (SPEC 7·8·11장) from the trip's episodes. Pure: same inputs → same card,
 * so it can be recomposed whenever an episode is hidden or a vote arrives.
 */
class StoryComposer(
    private val copy: CopyBook,
    private val cities: CityResolver,
    private val config: DetectorConfig,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    private val texts = EpisodeTexts(copy)
    private val titles = TitleGenerator(copy, texts)
    private val receipts = ReceiptBuilder(copy, config, zone)
    private val shaper = RouteShaper(config)
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val date = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun texts() = texts

    fun compose(
        trip: TripEntity,
        episodes: List<EpisodeEntity>,
        history: Map<EpisodeType, Int>,
        previous: Card? = null,
        buddy: BuddyInfo = BuddyInfo(),
        back: Back = previous?.back ?: Back(),
        tripNo: Int = 0,
        now: Long = trip.arrivedAt ?: trip.endedAt ?: trip.lastSignalAt ?: trip.startedAt,
        extras: StoryExtras = StoryExtras(),
    ): Card {
        val merged = Scorer.merge(episodes, history)
        val visible = merged.filterNot { it.hidden }.sortedWith(Scorer.byScore)
        val selected = visible.take(config.maxEpisodesOnCard)
        val overrides = previous?.overrides ?: Overrides()

        val from = city(trip.startLat, trip.startLng)
        val to = city(trip.lastLat ?: trip.destLat, trip.lastLng ?: trip.destLng)
        val endT = trip.arrivedAt ?: trip.endedAt ?: trip.lastSignalAt ?: trip.startedAt
        val km = trip.distanceKm.roundToInt()
        val startZ = Instant.ofEpochMilli(trip.startedAt).atZone(zone)
        val base = mapOf<String, Any?>(
            "dayWord" to Korean.dayWord(startZ.dayOfWeek.value),
            "fromCity" to (from?.short ?: ""), "toCity" to (to?.short ?: ""),
            "city" to (to?.short ?: from?.short ?: ""), "km" to km,
        )

        // title
        val top = selected.firstOrNull()
        val autoTitle = top?.let { titles.forEpisode(it, base) } ?: titles.route(base)
        val candidates = (selected.take(3).mapNotNull { titles.forEpisode(it, base) } + titles.route(base)).distinct()
        val title = overrides.title?.takeIf { it in candidates } ?: autoTitle

        // awards + grand prize
        val awarded = AwardBook.pick(selected, top?.type, config.maxAwards) { texts.hasAward(it) }
        val votes = overrides.votes
        val grandType = overrides.grandType
            ?: awarded.filter { (votes[it.type.name] ?: 0) > 0 }
                .sortedWith(compareByDescending<StoryEpisode> { votes[it.type.name] ?: 0 }.then(Scorer.byScore))
                .firstOrNull()?.type?.name
            ?: awarded.firstOrNull()?.type?.name   // nobody voted → buddy pick (highest score)
        val awards = awarded.map { Award(it.type.name, texts.award(it), votes[it.type.name] ?: 0, it.type.name == grandType, texts.awardDesc(it)) }
            .sortedWith(compareByDescending<Award> { it.grand }.thenByDescending { it.votes })
        val grand = selected.firstOrNull { it.type.name == grandType }

        // timeline (selected in time order, hidden ones kept for un-hiding)
        val cardEps = (selected.sortedBy { it.startT }.map { toCardEpisode(it, it.type.name == grandType) } +
            merged.filter { it.hidden }.sortedBy { it.startT }.map { toCardEpisode(it, false) })

        val receipt = receipts.build(trip, merged.filterNot { it.hidden }, extras.stops, extras.gaps)
        val marks = selected.map { RouteMark(it.id, it.type, it.startT, it.endT, texts.short(it), it.score) }
        val routeShape = runCatching { shaper.shape(extras.track, marks, extras.gaps, trim = extras.routeTrim) }.getOrNull()
        val gaps = extras.gaps.map {
            Gap(fmt(it.fromT), fmt(it.toT), Instant.ofEpochMilli(it.fromT).atZone(zone).format(hm), Instant.ofEpochMilli(it.toT).atZone(zone).format(hm))
        }
        val ambient = listOfNotNull(grand?.type?.ambient, top?.type?.ambient).firstOrNull { it != AmbientColor.WHITE } ?: AmbientColor.WHITE

        return Card(
            cardId = previous?.cardId ?: UUID.nameUUIDFromBytes("card:${trip.id}".toByteArray()).toString(),
            tripId = trip.id,
            createdAt = previous?.createdAt ?: Instant.ofEpochMilli(now).atZone(zone).toOffsetDateTime().format(iso),
            title = title,
            route = Route(from?.full ?: "", to?.full ?: "", km, ((endT - trip.startedAt) / 60_000.0).roundToInt()),
            buddy = buddy,
            episodes = cardEps,
            receipt = receipt,
            awards = awards,
            ambient = ambient.name,
            attachments = previous?.attachments ?: ai.carbuddy.card.Attachments(),
            back = back,
            gaps = gaps,
            routeShape = routeShape,
            routine = trip.routine,
            dateLabel = "${startZ.format(date)} ${Korean.dayShort(startZ.dayOfWeek.value)}",
            tripNo = tripNo,
            passengers = trip.maxPassengers,
            titleType = top?.type?.name,
            titleCandidates = candidates,
            buddyLine = grand?.let { texts.buddy(it) }?.ifEmpty { null } ?: if (selected.isEmpty()) copy.get("card_zero") else null,
            overrides = overrides,
        )
    }

    private fun toCardEpisode(e: StoryEpisode, star: Boolean) = CardEpisode(
        id = e.id, type = e.type.name,
        startAt = Instant.ofEpochMilli(e.startT).atZone(zone).toOffsetDateTime().format(iso),
        text = texts.timeline(e), hidden = e.hidden, star = star,
        time = Instant.ofEpochMilli(e.startT).atZone(zone).format(hm),
        name = texts.name(e.type), short = texts.short(e), buddyComment = texts.buddy(e), score = e.score,
        params = e.params.mapValues { (_, v) -> CopyBook.formatValue(v) },
    )

    private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toOffsetDateTime().format(iso)

    private fun city(lat: Double?, lng: Double?): City? = if (lat != null && lng != null) cities.resolve(lat, lng) else null

    companion object {
        fun paramsOf(e: EpisodeEntity) = Params.decode(e.paramsJson)
    }
}
