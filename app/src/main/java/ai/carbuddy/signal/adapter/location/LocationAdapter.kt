package ai.carbuddy.signal.adapter.location

import ai.carbuddy.core.model.Capability
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.signal.Location
import ai.carbuddy.signal.adapter.LiveEmitter
import ai.carbuddy.signal.adapter.SignalAdapter
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import timber.log.Timber

/**
 * Vehicle location (SPEC 4.2). Pleos FusedLocation SDK 1.1.3 first (official guide: isSupported →
 * initialize → registerFusedLocationCallback, permission pleos.car.permission.FUSED_LOCATION);
 * where it is not supported or fails, the platform GPS provider (1 s, ACCESS_FINE_LOCATION).
 *
 * LocationData.confidence has no documented unit, so accuracy is left null for fused fixes
 * (TODO(SDK): map once the unit is known); heading → bearing.
 */
class LocationAdapter(
    context: Context,
    private val out: LiveEmitter,
    private val caps: CapabilityRegistry,
    /** SPEC 5.3: wait this long for a fresh fix after start, then fall back to the last known one. */
    private val freshFixWaitMs: Long = 60_000L,
) : SignalAdapter {
    override val name = "location"
    private val ctx = context.applicationContext
    private var fused: FusedLocation? = null
    private var gpsOn = false
    @Volatile private var gotFix = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val l = location ?: return
            gotFix = true
            out.emit { Location(it, l.latitude, l.longitude, null, l.heading) }
        }
        override fun onError(exception: Exception) {
            Timber.tag(TAG).w("fused location error: %s → GPS", exception.message)
            startGps()
        }
    }

    private val gpsListener = LocationListener { l ->
        gotFix = true
        out.emit { Location(it, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, if (l.hasBearing()) l.bearing else null) }
    }

    /** Re-run after permission grants: a GPS fallback switches to FusedLocation once it is allowed. */
    @Synchronized override fun start() {
        if (fused != null) return
        if (gpsOn) { if (startFused()) stopGps(); return }
        if (!startFused()) startGps()
        main.removeCallbacks(lastKnownFallback)
        main.postDelayed(lastKnownFallback, freshFixWaitMs)
    }

    /**
     * SPEC 5.3 복구 2단계: no fresh fix within [freshFixWaitMs] (garage, tunnel, cold GPS) → the last
     * known position, which after a reboot is normally where the car was parked. Accuracy unknown (null).
     */
    private val lastKnownFallback = Runnable {
        if (gotFix) return@Runnable
        val f = fused
        try {
            if (f != null) f.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    val l = location ?: return
                    if (gotFix) return
                    Timber.tag(TAG).i("no fresh fix in %d s: using the last known location", freshFixWaitMs / 1000)
                    out.emit { Location(it, l.latitude, l.longitude, null, l.heading) }
                }
                override fun onError(exception: Exception) { Timber.tag(TAG).w("last known location: %s", exception.message) }
            }) else if (gpsOn && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                ctx.getSystemService(LocationManager::class.java)?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { l ->
                    Timber.tag(TAG).i("no fresh fix: using the last known GPS location")
                    out.emit { Location(it, l.latitude, l.longitude, null, if (l.hasBearing()) l.bearing else null) }
                }
            }
        } catch (e: SecurityException) { Timber.tag(TAG).w(e, "last known location refused") }
    }

    private fun stopGps() {
        ctx.getSystemService(LocationManager::class.java)?.removeUpdates(gpsListener)
        gpsOn = false
    }

    private fun startFused(): Boolean = if (ctx.checkSelfPermission(FUSED_PERMISSION) != PackageManager.PERMISSION_GRANTED) false else try {
        val f = FusedLocation(ctx)
        if (!f.isSupported()) { Timber.tag(TAG).i("FusedLocation not supported here → GPS"); false }
        else {
            f.initialize()
            f.registerFusedLocationCallback(fusedListener)
            fused = f
            caps.markSupported(Capability.LOCATION)
            Timber.tag(TAG).i("FusedLocation registered")
            true
        }
    } catch (e: Throwable) {
        Timber.tag(TAG).w("FusedLocation failed: %s → GPS", e.message); false
    }

    @Synchronized private fun startGps() {
        if (gpsOn) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (fused == null) caps.markUnsupported(Capability.LOCATION)
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            if (fused == null) caps.markUnsupported(Capability.LOCATION)
            return
        }
        try {
            fused?.let { f -> runCatching { f.unregisterFusedLocationCallback(fusedListener); f.release() } }
            fused = null
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, gpsListener, Looper.getMainLooper())
            gpsOn = true
            caps.markSupported(Capability.LOCATION)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "location updates refused")
            caps.markUnsupported(Capability.LOCATION)
        }
    }

    @Synchronized override fun stop() {
        main.removeCallbacks(lastKnownFallback)
        fused?.let { f -> runCatching { f.unregisterFusedLocationCallback(fusedListener); f.release() } }
        fused = null
        if (gpsOn) stopGps()
    }

    companion object {
        /** protectionLevel dangerous on the Pleos emulator: a runtime grant like ACCESS_FINE_LOCATION. */
        const val FUSED_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "CB/location"
        private const val GPS_INTERVAL_MS = 1_000L
    }
}
