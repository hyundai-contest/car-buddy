package ai.carbuddy.signal.adapter.pleos

import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.GearPos
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.core.model.WiperMode
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.signal.DriverBelt
import ai.carbuddy.signal.FuelPercent
import ai.carbuddy.signal.Gear
import ai.carbuddy.signal.HighBeam
import ai.carbuddy.signal.HvacTarget
import ai.carbuddy.signal.LowBeam
import ai.carbuddy.signal.SeatOccupancy
import ai.carbuddy.signal.TrunkOpen
import ai.carbuddy.signal.TurnSignal
import ai.carbuddy.signal.WindowPos
import ai.carbuddy.signal.Wiper
import ai.carbuddy.signal.adapter.LiveEmitter
import ai.carbuddy.signal.adapter.SignalAdapter
import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/** One shared Pleos Vehicle connection for the signal adapter, ambient light and window control. */
class PleosVehicle(context: Context) {
    private val ctx = context.applicationContext
    private var vehicle: Vehicle? = null
    private var failed = false

    @Synchronized fun get(): Vehicle? {
        if (vehicle == null && !failed) {
            vehicle = try { Vehicle(ctx).also { it.initialize() } } catch (e: Throwable) {
                Timber.tag("CB/pleos").w(e, "Vehicle.initialize failed: no Pleos vehicle service"); failed = true; null
            }
        }
        return vehicle
    }
}

/**
 * Pleos Vehicle SDK 2.3.2 → SignalEvent (SPEC 4.2). Every call here is one the Phase 0 prototype ran
 * on the Pleos emulator (docs/signals_phase0.md) or whose signature is in the SDK sources jar
 * (belt, low/high beam, fuel, trunk = DoorValue.REAR).
 *
 * Per-seat callbacks are folded into the whole-car events (SeatOccupancy, WindowPos, HvacTarget)
 * and re-emitted only when a value changes. A family whose registration fails is reported to the
 * [CapabilityRegistry] so its detectors switch off.
 */
