package ai.carbuddy.route

import ai.carbuddy.card.RouteInset
import ai.carbuddy.card.RoutePin
import ai.carbuddy.card.RouteSegment
import ai.carbuddy.card.RouteShape
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.util.Geo
import ai.carbuddy.data.TrackPointEntity
import ai.carbuddy.data.TripGapEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.DetectorConfig
import ai.carbuddy.signal.Location
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

/**
 * Collects route points for the active trip (SPEC 10.1): every trackIntervalSec or trackMinMoveM,
 * fixes worse than trackMaxAccuracyM dropped, nothing while paused or not recording.
 */
class RouteTracker(private val store: TripStore, private val config: DetectorConfig) {
    private var lastTrip: String? = null
    private var last: TrackPointEntity? = null

    fun onLocation(tripId: String, e: Location, moving: Boolean) {
        if (!moving) return
        val acc = e.accuracyM
        if (acc != null && acc > config.trackMaxAccuracyM) return
        if (lastTrip != tripId) { lastTrip = tripId; last = store.trackPoints(tripId).lastOrNull() }
        val prev = last
        if (prev != null) {
            val dt = e.t - prev.t
            if (dt <= 0) return
            val moved = Geo.distanceM(prev.lat, prev.lng, e.lat, e.lng)
            if (dt < config.trackIntervalSec * 1000L && moved < config.trackMinMoveM) return
        }
        val p = TrackPointEntity(tripId = tripId, t = e.t, lat = e.lat, lng = e.lng, accuracyM = acc)
        store.addTrackPoint(p)
        last = p
    }
}

/** An episode stretch to colour on the route (time window, kind, pin label). */
data class RouteMark(val episodeId: String, val type: EpisodeType, val startT: Long, val endT: Long, val label: String, val score: Double)

/**
 * Simplifies, trims and normalises a trip's points into [RouteShape] (SPEC 10.1). The result has
 * no latitude/longitude and no scale: it is safe to share.
 */
class RouteShaper(private val config: DetectorConfig) {

    private data class P(val x: Double, val y: Double, val t: Long)

    fun shape(
        points: List<TrackPointEntity>,
        marks: List<RouteMark>,
        gaps: List<TripGapEntity> = emptyList(),
        trim: Boolean = true,
        minKm: Double = config.routeMinKm,
        insets: Boolean = true,
    ): RouteShape? {
        if (points.size < 2) return null
        val km = points.zipWithNext().sumOf { (a, b) -> Geo.distanceM(a.lat, a.lng, b.lat, b.lng) } / 1000.0 +
            gaps.sumOf { it.straightKm }
        if (km < minKm) return null
        // 1) privacy trim around start and end
        val first = points.first()
        val lastPt = points.last()
        val kept = if (!trim) points else points.filter {
            Geo.distanceM(it.lat, it.lng, first.lat, first.lng) > config.routeTrimStartM &&
                Geo.distanceM(it.lat, it.lng, lastPt.lat, lastPt.lng) > config.routeTrimEndM
        }
        if (kept.size < 2) return null
        // 2) project (equirectangular) and simplify, keeping episode / gap boundaries
        val lat0 = kept.map { it.lat }.average()
        val k = cos(Math.toRadians(lat0))
        val proj = kept.map { P(it.lng * k * 111_320.0, it.lat * 111_320.0, it.t) }
        val forced = HashSet<Int>()
        fun nearestIdx(t: Long) = proj.indices.minByOrNull { kotlin.math.abs(proj[it].t - t) } ?: 0
        marks.forEach { forced += nearestIdx(it.startT); forced += nearestIdx(it.endT) }
        gaps.forEach { forced += nearestIdx(it.fromT); forced += nearestIdx(it.toT) }
        val simplified = simplify(proj, forced, config.routeMaxPoints)
        // 3) normalise: long side 0..1, north up
        val (norm, aspect) = normalise(simplified)
        fun idx(t: Long): Int = simplified.indices.minByOrNull { kotlin.math.abs(simplified[it].t - t) } ?: 0
        val segments = marks.mapNotNull { m ->
            val kind = kindOf(m.type) ?: return@mapNotNull null
            val inside = simplified.indices.filter { simplified[it].t in m.startT..m.endT }
            val from = inside.minOrNull() ?: idx(m.startT)
            val to = inside.maxOrNull() ?: idx(m.endT)
            RouteSegment(from, maxOf(to, from), kind)
        }.sortedBy { LAYER.indexOf(it.kind) }   // draw order: JAM first … LOOP last (on top)
        val pins = marks.sortedByDescending { it.score }.take(config.routeMaxPins).map { m ->
            val inside = simplified.indices.filter { simplified[it].t in m.startT..m.endT }
            val i = if (inside.isNotEmpty()) inside[inside.size / 2] else idx((m.startT + m.endT) / 2)
            RoutePin(i, m.episodeId, m.label, m.type.name)
        }.sortedBy { it.i }
        val gapsAfter = gaps.map { g -> simplified.indices.lastOrNull { simplified[it].t <= g.fromT } ?: 0 }
        val insetList = if (!insets) emptyList() else buildInsets(proj, marks, simplified, pins)
        return RouteShape(
            trimmed = trim, aspect = round2(aspect), points = norm, segments = segments, pins = pins,
            insets = insetList, gapsAfter = gapsAfter,
        )
    }

