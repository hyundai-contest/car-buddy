package ai.carbuddy.ceremony

import ai.carbuddy.core.model.AmbientColor
import ai.carbuddy.core.model.Capability
import ai.carbuddy.core.model.SeatPos
import ai.carbuddy.data.AmbientBackupEntity
import ai.carbuddy.data.TripStore
import ai.carbuddy.episode.CapabilityRegistry
import ai.carbuddy.signal.adapter.pleos.PleosVehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Ceremony ambient light on the Pleos Vehicle SDK (SPEC 10장, signals_phase0.md: verified on the
 * emulator with get/setAmbientLightBrightnessColor, area from checkAmbientLightBrightnessColorCapabilities).
 *
 * save() reads the car's colour and keeps it in ambient_backup BEFORE apply() changes anything, so
 * apply waits for that read. A backup already in the table is the true original (a ceremony that
 * died before restoring) and is never overwritten. restore() / recoverIfNeeded() put it back and
 * clear it. Colours are the four the emulator console offers, sent as ARGB with alpha 0xFF (SDK doc).
 * On the emulator the VHAL receives the colour (sdv_vhal "set ambient lights color") but the
 * console page and an immediate getAmbient… read still show the old value (11/8).
 */
class PleosAmbientController(
    private val pleos: PleosVehicle,
    private val store: TripStore,
    private val caps: CapabilityRegistry,
    private val now: () -> Long = System::currentTimeMillis,
) : AmbientController {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cb-ambient") }
    private var area: SeatArea = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private var areaChecked = false
    private var backupReady = false
    private var pendingColor: AmbientColor? = null

    override fun save() = io.execute {
        val light = light() ?: return@execute
        backupReady = false
        if (store.ambientBackup() != null) { backupReady = true; return@execute }
        try {
            light.getAmbientLightBrightnessColor(area, { color ->
                io.execute {
                    if (color == null) { Timber.tag(TAG).w("ambient read returned null: not changing the light"); return@execute }
                    store.saveAmbientBackup(AmbientBackupEntity(brightness = -1, color = color, savedAt = now()))
                    backupReady = true
                    Timber.tag(TAG).i("ambient saved #%06x", color and 0xFFFFFF)
                    pendingColor?.let { pendingColor = null; set(it.rgb) }
                }
            }, { e -> unsupported("get", e) })
        } catch (e: Exception) { unsupported("get", e) }
    }

    override fun apply(color: AmbientColor) = io.execute {
        Timber.tag(TAG).d("ambient apply %s (backup ready=%b)", color, backupReady)
        if (backupReady) set(color.rgb) else pendingColor = color      // never paint before the original is safe
    }

    override fun restore() = io.execute {
        pendingColor = null
        backupReady = false
        val b = store.ambientBackup() ?: return@execute
        set(b.color) { store.saveAmbientBackup(null); Timber.tag(TAG).i("ambient restored #%06x", b.color and 0xFFFFFF) }
    }

    override fun recoverIfNeeded() {
        if (store.ambientBackup() != null) { Timber.tag(TAG).i("ambient backup left behind: restoring"); restore() }
    }

    private fun set(rgb: Int, done: () -> Unit = {}) {
        val light = light() ?: return
        try {
            Timber.tag(TAG).d("ambient set #%06x area=%s", rgb and 0xFFFFFF, area.values.toList())
            light.setAmbientLightBrightnessColor(area, rgb or 0xFF000000.toInt(), { _, v ->
                Timber.tag(TAG).d("ambient set ok #%06x", v and 0xFFFFFF)
                io.execute(done)
            }, { e -> unsupported("set", e) })
        } catch (e: Exception) { unsupported("set", e) }
    }

    private fun light() = pleos.get()?.getLight()?.also { l ->
        if (!areaChecked) {
            areaChecked = true
            runCatching {
                l.checkAmbientLightBrightnessColorCapabilities({ cfg, areas ->
                    Timber.tag(TAG).d("ambient caps %s areas=%s", cfg, areas.map { it.values.toList() })
                    areas.firstOrNull()?.let { area = it }
                }, { e -> unsupported("capabilities", e) })
            }
        }
    }

    private fun unsupported(what: String, e: Exception) {
        Timber.tag(TAG).w("ambient %s failed: %s", what, e.message)
        caps.markUnsupported(Capability.AMBIENT)
    }

    private val AmbientColor.rgb: Int get() = when (this) {
        AmbientColor.CYAN -> 0x00E5FF
        AmbientColor.BLUE -> 0x2979FF
        AmbientColor.PURPLE -> 0xAA00FF
        AmbientColor.WHITE -> 0xFFFFFF
    }

    private companion object { const val TAG = "CB/ambient" }
}

/**
 * Exit-check window close (확장 B) via Window.setWindowPosition (CONTROL_CAR_WINDOWS). Position 0 =
 * closed on the SDK's 0–10 scale. Called only from a touch while parked (TripRuntime checks the gate).
 */
class PleosVehicleControl(private val pleos: PleosVehicle) : VehicleControlPort {
    override fun closeWindow(seat: SeatPos): Boolean {
        val window = pleos.get()?.getWindow() ?: return false
        val value = when (seat) {
            SeatPos.DRIVER -> WindowValue.ROW_1_LEFT
            SeatPos.PASSENGER -> WindowValue.ROW_1_RIGHT
            SeatPos.REAR_LEFT -> WindowValue.ROW_2_LEFT
            SeatPos.REAR_RIGHT -> WindowValue.ROW_2_RIGHT
        }
        return try {
            window.setWindowPosition(WindowArea(value), 0, { _, _ -> Timber.tag("CB/exit").i("window %s closing", seat) },
                { e -> Timber.tag("CB/exit").w("window %s close failed: %s", seat, e.message) })
            true
        } catch (e: Exception) {
            Timber.tag("CB/exit").w("window close refused: %s", e.message); false
        }
    }
}
