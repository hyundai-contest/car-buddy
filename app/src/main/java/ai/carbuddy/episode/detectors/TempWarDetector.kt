package ai.carbuddy.episode.detectors

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.episode.EpisodeCandidate
import ai.carbuddy.episode.StatefulDetector
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * TEMP_WAR (SPEC 6.2): passenger seat occupied and |driver − passenger target| ≥ tempDiffC
 * continuously for tempMinDurMin. Values at the widest gap are kept for the card.
 */
class TempWarDetector : StatefulDetector<TempWarDetector.State>(State.serializer(), ::State) {

    @Serializable data class State(
        val since: Long? = null,
        val maxDiff: Float = 0f,
        val driverC: Float = 0f,
        val passengerC: Float = 0f,
        val emitted: Boolean = false,
        val lastT: Long = 0,
    )

    override val type = EpisodeType.TEMP_WAR
    override val requires = setOf(Capability.HVAC_TARGET, Capability.SEAT_OCCUPANCY)

    override fun onEvent(e: SignalEvent, ctx: TripContext): List<EpisodeCandidate> {
        val cfg = ctx.config
        val h = ctx.hvac
        val war = h != null && ctx.seats[SeatPos.PASSENGER] == true && abs(h.driverC - h.passengerC) >= cfg.tempDiffC
        val minDur = cfg.tempMinDurMin * 60_000L
        if (war) {
            val diff = abs(h!!.driverC - h.passengerC)
            var st = s.since?.let { s } ?: State(since = e.t)
            if (diff > st.maxDiff) st = st.copy(maxDiff = diff, driverC = h.driverC, passengerC = h.passengerC)
            s = st.copy(lastT = e.t)
            if (e.t - s.since!! >= minDur) {
                s = s.copy(emitted = true)
                return listOf(candidate(e.t))
            }
            return emptyList()
        }
        val since = s.since ?: return emptyList()
        val out = if (e.t - since >= minDur) listOf(candidate(e.t)) else emptyList()
        s = State()
        return out
    }

    override fun onTripEnd(ctx: TripContext): List<EpisodeCandidate> {
        val since = s.since ?: return emptyList()
        return if (s.lastT - since >= ctx.config.tempMinDurMin * 60_000L) listOf(candidate(s.lastT)) else emptyList()
    }

    private fun candidate(end: Long) = EpisodeCandidate(
        type, s.since!!, end,
        mapOf("driverC" to s.driverC.toDouble(), "passengerC" to s.passengerC.toDouble()),
        key = s.since.toString(),
    )
}
