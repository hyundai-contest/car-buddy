package ai.carbuddy.episode

import ai.carbuddy.signal.PauseClosed
import ai.carbuddy.signal.PauseKind
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.SignalEvent
import ai.carbuddy.trip.TripContext
import kotlinx.serialization.Serializable

/** A closed stop inside one trip (rest area or unplanned place). Stay includes ignition-off time. */
@Serializable
data class Stop(
    val start: Long,
    val resumeT: Long,
    val lat: Double?,
    val lng: Double?,
    val distToDestM: Double?,
    val remainBeforeSec: Int?,
    val minutesSinceTripStart: Double,
    val placeName: String? = null,
    val remainAfterSec: Int? = null,
) {
    val stayMinutes: Double get() = (resumeT - start) / 60_000.0
}

enum class StopKind { REST, OFF_ROUTE }

/** Provides a POI name for a stop if one is known (e.g. "횡성휴게소"). TODO: real POI lookup. */
fun interface PlaceNameProvider { fun nameAt(lat: Double, lng: Double): String? }

/**
 * Turns [PauseClosed] (SPEC 4.1) into a classified [Stop]. A stop resolves at the first RemainTime
 * after resuming (to compare the ETA before/after), or after a timeout. Waypoint and provisional
 * stops are not episodes ('들른 곳' receipt lines, SPEC 5.5) and are ignored here.
 * Used by both REST_STOP and DETOUR(b) so the two never claim the same stop.
 */
@Serializable
class StopWatcher(var pending: Stop? = null) {

    /** @return the stop once it is resolved (exactly once). */
    fun onEvent(e: SignalEvent, ctx: TripContext, places: PlaceNameProvider?): Stop? {
        if (e is PauseClosed) {
            if (e.kind != PauseKind.STOP) return null
            pending = Stop(
                start = e.pausedAt, resumeT = e.t, lat = e.lat, lng = e.lng, distToDestM = e.distToDestM,
                remainBeforeSec = e.remainBeforeSec,
                minutesSinceTripStart = (e.pausedAt - ctx.tripStartedAt) / 60_000.0,
                placeName = e.lat?.let { la -> e.lng?.let { places?.nameAt(la, it) } },
            )
            return null
        }
        val p = pending ?: return null
        val done = when {
            e is RemainTime && e.t >= p.resumeT -> p.copy(remainAfterSec = e.remainSec)
            e.t - p.resumeT >= ctx.config.stopResolveTimeoutMin * 60_000L -> p
            else -> return null
        }
        pending = null
        return done
    }

    /** Trip ended while a stop was still waiting for its RemainTime. */
    fun flush(): Stop? = pending.also { pending = null }

    companion object {
        fun classify(stop: Stop, cfg: DetectorConfig): StopKind {
            if (stop.placeName?.contains("휴게소") == true) return StopKind.REST
            val before = stop.remainBeforeSec
            val after = stop.remainAfterSec
            if (before != null && after != null && after - before > cfg.detourRemainIncreaseMin * 60) return StopKind.OFF_ROUTE
            return StopKind.REST
        }
    }
}
