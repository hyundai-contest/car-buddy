package ai.carbuddy.episode.detectors

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.PlaceNameProvider
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.episode.Stop
import ai.carbuddy.episode.StopKind
import ai.carbuddy.episode.StopWatcher
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable

/**
 * REST_STOP (SPEC 6.2): after restAfterStartMin of the trip, a PAUSED stop outside restMinDistKm
 * of the destination that resumes the same trip after restMinMin..restMaxMin. One episode per
 * trip with count and total minutes. Stops that pushed the ETA go to DETOUR instead.
 */
class RestStopDetector(private val places: PlaceNameProvider? = null) :
    StatefulDetector<RestStopDetector.State>(State.serializer(), ::State) {

    @Serializable data class State(
        val count: Int = 0,
        val totalMin: Int = 0,
        val first: Long? = null,
        val last: Long? = null,
        val stops: StopWatcher = StopWatcher(),
    )

    override val type = EpisodeType.REST_STOP
    override val requires = setOf(Capability.DRIVER_BELT)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> =
        s.stops.onEvent(e, ctx, places)?.let { judge(it, ctx) } ?: emptyList()

    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> = s.stops.flush()?.let { judge(it, ctx) } ?: emptyList()

    private fun judge(stop: Stop, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val stay = stop.stayMinutes
        val farEnough = stop.distToDestM?.let { it > cfg.restMinDistKm * 1000 } ?: true
        val ok = stop.minutesSinceTripStart >= cfg.restAfterStartMin && farEnough &&
            stay >= cfg.restMinMin && StopWatcher.classify(stop, cfg) == StopKind.REST
        if (!ok) return emptyList()
        val mins = Math.round(stay).toInt()
        s = s.copy(count = s.count + 1, totalMin = s.totalMin + mins, first = s.first ?: stop.start, last = stop.resumeT)
        return listOf(
            EpisodeCandidate(
                type, s.first!!, s.last!!, mapOf("count" to s.count, "minutes" to s.totalMin),
                lostMinutes = s.totalMin, key = "rest",
            ),
        )
    }
}
