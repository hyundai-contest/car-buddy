package ai.carbuddy

import ai.carbuddy.story.CopyBook
import ai.carbuddy.trip.TripRuntime
import ai.carbuddy.trip.TripService
import ai.carbuddy.ui.AppRoot
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var runtime: TripRuntime
    @Inject lateinit var copy: CopyBook

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TripService.start(this)
        runtime.start()
        setContent {
            AppRoot(
                runtime = runtime,
                copy = copy,
                debug = BuildConfig.DEBUG,
                moveToBack = { moveTaskToBack(true) },   // Pleos rule: Back at the top level hides, never finishes
                requestPermissions = ::requestAllPermissions,
                openPermissionSettings = ::allowLocation,
            )
        }
        handle(intent)
    }

    /** F0 consent step: CAR_SPEED, FusedLocation, location (+ notifications) are runtime grants. */
    private fun requestAllPermissions() {
        val wanted = SIGNAL_PERMISSIONS + (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_SIGNALS)
    }

    /** Home status card (a): ask again, or open the app's settings when the system no longer asks. */
    private fun allowLocation() {
        val p = Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return
        if (askedOnce && !shouldShowRequestPermissionRationale(p)) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        } else requestAllPermissions()
    }

    private var askedOnce = false

    private fun reportPermissions() = runtime.setLocationGranted(
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(ai.carbuddy.signal.adapter.location.LocationAdapter.FUSED_PERMISSION) == PackageManager.PERMISSION_GRANTED,
    )

    override fun onResume() { super.onResume(); reportPermissions(); runtime.refreshAdapters() }

    @Deprecated("platform callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askedOnce = true
        reportPermissions()
        if (requestCode == REQ_SIGNALS && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            runtime.refreshAdapters()
            TripService.start(this)          // re-enter foreground with the location type
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() { super.onStart(); runtime.uiVisible = true }
    override fun onStop() { runtime.uiVisible = false; super.onStop() }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SCREENING_TRIP)?.let { runtime.openScreening(it, intent.getBooleanExtra(EXTRA_SUMMARY, false), fromNotification = true) }
        intent?.removeExtra(EXTRA_SCREENING_TRIP)
        intent?.getStringExtra(EXTRA_HALFTIME_TRIP)?.let { runtime.openHalftime(it, fromNotification = true) }
        intent?.removeExtra(EXTRA_HALFTIME_TRIP)
    }


    companion object {
        const val EXTRA_SCREENING_TRIP = "screening_trip"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_HALFTIME_TRIP = "halftime_trip"
        private const val REQ_SIGNALS = 2
        private val SIGNAL_PERMISSIONS = listOf("android.car.permission.CAR_SPEED", ai.carbuddy.signal.adapter.location.LocationAdapter.FUSED_PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