    /** A loop too small to see on a long route gets its own zoomed circle (SPEC 10.1 확대 원). */
    private fun buildInsets(all: List<P>, marks: List<RouteMark>, simplified: List<P>, pins: List<RoutePin>): List<RouteInset> {
        val diag = diagonal(simplified)
        val loop = marks.filter { it.type == EpisodeType.LOST_LOOP || it.type == EpisodeType.PARKING_HUNT }.maxByOrNull { it.score } ?: return emptyList()
        val part = all.filter { it.t in loop.startT..loop.endT }
        if (part.size < 4 || diagonal(part) >= diag * config.routeInsetRatio) return emptyList()
        val (norm, _) = normalise(simplify(part, emptySet(), config.routeInsetMaxPoints, ratio = 0.01))
        val at = pins.firstOrNull { it.episodeId == loop.episodeId }?.i ?: 0
        return listOf(RouteInset(loop.episodeId, norm, at, loop.label))
    }

    private fun diagonal(ps: List<P>): Double =
        hypot(ps.maxOf { it.x } - ps.minOf { it.x }, ps.maxOf { it.y } - ps.minOf { it.y })

    private fun normalise(ps: List<P>): Pair<List<List<Double>>, Double> {
        val minX = ps.minOf { it.x }; val maxX = ps.maxOf { it.x }
        val minY = ps.minOf { it.y }; val maxY = ps.maxOf { it.y }
        val w = maxX - minX; val h = maxY - minY
        val scale = max(w, h).takeIf { it > 0 } ?: 1.0
        return ps.map { listOf(round4((it.x - minX) / scale), round4((maxY - it.y) / scale)) } to (if (w > 0) h / w else 1.0)
    }

    /** Douglas–Peucker with forced keep points; tolerance grows until ≤ maxPoints. */
    private fun simplify(ps: List<P>, forced: Set<Int>, maxPoints: Int, ratio: Double = config.routeToleranceRatio): List<P> {
        if (ps.size <= 2) return ps
        var tol = diagonal(ps) * ratio
        var keep: BooleanArray
        while (true) {
            keep = BooleanArray(ps.size)
            val anchors = (forced + 0 + ps.lastIndex).filter { it in ps.indices }.sorted()
            anchors.forEach { keep[it] = true }
            for ((a, b) in anchors.zipWithNext()) dp(ps, a, b, tol, keep)
            if (keep.count { it } <= maxPoints || tol <= 0.0) break
            tol = if (tol == 0.0) 1.0 else tol * 1.5
        }
        return ps.filterIndexed { i, _ -> keep[i] }
    }

    private fun dp(ps: List<P>, a: Int, b: Int, tol: Double, keep: BooleanArray) {
        if (b <= a + 1) return
        var maxD = -1.0; var idx = -1
        val ax = ps[a].x; val ay = ps[a].y; val bx = ps[b].x; val by = ps[b].y
        val len = hypot(bx - ax, by - ay)
        for (i in a + 1 until b) {
            val d = if (len == 0.0) hypot(ps[i].x - ax, ps[i].y - ay)
            else kotlin.math.abs((by - ay) * ps[i].x - (bx - ax) * ps[i].y + bx * ay - by * ax) / len
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > tol && idx > 0) { keep[idx] = true; dp(ps, a, idx, tol, keep); dp(ps, idx, b, tol, keep) }
    }

    private fun round4(v: Double) = round(v * 10_000) / 10_000
    private fun round2(v: Double) = round(v * 100) / 100

    companion object {
        /** Colour kind per episode (SPEC 10.1 표). Speed never colours the line. */
        fun kindOf(t: EpisodeType): String? = when (t) {
            EpisodeType.JAM_ENDURED -> "JAM"
            EpisodeType.LOST_LOOP, EpisodeType.PARKING_HUNT -> "LOOP"
            EpisodeType.DETOUR -> "DETOUR"
            EpisodeType.SUDDEN_RAIN -> "RAIN"
            EpisodeType.TUNNEL_PARADE -> "TUNNEL"
            EpisodeType.WIND_RUN -> "WIND"
            else -> null
        }
        /** Overlap order, bottom → top: LOOP > RAIN·TUNNEL·WIND > DETOUR > JAM. */
        val LAYER = listOf("JAM", "DETOUR", "WIND", "TUNNEL", "RAIN", "LOOP")
    }
}
