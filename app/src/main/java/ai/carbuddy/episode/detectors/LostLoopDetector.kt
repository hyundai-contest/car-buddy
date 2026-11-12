package ai.carbuddy.episode.detectors

import ai.carbuddy.core.geo.CityResolver
import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.TripState
import ai.carbuddy.core.util.Geo
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.ReRouteEnded
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable

/**
 * LOST_LOOP (SPEC 6.2): (a) ≥ reRouteMin re-routes within loopWindowMin, or (b) passing within
 * loopRadiusM of a point visited loopExcludeMin..loopLookbackMin ago, loopRevisitMin times.
 * Overlapping / close evidence is merged into one loop.
 */
class LostLoopDetector(private val cities: CityResolver? = null) :
    StatefulDetector<LostLoopDetector.State>(State.serializer(), ::State) {

    @Serializable data class Pt(val t: Long, val lat: Double, val lng: Double)
    @Serializable data class Loop(val key: String, val start: Long, val end: Long, val count: Int, val lat: Double?, val lng: Double?)
    @Serializable data class State(
        val reroutes: List<Long> = emptyList(),
        val pts: List<Pt> = emptyList(),
        val revisits: List<Long> = emptyList(),
        val anchorT: Long? = null,
        val loop: Loop? = null,
    )

    override val type = EpisodeType.LOST_LOOP
    override val requires = emptySet<Capability>()   // works with NAVI or LOCATION

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        return when (e) {
            is ReRouteEnded -> {
                val window = cfg.loopWindowMin * 60_000L
                val rr = (s.reroutes + e.t).filter { e.t - it <= window }
                s = s.copy(reroutes = rr)
                if (rr.size >= cfg.reRouteMin) merge(rr.first(), e.t, rr.size, ctx) else emptyList()
            }
            is Location -> onLocation(e, ctx)
            else -> emptyList()
        }
    }

    private fun onLocation(e: Location, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        if (ctx.speedKmh < cfg.loopMinSpeedKmh) return emptyList()
        val lookback = cfg.loopLookbackMin * 60_000L
        val exclude = cfg.loopExcludeMin * 60_000L
        val pts = s.pts.filter { e.t - it.t <= lookback }
        var out = emptyList<EpisodeCandidate>()

        var matchT: Long? = null
        for ((i, p) in pts.withIndex()) {
            if (e.t - p.t < exclude) break
            if (Geo.distanceM(e.lat, e.lng, p.lat, p.lng) > cfg.loopRadiusM) continue
            // must have actually left that point since (filters stop-and-go and parking)
            val leftIt = (i + 1 until pts.size).any { j -> Geo.distanceM(pts[j].lat, pts[j].lng, p.lat, p.lng) >= cfg.loopMinAwayM }
            if (leftIt) { matchT = p.t; break }
        }
        if (matchT != null) {
            val cooledDown = s.revisits.lastOrNull()?.let { e.t - it >= exclude } ?: true
            val recent = s.revisits.filter { e.t - it <= lookback }
            if (cooledDown) {
                // the loop starts where the first revisit matched (the junction we keep coming back to)
                val anchor = if (recent.isEmpty()) matchT else (s.anchorT ?: matchT)
                val revisits = recent + e.t
                s = s.copy(revisits = revisits, anchorT = anchor)
                if (revisits.size >= cfg.loopRevisitMin) out = merge(anchor, e.t, revisits.size + 1, ctx)
            } else {
                // still driving over road we already covered: the loop goes on, but it is not a new lap
                val loop = s.loop
                if (loop != null && e.t - loop.end <= cfg.loopMergeGapMin * 60_000L && recent.size >= cfg.loopRevisitMin) {
                    out = merge(loop.start, e.t, loop.count, ctx)
                }
            }
        }
        s = s.copy(pts = pts + Pt(e.t, e.lat, e.lng))
        return out
    }

    private fun merge(start: Long, end: Long, count: Int, ctx: TripContext): List<EpisodeCandidate> {
        val gap = ctx.config.loopMergeGapMin * 60_000L
        val cur = s.loop
        val loc = ctx.lastLocation
        val loop = if (cur != null && start <= cur.end + gap) {
            cur.copy(start = minOf(cur.start, start), end = maxOf(cur.end, end), count = maxOf(cur.count, count))
        } else {
            Loop(key = start.toString(), start = start, end = end, count = count, lat = loc?.lat, lng = loc?.lng)
        }
        s = s.copy(loop = loop)
        return listOf(candidate(loop, parkingHunt(loop, ctx)))
    }

    /** No-destination trips: loops near where we finally parked were a parking hunt (SPEC 6.2). */
    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> {
        val loop = s.loop ?: return emptyList()
        if (ctx.dest != null) return emptyList()
        val end = ctx.lastLocation ?: return emptyList()
        val near = loop.lat != null && loop.lng != null &&
            Geo.distanceM(loop.lat, loop.lng, end.lat, end.lng) <= ctx.config.parkingHuntRadiusM
        return if (near) listOf(candidate(loop, true)) else emptyList()
    }

    /** ARRIVING, or within parkingHuntRadiusM of the destination: looking for parking, not lost. */
    private fun parkingHunt(loop: Loop, ctx: TripContext): Boolean {
        if (ctx.state == TripState.ARRIVING) return true
        val d = ctx.dest ?: return false
        val lat = loop.lat ?: return false
        val lng = loop.lng ?: return false
        val dist = ctx.distanceToDestM(lat, lng, d) ?: return false
        return dist <= ctx.config.parkingHuntRadiusM
    }

    private fun candidate(loop: Loop, hunt: Boolean): EpisodeCandidate {
        val mins = minutes(loop.start, loop.end)
        val city = loop.lat?.let { lat -> loop.lng?.let { cities?.resolve(lat, it) } }
        val params = buildMap<String, Any> {
            put("count", loop.count)
            put("minutes", mins)
            city?.let { put("cityName", it.short) }
        }
        return EpisodeCandidate(if (hunt) EpisodeType.PARKING_HUNT else type, loop.start, loop.end, params, lostMinutes = mins, key = loop.key)
    }
}
