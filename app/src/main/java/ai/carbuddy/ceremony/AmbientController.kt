package ai.carbuddy.ceremony

import ai.carbuddy.core.model.AmbientColor
import timber.log.Timber

/**
 * Ambient light during the ceremony (SPEC 10장): save → apply(colour) → restore.
 * Implementations must persist the saved value (ambient_backup) so a crash or ignition-off is
 * restored on the next boot. Pleos calls (Light.get/setAmbientLightBrightnessColor) live only in
 * the Pleos implementation (CLAUDE.md 규칙 2).
 */
interface AmbientController {
    fun save()
    fun apply(color: AmbientColor)
    fun restore()
    /** On boot: restore if a backup was left behind. */
    fun recoverIfNeeded() {}
}

/** Used when the car has no ambient capability or the setting is off: the screen colour does the job. */
object NoAmbient : AmbientController {
    override fun save() = Unit
    override fun apply(color: AmbientColor) { Timber.tag("CB/ceremony").d("ambient (screen only) %s", color) }
    override fun restore() = Unit
}

/** Vehicle controls the exit check may use while parked (확장 B). */
interface VehicleControlPort {
    /** Closes one window. Only on explicit user touch while parked. @return false if unsupported. */
    fun closeWindow(seat: ai.carbuddy.core.model.SeatPos): Boolean

    object None : VehicleControlPort {
        override fun closeWindow(seat: ai.carbuddy.core.model.SeatPos) = false
    }
}
