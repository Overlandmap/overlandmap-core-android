package ch.overlandmap.map.bluetooth

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import kotlinx.coroutines.*

/** Keeps the link and GPS broadcaster alive when the display is off. */
class BluetoothGPSForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gps = (application as OverlandApp).bluetoothGPS
        if (intent?.action == STOP) {
            gps.disableClient(); gps.stopServer(); stopSelf(); return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bluetooth_gps), NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(getString(R.string.bluetooth_gps))
            .setContentText(getString(if ((gps.state.value.server || gps.state.value.startingServer)) R.string.bluetooth_gps_server else R.string.bluetooth_gps_client))
            .setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.bluetooth_gps_stop), PendingIntent.getService(this, 1,
                Intent(this, BluetoothGPSForegroundService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (gps.hardwareServer) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(7812, notification, type)
        } else startForeground(7812, notification)
        if (watcher == null) watcher = scope.launch {
            gps.state.collect {
                if (!it.server && !it.startingServer && !it.connected) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "bluetooth-gps"
        private const val STOP = "ch.overlandmap.STOP_BLUETOOTH_GPS"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, BluetoothGPSForegroundService::class.java))
    }
}
