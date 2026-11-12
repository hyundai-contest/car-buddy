package ai.carbuddy.signal.adapter.navi

import ai.carbuddy.core.model.Capability
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.signal.CameraAlert
import ai.carbuddy.signal.DestinationArrived
import ai.carbuddy.signal.DestinationChanged
import ai.carbuddy.signal.ReRouteEnded
import ai.carbuddy.signal.RemainTime
import ai.carbuddy.signal.RouteCancelled
import ai.carbuddy.signal.RouteStarted
import ai.carbuddy.signal.WaypointChanged
import ai.carbuddy.signal.adapter.LiveEmitter
import ai.carbuddy.signal.adapter.SignalAdapter
import ai.carbuddy.voice.TurnInfoPort
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import ai.pleos.playground.navi.data.CameraAlert as NaviCameraAlert

/**
 * Pleos NaviHelper SDK 3.0.5 → navigation SignalEvents (SPEC 4.2). Names and fields are from the
 * official guide (document.pleos.ai, NaviHelper) and the SDK sources jar.
 *
 * Push callbacks give the route lifecycle; the info callbacks (onDestinationInfo, onWayPointInfo,
 * onTBTInfo) answer get*Info() requests, so while a route runs the adapter asks every
 * [POLL_MS] for the remaining time and the next turn.
 * - RouteStarted: onRouteStarted gives name + duration; coordinates come from the following
 *   getDestinationInfo (or are left null after [DEST_WAIT_MS]). ETA = callback time + duration.
 * - DestinationChanged: onDestinationChanged has no payload → the next DestinationInfo.
 * - WaypointChanged: getWaypointInfo → one event per waypoint (name + coordinates).
 * - On start, getRouteStateInfo().naviMode == ROUTE_GUIDING re-sends the running route (recovery after a reboot,
 *   SPEC 5.3: the pipeline ignores the same destination).
 * Also the TurnInfoPort for ArrivalRecap: the next turn is "far" when the first TBT is ≥ [farTurnM].
 */
