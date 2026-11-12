package ai.carbuddy.ui

import ai.carbuddy.MainActivity
import ai.carbuddy.R
import ai.carbuddy.story.CopyBook
import ai.carbuddy.trip.TripRuntime
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * System notifications (UX_SPEC 3장 알림): N0 recording (low importance, no heads-up),
 * N1 arrival, N4 next ride. Car Buddy never launches a screen by itself.
 */
class Notifier(private val context: Context, private val copy: CopyBook) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, context.getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ARRIVE, context.getString(R.string.arrive_channel_name), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** N0 — TODO(SDK): Pleos policy for third-party foreground-service notifications (SPEC 19장). */
    fun serviceNotification(recording: Boolean = true): Notification = Notification.Builder(context, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_buddy)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(copy.get(if (recording) "trip_fgs_body" else "trip_fgs_off"))
        .setContentIntent(openApp(null, false))
        .setOngoing(true)
        .build()

    fun updateService(recording: Boolean) = nm.notify(ID_SERVICE, serviceNotification(recording))

    /** N1: arrived + P while the app is off screen. */
    fun arrival(tripId: String, episodes: Int) = post(
        ID_ARRIVE, tripId, copy.get("arrive_noti_title", "n" to episodes), copy.get("arrive_noti_body"), summary = false,
    )

    /** N4: next ride, parked, an unseen special card (SPEC 10.2). Cancelled when Gear D. */
    fun nextRide(tripId: String, whenWord: String, place: String) = post(
        ID_NEXT, tripId, copy.get("noti_next_body", "when" to whenWord, "place" to place), copy.get("arrive_noti_body"), summary = true,
    )

    /** N5 중간 상영 (NEXT_DESIGN A): back in the seat at a rest stop, app off screen. Cancelled at gear D. */
    fun halftime(tripId: String) = post(
        ID_HALF, tripId, copy.get("halftime_noti_title"), copy.get("halftime_noti_body"), summary = false, halftime = true,
    )

    fun cancelArrival() = nm.cancel(ID_ARRIVE)
    fun cancelNext() = nm.cancel(ID_NEXT)
    fun cancelHalftime() = nm.cancel(ID_HALF)

    private fun post(id: Int, tripId: String, title: String, body: String, summary: Boolean, halftime: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val later = PendingIntent.getBroadcast(
            context, id, Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_LATER)
                .putExtra(EXTRA_TRIP, tripId).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CH_ARRIVE)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp(tripId, summary, halftime))
            .addAction(Notification.Action.Builder(null, copy.get("arrive_noti_view"), openApp(tripId, summary, halftime)).build())
            .addAction(Notification.Action.Builder(null, copy.get("arrive_noti_later"), later).build())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        nm.notify(id, n)
    }

    private fun openApp(tripId: String?, summary: Boolean, halftime: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, if (tripId == null) 0 else if (halftime) 3 else if (summary) 2 else 1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply {
                tripId?.let {
                    if (halftime) putExtra(MainActivity.EXTRA_HALFTIME_TRIP, it)
                    else { putExtra(MainActivity.EXTRA_SCREENING_TRIP, it); putExtra(MainActivity.EXTRA_SUMMARY, summary) }
                }
            },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CH_SERVICE = "recording"
        const val CH_ARRIVE = "arrival"
        const val ID_SERVICE = 1
        const val ID_ARRIVE = 2
        const val ID_NEXT = 3
        const val ID_HALF = 4
        const val ACTION_LATER = "ai.carbuddy.LATER"
        const val EXTRA_TRIP = "trip"
        const val EXTRA_ID = "id"
    }
}

/** [나중에]: dismiss and count the card as ignored (3 → archive only, SPEC 10.2). */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var runtime: TripRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_LATER) return
        val id = intent.getIntExtra(Notifier.EXTRA_ID, Notifier.ID_ARRIVE)
        context.getSystemService(NotificationManager::class.java).cancel(id)
        if (id == Notifier.ID_HALF) return                  // 중간 상영: no card is being skipped
        intent.getStringExtra(Notifier.EXTRA_TRIP)?.let { runtime.ignored(it) }
    }
}
