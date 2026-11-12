package ai.carbuddy.card

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Card JSON shared by the car and the server (SPEC 11장).
 * Never add coordinates or trajectories here (CLAUDE.md 규칙 6). `back` is driver-only.
 * Fields beyond SPEC 11 (labels, overrides) are display helpers so screens need no recomputation.
 */
@Serializable
data class Card(
    val cardId: String,
    val tripId: String,
    val version: Int = 1,
    val createdAt: String,
    val title: String,
    val route: Route,
    val buddy: BuddyInfo = BuddyInfo(),
    val episodes: List<CardEpisode> = emptyList(),
    val receipt: Receipt,
    val awards: List<Award> = emptyList(),
    val ambient: String = "WHITE",
    val attachments: Attachments = Attachments(),
    val back: Back = Back(),
    /** recording gaps, times only (SPEC 11장) */
    val gaps: List<Gap> = emptyList(),
    /** trimmed, normalised route shape; never coordinates (SPEC 10.1) */
    val routeShape: RouteShape? = null,
    /** routine trip (SPEC 6.4): shown as a small card, never announced */
    val routine: Boolean = false,
    // display helpers
    val dateLabel: String = "",
    val tripNo: Int = 0,
    val passengers: Int = 1,
    val titleType: String? = null,
    val titleCandidates: List<String> = emptyList(),
    val buddyLine: String? = null,
    val overrides: Overrides = Overrides(),
) {
    val visibleEpisodes: List<CardEpisode> get() = episodes.filterNot { it.hidden }
    val grand: Award? get() = awards.firstOrNull { it.grand }

    /** Copy for sharing / passengers: no driver-only back side. */
    fun forShare(): Card = copy(back = Back(), episodes = visibleEpisodes)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        fun fromJson(s: String): Card = JSON.decodeFromString(serializer(), s)
    }
}

@Serializable data class Route(val fromCity: String, val toCity: String, val distanceKm: Int, val durationMin: Int)

/** The buddy as it was on this trip; [grewTo]/[newItems] = what this trip changed (확장 A). */
@Serializable data class BuddyInfo(
    val stage: String = "EGG",
    val item: String? = null,
    val grewTo: String? = null,
    val newItems: List<String> = emptyList(),
)

@Serializable
data class CardEpisode(
    val id: String,
    val type: String,
    val startAt: String,
    val text: String,
    val hidden: Boolean = false,
    val star: Boolean = false,
    val clipId: String? = null,
    // display helpers
    val time: String = "",
    val name: String = "",
    val short: String = "",
    val buddyComment: String = "",
    val score: Double = 0.0,
    /** Template variables (counts, minutes, temperatures, place words). Never coordinates. */
    val params: Map<String, String> = emptyMap(),
)

@Serializable data class ReceiptLine(val label: String, val min: Int, val detail: String? = null, val type: String? = null)

@Serializable
data class ReceiptSummary(
    val distanceKm: Int = 0,
    val drivingMin: Int = 0,
    val restMin: Int = 0,
    val participants: Int = 1,
    val episodeCount: Int = 0,
    /** vehicle tallies when there are no episodes ("터널 4개", "창문 연 시간 12분") */
    val tallies: List<String> = emptyList(),
)

/** SPEC 8장 여정 영수증: the header ('여정 내역') never changes; lines, total and stamp depend on [mode]. */
@Serializable
data class Receipt(
    val mode: String = "LATE",       // LATE / ON_TIME / EARLY / NO_DEST
    val stamp: String? = null,       // 지각 / 정시 도착 / 일찍 도착 (none for NO_DEST)
    val baseEtaAt: String? = null,
    val arrivedAt: String? = null,
    val diffMin: Int? = null,
    val summary: ReceiptSummary = ReceiptSummary(),
    val lines: List<ReceiptLine> = emptyList(),
    /** LATE/ON_TIME/EARLY: diffMin; NO_DEST: total minutes */
    val totalMin: Int = 0,
    val totalLabel: String = "",
    val buddyLineKey: String? = null,
    val buddyLine: String? = null,
    val gapNote: String? = null,
    val etaLabel: String? = null,    // "13:10"
    val arrivedLabel: String? = null,
)

@Serializable data class Award(val type: String, val name: String, val votes: Int = 0, val grand: Boolean = false, val desc: String = "")

@Serializable data class Photo(val photoId: String, val by: String, val episodeId: String? = null, val tag: String? = null)
@Serializable data class Clip(val clipId: String, val by: String, val caption: String)
@Serializable data class DriverMoment(val t: String, val note: String? = null)
@Serializable data class Attachments(val photos: List<Photo> = emptyList(), val clips: List<Clip> = emptyList(), val driverMoments: List<DriverMoment> = emptyList())

@Serializable
data class Back(
    val windowsOpen: List<String> = emptyList(),
    val trunkOpen: Boolean = false,
    val lightsOn: Boolean = false,
    val parkingMemo: String? = null,
    val fuelLow: Boolean = false,
    val parkingPrompt: Boolean = false,
)

@Serializable data class Gap(val from: String, val to: String, val fromLabel: String = "", val toLabel: String = "")

/** SPEC 10.1 / 11장 routeShape. Points are 0..1 (x east, y down = north up), no lat/lng, no scale. */
@Serializable
data class RouteShape(
    val v: Int = 1,
    val trimmed: Boolean = true,
    /** height / width of the shape's bounding box */
    val aspect: Double = 1.0,
    val points: List<List<Double>> = emptyList(),
    val segments: List<RouteSegment> = emptyList(),
    val pins: List<RoutePin> = emptyList(),
    val insets: List<RouteInset> = emptyList(),
    /** indices (into points) where a recording gap starts: the next segment is drawn dotted */
    val gapsAfter: List<Int> = emptyList(),
)

@Serializable data class RouteSegment(val from: Int, val to: Int, val kind: String)
@Serializable data class RoutePin(val i: Int, val episodeId: String, val label: String, val type: String = "")
@Serializable data class RouteInset(val episodeId: String, val points: List<List<Double>>, val at: Int = 0, val label: String = "")

/** Choices the driver/passengers made that survive recomposition. */
@Serializable
data class Overrides(
    val title: String? = null,
    val votes: Map<String, Int> = emptyMap(),   // award type → votes
    val grandType: String? = null,
    val voteClosed: Boolean = false,
)
