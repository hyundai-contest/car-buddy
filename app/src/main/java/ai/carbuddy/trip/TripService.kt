package ai.carbuddy.trip

import ai.carbuddy.ui.Notifier
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Keeps recording while the app is off screen (UX_SPEC 0장: 기록 서비스만 유지).
 * No UI work happens here; it only keeps the runtime alive.
 */
@AndroidEntryPoint
class TripService : Service() {
    @Inject lateinit var runtime: TripRuntime
    @Inject lateinit var notifier: Notifier

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true) runtime.bootReason = ai.carbuddy.signal.BootReason.BOOT
        // Location type only when the user granted it AND the system lets us: a location FGS cannot
        // start from the background (boot broadcast) without background location → SecurityException
        // seen on the emulator (11/8). Then specialUse alone; Pleos FusedLocation keeps working
        // through its own service binding.
        val located = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notification = notifier.serviceNotification()
        try {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (located) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(Notifier.ID_SERVICE, notification, type)
        } catch (e: SecurityException) {
            timber.log.Timber.tag("CB/runtime").w("location FGS refused (%s), specialUse only", e.message?.take(80))
            startForeground(Notifier.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        runtime.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_BOOT = "boot"

        fun start(context: Context, boot: Boolean = false) {
            runCatching { context.startForegroundService(Intent(context, TripService::class.java).putExtra(EXTRA_BOOT, boot)) }
                .onFailure { Timber.tag("CB/runtime").w(it, "could not start service") }
        }
    }
}

/** Head unit boot → start recording (the process may die at any ignition-off). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) TripService.start(context, boot = true)
    }
}