class PleosVehicleAdapter(
    private val pleos: PleosVehicle,
    private val out: LiveEmitter,
    private val caps: CapabilityRegistry,
) : SignalAdapter {
    override val name = "pleos"
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null

    private val occupancy = linkedMapOf<SeatPos, Boolean>()
    private val windows = linkedMapOf<SeatPos, Int>()
    private var driverC: Float? = null
    private var passengerC: Float? = null
    private var lastWiper: WiperMode? = null
    private var lastGear: GearPos? = null

    /**
     * SeatImpl maps raw value 1 to "occupied", but AAOS VehicleSeatOccupancyState is 1 = VACANT,
     * 2 = OCCUPIED, and the emulator console follows AAOS: every seat reads the opposite of the
     * console (11/8). Flipped on the emulator only. TODO(SDK): ask Pleos which one a real car sends.
     */
    private val invertOccupancy = android.os.Build.HARDWARE in setOf("ranchu", "goldfish")

    private val seatValue = mapOf(
        SeatPos.DRIVER to SeatValue.ROW_1_LEFT, SeatPos.PASSENGER to SeatValue.ROW_1_RIGHT,
        SeatPos.REAR_LEFT to SeatValue.ROW_2_LEFT, SeatPos.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )
    private val windowValue = mapOf(
        SeatPos.DRIVER to WindowValue.ROW_1_LEFT, SeatPos.PASSENGER to WindowValue.ROW_1_RIGHT,
        SeatPos.REAR_LEFT to WindowValue.ROW_2_LEFT, SeatPos.REAR_RIGHT to WindowValue.ROW_2_RIGHT,
    )

    @Synchronized override fun start() {
        if (vehicle != null) return
        val v = pleos.get() ?: run {
            VEHICLE_FAMILIES.forEach(caps::markUnsupported)
            return
        }
        vehicle = v

        sdk(Capability.GEAR) {
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) {
                    val g = gear?.toPos() ?: return
                    synchronized(this@PleosVehicleAdapter) { if (g == lastGear) return; lastGear = g }
                    out.emit { Gear(it, g) }
                }
                override fun onFailed(e: Exception) = failed(Capability.GEAR, e)
            })
        }
        sdk(Capability.DRIVER_BELT) {
            v.getSeat().registerSeatBeltBuckledState(SeatArea(SeatValue.ROW_1_LEFT), object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { state?.let { b -> changed("belt", b) { DriverBelt(it, b) } } }
                override fun onFailed(e: Exception) = failed(Capability.DRIVER_BELT, e)
            })
        }
        for ((seat, value) in seatValue) {
            sdk(Capability.SEAT_OCCUPANCY) {
                v.getSeat().registerSeatOccupancy(SeatArea(value), object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(occupancy: Boolean?) { occupancy?.let { onOccupancy(seat, it != invertOccupancy) } }
                    override fun onFailed(e: Exception) = failed(Capability.SEAT_OCCUPANCY, e)
                })
            }
        }
        for ((seat, value) in windowValue) {
            sdk(Capability.WINDOW) {
                v.getWindow().registerWindowPosition(WindowArea(value), object : WindowPositionListener {
                    // SDK 0–10 = console 0–100 % (signals_phase0.md)
                    override fun onWindowPositionUpdated(position: Int?) { position?.let { onWindow(seat, (it * 10).coerceIn(0, 100)) } }
                    override fun onFailed(e: Exception) = failed(Capability.WINDOW, e)
                })
            }
        }
        sdk(Capability.HVAC_TARGET) {
            v.getHVAC().registerHvacTemperature(SeatArea(SeatValue.ROW_1_LEFT), object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) { temperature?.let { onTemp(driver = it) } }
                override fun onFailed(e: Exception) = failed(Capability.HVAC_TARGET, e)
            })
            v.getHVAC().registerHvacTemperature(SeatArea(SeatValue.ROW_1_RIGHT), object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) { temperature?.let { onTemp(passenger = it) } }
                override fun onFailed(e: Exception) = failed(Capability.HVAC_TARGET, e)
            })
        }
        sdk(Capability.TURN_SIGNAL) {
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
                    val on = state != null && state != TurnSignalValue.OFF
                    changed("turn", on) { TurnSignal(it, on) }
                }
                override fun onFailed(e: Exception) = failed(Capability.TURN_SIGNAL, e)
            })
        }
        sdk(Capability.LOW_BEAM) {
            v.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) { state?.let { s -> (s == LightState.ON).let { on -> changed("low", on) { LowBeam(it, on) } } } }
                override fun onFailed(e: Exception) = failed(Capability.LOW_BEAM, e)
            })
        }
        sdk(Capability.HIGH_BEAM) {
            v.getLight().registerHighBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(state: LightState?) { state?.let { s -> (s == LightState.ON).let { on -> changed("high", on) { HighBeam(it, on) } } } }
                override fun onFailed(e: Exception) = failed(Capability.HIGH_BEAM, e)
            })
        }
        sdk(Capability.FUEL) {
            v.getFuel().registerFuelPercent(object : FuelPercentListener {
                override fun onFuelPercentUpdated(value: Int?) { value?.let { p -> changed("fuel", p) { FuelPercent(it, p) } } }
                override fun onFailed(e: Exception) = failed(Capability.FUEL, e)
            })
        }
        // electric cars have no fuel level: the battery state of charge (%) stands in for it (FuelPercent)
        sdk(Capability.FUEL) {
            v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    val hasFuel = synchronized(this@PleosVehicleAdapter) { last.containsKey("fuel") }   // hybrid: fuel wins
                    if (hasFuel) return
                    value?.let { p -> changed("soc", p) { FuelPercent(it, p.coerceIn(0, 100)) } }
                    caps.markSupported(Capability.FUEL)
                }
                override fun onFailed(e: Exception) = Unit                  // combustion car: fuel level only
            })
        }
        sdk(Capability.DOOR) {
            v.getDoor().registerDoorPosition(DoorArea(DoorValue.REAR), object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) { pos?.let { p -> (p > 0).let { open -> changed("trunk", open) { TrunkOpen(it, open) } } } }
                override fun onFailed(e: Exception) = failed(Capability.DOOR, e)
            })
        }
        pollWiper()
    }

    @Synchronized override fun stop() {
        main.removeCallbacksAndMessages(null)
        vehicle = null                                   // the shared connection stays (ambient, window control)
    }

    // ------------------------------------------------------------------ folding per-seat values

    private fun onOccupancy(seat: SeatPos, occupied: Boolean) {
        val snapshot = synchronized(this) {
            if (occupancy[seat] == occupied) return
            occupancy[seat] = occupied
            if (occupancy.size < seatValue.size) return   // wait until every seat has reported once
            occupancy.toMap()
        }
        out.emit { SeatOccupancy(it, snapshot) }
    }

    private fun onWindow(seat: SeatPos, percent: Int) {
        val snapshot = synchronized(this) {
            if (windows[seat] == percent) return
            windows[seat] = percent
            if (windows.size < windowValue.size) return     // wait until every window has reported once
            windows.toMap()
        }
        out.emit { WindowPos(it, snapshot) }
    }

    private fun onTemp(driver: Float? = null, passenger: Float? = null) {
        caps.markSupported(Capability.HVAC_TARGET)       // HVAC can report "not available" while powered off
        val (d, p) = synchronized(this) {
            if (driver != null) { if (driver == driverC) return; driverC = driver }
            if (passenger != null) { if (passenger == passengerC) return; passengerC = passenger }
            (driverC ?: return) to (passengerC ?: return)
        }
        out.emit { HvacTarget(it, d, p) }
    }

    /** WiperSwitch has no listener (WiperState is only ON/OFF): poll every 2 s, emit on change. */
    private fun pollWiper() {
        val v = vehicle ?: return
        sdk(Capability.WIPER) {
            v.getWiper().getCurrentWiperSwitch(WindowArea(WindowValue.FRONT_WINDSHIELD), { sw ->
                val mode = sw.toMode()
                val changed = synchronized(this) { (mode != lastWiper).also { lastWiper = mode } }
                if (changed) out.emit { Wiper(it, mode) }
            }, { e -> failed(Capability.WIPER, e) })
        }
        main.postDelayed(::pollWiper, WIPER_POLL_MS)
    }

    // ------------------------------------------------------------------ helpers

    private val last = mutableMapOf<String, Any>()

    /** Emits only when [value] differs from the last one sent under [key] (SPEC 4.2 값이 바뀔 때만). */
    private fun changed(key: String, value: Any, make: (Long) -> ai.carbuddy.signal.SignalEvent) {
        val fresh = synchronized(this) { (last.put(key, value) != value) }
        if (fresh) out.emit(make)
    }

    private inline fun sdk(cap: Capability, block: () -> Unit) {
        try { block() } catch (e: Exception) { failed(cap, e) }
    }

    private fun failed(cap: Capability, e: Exception) {
        if (cap !in caps.unsupportedLive) Timber.tag(TAG).w("%s unavailable: %s", cap, e.message)
        caps.markUnsupported(cap)
    }

    private fun VehicleGear.toPos(): GearPos = when (this) {
        VehicleGear.GEAR_PARK -> GearPos.P
        VehicleGear.GEAR_REVERSE -> GearPos.R
        VehicleGear.GEAR_NEUTRAL -> GearPos.N
        VehicleGear.GEAR_UNKNOWN -> GearPos.UNKNOWN
        else -> GearPos.D                                  // GEAR_DRIVE, GEAR_1~9
    }

    private fun WiperSwitch?.toMode(): WiperMode = when (this) {
        null, WiperSwitch.OFF, WiperSwitch.OTHER, WiperSwitch.SERVICE -> WiperMode.OFF
        WiperSwitch.AUTO -> WiperMode.AUTO
        WiperSwitch.CONTINUOUS_LEVEL_3, WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> WiperMode.HIGH
        else -> WiperMode.LOW                              // MIST, intermittent 1–5, continuous 1–2
    }

    private companion object {
        const val TAG = "CB/pleos"
        const val WIPER_POLL_MS = 2_000L
        val VEHICLE_FAMILIES = listOf(
            Capability.GEAR, Capability.DRIVER_BELT, Capability.SEAT_OCCUPANCY, Capability.WIPER, Capability.WINDOW,
            Capability.LOW_BEAM, Capability.HIGH_BEAM, Capability.HVAC_TARGET, Capability.TURN_SIGNAL, Capability.FUEL, Capability.DOOR,
        )
    }
}
