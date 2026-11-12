package ai.carbuddy.signal.adapter.aaos

import ai.carbuddy.core.model.Capability
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.signal.Speed
import ai.carbuddy.signal.adapter.LiveEmitter
import ai.carbuddy.signal.adapter.SignalAdapter
import ai.carbuddy.signal.adapter.UxRestrictionsSource
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber
import kotlin.math.abs

/** One shared connection to the AAOS car service; null when the platform has none (phones, tests). */
class CarConnection(context: Context) {
    private val ctx = context.applicationContext
    private var car: Car? = null
    private var tried = false

    @Synchronized fun get(): Car? {
        if (!tried) {
            tried = true
            car = runCatching { Car.createCar(ctx) }.onFailure { Timber.tag(TAG).w(it, "Car.createCar failed") }.getOrNull()
        }
        return car
    }

    companion object { const val TAG = "CB/aaos" }
}

/**
 * Vehicle speed from CarPropertyManager (SPEC 4.2: m/s → km/h). The Pleos emulator publishes only
 * PERF_VEHICLE_SPEED_DISPLAY, real cars both (signals_phase0.md). Needs the runtime grant
 * android.car.permission.CAR_SPEED; until granted, SPEED counts as unsupported.
 */
class AaosSpeedAdapter(
    context: Context,
    private val car: CarConnection,
    private val out: LiveEmitter,
    private val caps: CapabilityRegistry,
) : SignalAdapter {
    override val name = "aaos-speed"
    private val ctx = context.applicationContext
    private var registered = false
    private var lastKmh = -1f

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val ms = value.value as? Float ?: return
            val kmh = (ms * 3.6f).coerceAtLeast(0f)
            synchronized(this@AaosSpeedAdapter) {
                // keep the log small, but never miss the moment the car stops
                if (abs(kmh - lastKmh) < 1f && !(kmh == 0f && lastKmh != 0f)) return
                lastKmh = kmh
            }
            out.emit { Speed(it, kmh) }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    @Synchronized override fun start() {
        if (registered) return
        if (ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            caps.markUnsupported(Capability.SPEED)
            Timber.tag(CarConnection.TAG).i("speed waits for %s", PERMISSION)
            return
        }
        val pm = car.get()?.let { runCatching { it.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull() }
        if (pm == null) { caps.markUnsupported(Capability.SPEED); return }
        var ok = false
        for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY)) {
            runCatching { if (pm.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)) ok = true }
                .onFailure { Timber.tag(CarConnection.TAG).w("speed %d: %s", id, it.message) }
        }
        registered = ok
        if (ok) caps.markSupported(Capability.SPEED) else caps.markUnsupported(Capability.SPEED)
    }

    companion object { const val PERMISSION = "android.car.permission.CAR_SPEED" }
}

/**
 * CarUxRestrictionsManager → [UxRestrictionsSource] (SPEC 9장). When the service is missing,
 * [available] stays false and SafetyGate keeps its "restricted = not parked" fallback.
 */
class AaosUxRestrictions(private val car: CarConnection) : SignalAdapter, UxRestrictionsSource {
    override val name = "aaos-ux"
    @Volatile private var current: CarUxRestrictions? = null
    @Volatile override var available: Boolean = false
        private set
    private var registered = false

    override val restricted: Boolean get() = current?.isRequiresDistractionOptimization == true
    override val maxStringLength: Int? get() = current?.takeIf { it.isRequiresDistractionOptimization }?.maxRestrictedStringLength

    @Synchronized override fun start() {
        if (registered) return
        val mgr = car.get()?.let { runCatching { it.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager }.getOrNull() } ?: return
        runCatching {
            current = mgr.currentCarUxRestrictions
            mgr.registerListener { r -> current = r; Timber.tag(CarConnection.TAG).d("ux restrictions DO=%b", r.isRequiresDistractionOptimization) }
            registered = true
            available = true
        }.onFailure { Timber.tag(CarConnection.TAG).w(it, "CarUxRestrictions unavailable") }
    }
}