class NaviHelperAdapter(
    context: Context,
    private val out: LiveEmitter,
    private val caps: CapabilityRegistry,
    private val farTurnM: Double = 1_000.0,
) : SignalAdapter, TurnInfoPort {
    override val name = "navi"
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null

    // all state below is touched only on the main thread (SDK callbacks are re-posted there)
    private var routing = false
    private var pendingStart: PendingStart? = null
    private var pendingChange = false
    private var pendingWaypoints = false
    @Volatile private var nextTurnM: Double? = null
    private var lastCameraAt = 0L

    private data class PendingStart(val t: Long, val name: String?, val durationSec: Int)

    @Synchronized override fun start() {
        if (navi != null) return
        val n = try { NaviHelper(ctx) } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "NaviHelper unavailable"); caps.markUnsupported(Capability.NAVI); return
        }
        navi = n
        try {
            n.initialize(
                onConnected = {
                    main.post {
                        caps.markSupported(Capability.NAVI)
                        n.addListener(listener)
                        call { n.getRouteStateInfo() }
                        Timber.tag(TAG).i("NaviHelper connected")
                    }
                },
                onFailed = { t -> Timber.tag(TAG).w(t, "NaviHelper connect failed"); caps.markUnsupported(Capability.NAVI) },
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "NaviHelper.initialize failed"); caps.markUnsupported(Capability.NAVI)
        }
    }

    @Synchronized override fun stop() {
        main.removeCallbacksAndMessages(null)
        navi?.let { runCatching { it.removeListener(listener); it.release() } }
        navi = null
    }

    /** ArrivalRecap: unknown (no route / no TBT yet) counts as far, as before navi existed. */
    override fun nextTurnFar(): Boolean = nextTurnM?.let { it >= farTurnM } ?: true

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onMain {
            Timber.tag(TAG).d("onRouteStarted %s", routeStartInfo)
            routing = true
            pendingStart = PendingStart(now(), routeStartInfo.name.ifBlank { null }, routeStartInfo.duration)
            call { navi?.getDestinationInfo() }
            main.postDelayed(::flushStartWithoutCoords, DEST_WAIT_MS)
            schedulePoll()
        }

        override fun onReRouteEnded() = onMain { out.emit { ReRouteEnded(it) }; call { navi?.getDestinationInfo() } }

        override fun onRouteCancelled() = onMain { endRoute(); out.emit { RouteCancelled(it) } }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = onMain { endRoute(); out.emit { DestinationArrived(it) } }

        override fun onDestinationChanged() = onMain { pendingChange = true; call { navi?.getDestinationInfo() } }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = onMain { pendingWaypoints = true; call { navi?.getWaypointInfo() } }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = onMain {
            // the navigation also pushes this unasked (e.g. after a cancel); only answers to our request count
            if (!pendingWaypoints) return@onMain
            pendingWaypoints = false
            if (waypointInfoList.isEmpty()) out.emit { WaypointChanged(it) }
            waypointInfoList.forEach { w -> out.emit { WaypointChanged(it, w.name.ifBlank { null }, w.latitude, w.longitude) } }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onMain { onDestination(destinationInfo) }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onMain {
            Timber.tag(TAG).d("onRouteStateInfo mode=%s routing=%b (ours %b)", routeStateInfo.naviMode, routeStateInfo.isRouting, routing)
            // isRouting = a route search in progress; guidance itself is naviMode ROUTE_GUIDING (emulator, 11/8)
            if (routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING && !routing) {
                // a route already running when we connected (app or system restart): re-send it
                routing = true
                pendingStart = PendingStart(now(), null, -1)
                call { navi?.getDestinationInfo() }
                main.postDelayed(::flushStartWithoutCoords, DEST_WAIT_MS)
                schedulePoll()
            }
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) { nextTurnM = tbtInfoList.firstOrNull()?.distance }

        override fun onCameraAlert(cameraAlertList: List<NaviCameraAlert>) = onMain {
            val active = cameraAlertList.firstOrNull { it.status } ?: return@onMain
            val t = now()
            if (t - lastCameraAt < CAMERA_REPEAT_MS) return@onMain     // the list repeats while approaching
            lastCameraAt = t
            out.emit { CameraAlert(it, active.speedLimit.takeIf { s -> s > 0 }) }
        }

        override fun onError(errorCode: NaviErrorCode) { Timber.tag(TAG).w("navi error %s", errorCode) }
    }

    private fun onDestination(d: DestinationInfo) {
        Timber.tag(TAG).d("onDestinationInfo %s remain=%ds pendingStart=%s change=%b", d.name, d.remainTime, pendingStart, pendingChange)
        val name = d.name.ifBlank { null }
        val (lat, lng) = d.latitude to d.longitude
        val start = pendingStart
        when {
            start != null -> {
                pendingStart = null
                val remain = if (start.durationSec >= 0) start.durationSec else d.remainTime
                out.emit { RouteStarted(it, start.name ?: name, lat, lng, start.t + remain * 1000L) }
                out.emit { RemainTime(it, d.remainTime) }
            }
            pendingChange -> {
                pendingChange = false
                out.emit { DestinationChanged(it, name, lat, lng) }
                out.emit { RemainTime(it, d.remainTime) }
            }
            routing -> out.emit { RemainTime(it, d.remainTime) }
        }
    }

    private fun flushStartWithoutCoords() {
        val s = pendingStart ?: return
        pendingStart = null
        if (s.durationSec < 0) return       // a recovered route without any destination info: nothing to say
        out.emit { RouteStarted(it, s.name, null, null, s.t + s.durationSec * 1000L) }
    }

    private fun endRoute() {
        routing = false
        pendingStart = null
        pendingChange = false
        nextTurnM = null
        main.removeCallbacks(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!routing) return
            call { navi?.getDestinationInfo() }
            call { navi?.getTBTInfo() }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun schedulePoll() { main.removeCallbacks(poll); main.postDelayed(poll, POLL_MS) }

    private fun now() = out.now()
    private inline fun onMain(crossinline block: () -> Unit) { main.post { block() } }
    private inline fun call(block: () -> Unit) {
        try { block() } catch (e: Exception) { Timber.tag(TAG).w("navi call failed: %s", e.message) }
    }

    private companion object {
        const val TAG = "CB/navi"
        const val POLL_MS = 10_000L
        const val DEST_WAIT_MS = 3_000L
        const val CAMERA_REPEAT_MS = 30_000L
    }
}
