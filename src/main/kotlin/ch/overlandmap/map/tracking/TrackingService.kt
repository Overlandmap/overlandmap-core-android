package ch.overlandmap.map.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the tracking notification says; null parts mean that activity is not running. */
data class TrackingStatus(val recording: String? = null, val navigation: String? = null) {
    val active: Boolean get() = recording != null || navigation != null
}

/**
 * Keeps the process alive, with a persistent notification (the Android counterpart of the iOS Live
 * Activities), while a track is being recorded or a navigation is running, so GPS fixes keep
 * arriving with the screen off. It follows [OverlandApp.trackingStatus] and stops itself when
 * nothing is active; its Stop actions end the recording or the navigation.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun keepAwake() {
        if (wakeLock == null) wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:${javaClass.simpleName}")
            .apply { acquire() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as OverlandApp
        when (intent?.action) {
            ACTION_STOP_RECORDING -> app.trackRecorder.stopRecording()
            ACTION_STOP_NAVIGATION -> app.navigationCoordinator.stopNavigation("notification stop button")
        }
        startAsForeground(notification(app.trackingStatus.value))
        keepAwake()
        if (job == null) {
            job = scope.launch {
                app.trackingStatus.collect { status ->
                    if (!status.active) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(status: TrackingStatus): Notification {
        ensureChannel(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        fun stopAction(action: String, label: Int, code: Int) = NotificationCompat.Action(
            0, getString(label),
            PendingIntent.getService(
                this, code, Intent(this, TrackingService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val lines = listOfNotNull(status.navigation, status.recording)
        builder.setContentTitle(getString(if (status.navigation != null) R.string.nav else R.string.recording_notification_title))
        builder.setContentText(lines.firstOrNull().orEmpty())
        if (lines.size > 1) builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        if (status.recording != null) builder.addAction(stopAction(ACTION_STOP_RECORDING, R.string.stop_recording, 1))
        if (status.navigation != null) builder.addAction(stopAction(ACTION_STOP_NAVIGATION, R.string.stop_navigation, 2))
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_STOP_RECORDING = "ch.overlandmap.map.STOP_RECORDING"
        private const val ACTION_STOP_NAVIGATION = "ch.overlandmap.map.STOP_NAVIGATION"

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }

        /** Starts the service; call when a recording or a navigation begins. It stops itself when both are over. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
            } catch (_: Exception) {
                // Starting a foreground service from the background can be refused; the recording still runs in the foreground.
            }
        }
    }
}
