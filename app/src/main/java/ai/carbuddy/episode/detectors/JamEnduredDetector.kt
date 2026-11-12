package ai.carbuddy.episode.detectors

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.signal.DriverBelt
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.PowerBoot
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable

/**
 * JAM_ENDURED (SPEC 6.2): consecutive Speed < jamKmh, bridging recoveries of ≤ jamRecoverSec,
 * broken by Gear=P or belt release. Segments ≥ jamMinMin become an episode.
 */
class JamEnduredDetector : StatefulDetector<JamEnduredDetector.State>(State.serializer(), ::State) {

    @Serializable data class State(
        val segStart: Long? = null,
        val recoverSince: Long? = null,
        val lastT: Long = 0,
        /** last P / belt-off break: a new segment never starts before it */
        val breakAt: Long = 0,
    )

    override val type = EpisodeType.JAM_ENDURED
    override val requires = setOf(Capability.SPEED)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val out = mutableListOf<EpisodeCandidate>()
        val broken = (e is Gear && e.gear == GearPos.P) || (e is DriverBelt && !e.buckled) || e is PowerBoot
        if (broken) {
            s.segStart?.let { out += close(it, s.recoverSince ?: if (e is PowerBoot) s.lastT else e.t, ctx) }
            s = State(lastT = e.t, breakAt = e.t)
            return out
        }
        val slow = ctx.speedKmh < cfg.jamKmh
        val start = s.segStart
        if (start == null) {
            // any event may reveal we are slow (speed events are sparse at a constant speed)
            if (slow && ctx.speedKnown && ctx.gear != GearPos.P && !ctx.isParked(e.t)) {
                val since = maxOf(ctx.speedT, ctx.tripStartedAt, s.breakAt)
                s = State(segStart = since, lastT = e.t, breakAt = s.breakAt)
            }
            return out
        }
        if (slow) {
            s = s.copy(recoverSince = null, lastT = e.t)
        } else {
            val rec = s.recoverSince ?: e.t
            if (e.t - rec > cfg.jamRecoverSec * 1000L) {
                out += close(start, rec, ctx)
                s = State(lastT = e.t)
                return out
            }
            s = s.copy(recoverSince = rec, lastT = e.t)
        }
        // provisional episode while the jam is still going
        val end = s.recoverSince ?: e.t
        if (end - start >= cfg.jamMinMin * 60_000L) out += candidate(start, end, ctx)
        return out
    }

    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> {
        val start = s.segStart ?: return emptyList()
        return close(start, s.recoverSince ?: s.lastT, ctx).also { s = State() }
    }

    private fun close(start: Long, end: Long, ctx: TripContext): List<EpisodeCandidate> =
        if (end - start >= ctx.config.jamMinMin * 60_000L) listOf(candidate(start, end, ctx)) else emptyList()

    private fun candidate(start: Long, end: Long, ctx: TripContext): EpisodeCandidate {
        val mins = minutes(start, end)
        return EpisodeCandidate(
            type, start, end, mapOf("minutes" to mins),
            lostMinutes = Math.round(mins * ctx.config.jamLostFactor).toInt(), key = start.toString(),
        )
    }
}
