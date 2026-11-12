package ai.carbuddy.episode.detectors

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.util.Geo
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.PlaceNameProvider
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.episode.Stop
import ai.carbuddy.episode.StopKind
import ai.carbuddy.episode.StopWatcher
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteCancelled
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.signal.WaypointChanged
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable

/**
 * DETOUR (SPEC 6.2):
 * (a) destination change / waypoint / cancel-then-new-route away from the original destination.
 *     lostMinutes = ETA growth across the detour: (ETA to the original destination after coming
 *     back, or ETA to the new place if we never came back) − ETA just before the change.
 * (b) a stop far from the destination that pushed the ETA (classified by [StopWatcher]).
 */
class DetourDetector(private val places: PlaceNameProvider? = null) :
    StatefulDetector<DetourDetector.State>(State.serializer(), ::State) {

    @Serializable data class Session(
        val key: String,
        val start: Long,
        val fromName: String?,
        val toName: String?,
        val etaBefore: Long?,
        val etaAfterChange: Long? = null,
        val awaitingChangeEta: Boolean = true,
        val returnedAt: Long? = null,
        val etaAfterReturn: Long? = null,
        val closed: Boolean = false,
    )

    @Serializable data class State(
        val session: Session? = null,
        val cancelled: Boolean = false,
        val stops: StopWatcher = StopWatcher(),
    )

    override val type = EpisodeType.DETOUR
    override val requires = emptySet<Capability>()

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val out = mutableListOf<EpisodeCandidate>()
        s.stops.onEvent(e, ctx, places)?.let { out += fromStop(it, ctx) }
        when (e) {
            is DestinationChanged -> out += onNewDestination(e.t, e.destName, e.destLat, e.destLng, ctx)
            is RouteStarted -> if (s.cancelled) { s = s.copy(cancelled = false); out += onNewDestination(e.t, e.destName, e.destLat, e.destLng, ctx) }
            is RouteCancelled -> s = s.copy(cancelled = true)
            is WaypointChanged -> if (s.session == null) out += open(e.t, null, ctx)
            is RemainTime -> s.session?.let { ses ->
                val eta = e.t + e.remainSec * 1000L
                if (ses.awaitingChangeEta) {
                    s = s.copy(session = ses.copy(etaAfterChange = eta, awaitingChangeEta = false))
                    if (ses.returnedAt == null) out += emit(s.session!!)
                }
                val cur = s.session!!
                if (cur.returnedAt != null && !cur.closed && e.t >= cur.returnedAt) {
                    s = s.copy(session = cur.copy(etaAfterReturn = eta, closed = true))
                    out += emit(s.session!!)
                }
            }
            else -> Unit
        }
        return out
    }

    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> {
        val out = mutableListOf<EpisodeCandidate>()
        s.stops.flush()?.let { out += fromStop(it, ctx) }
        s.session?.takeIf { !it.closed }?.let { out += emit(it.copy(closed = true)) }
        return out
    }

    private fun onNewDestination(t: Long, name: String?, lat: Double?, lng: Double?, ctx: TripContext): List<EpisodeCandidate> {
        val orig = ctx.origDest ?: return emptyList()
        val ses = s.session
        val backHome = (name != null && name == orig.name) ||
            (lat != null && lng != null && orig.lat != null && orig.lng != null && Geo.distanceM(lat, lng, orig.lat, orig.lng) <= ctx.config.trip.arriveRadiusM)
        if (ses != null) {
            if (backHome && ses.returnedAt == null) s = s.copy(session = ses.copy(returnedAt = t))
            return emptyList()
        }
        if (backHome) return emptyList()
        return open(t, name, ctx)
    }

    private fun open(t: Long, toName: String?, ctx: TripContext): List<EpisodeCandidate> {
        val remain = ctx.remainSec
        val etaBefore = if (remain != null && t - ctx.remainT < 5 * 60_000L) ctx.remainT + remain * 1000L else null
        val ses = Session(key = "nav-$t", start = t, fromName = ctx.origDest?.name, toName = toName, etaBefore = etaBefore)
        s = s.copy(session = ses)
        return emit(ses)
    }

    private fun emit(ses: Session): List<EpisodeCandidate> {
        val after = if (ses.returnedAt != null) ses.etaAfterReturn else ses.etaAfterChange
        val lost = if (ses.closed && ses.etaBefore != null && after != null) maxOf(0, minutes(ses.etaBefore, after)) else null
        val end = ses.returnedAt ?: ses.start
        val params = buildMap<String, Any> {
            ses.fromName?.let { put("fromName", it) }
            ses.toName?.let { put("toName", it); put("place", placeWord(it)) }
            put("minutes", lost ?: 0)
            put("variant", if (ses.toName == null) "waypoint" else "nav")
        }
        return listOf(EpisodeCandidate(type, ses.start, maxOf(end, ses.start), params, lostMinutes = lost, key = ses.key))
    }

    private fun fromStop(stop: Stop, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val far = (stop.distToDestM ?: return emptyList()) > cfg.detourMinDistKm * 1000
        if (!far || stop.stayMinutes < cfg.detourMinStayMin) return emptyList()
        if (StopWatcher.classify(stop, cfg) != StopKind.OFF_ROUTE) return emptyList()
        val mins = Math.round(stop.stayMinutes).toInt()
        val params = buildMap<String, Any> {
            ctx.origDest?.name?.let { put("fromName", it) }
            stop.placeName?.let { put("toName", it); put("place", placeWord(it)) }
            put("minutes", mins)
            put("variant", if (stop.placeName == null) "stop" else "nav")
        }
        return listOf(EpisodeCandidate(type, stop.start, stop.resumeT, params, lostMinutes = mins, key = "stop-${stop.start}"))
    }

    companion object {
        private val SEA = listOf("해변", "해수욕장", "바다", "해안", "항구", "포구", "방파제")
        /** Short place word for titles/receipts: "안목해변" → "바다" (card mockup "바다로 새 버린 주말"). */
        fun placeWord(name: String): String = when {
            SEA.any { name.contains(it) } -> "바다"
            name.endsWith("산") || name.contains("산 ") -> "산"
            name.contains("계곡") -> "계곡"
            name.contains("호수") -> "호수"
            else -> name
        }
    }
}
