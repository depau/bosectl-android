package eu.depau.bosectl.service

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
import eu.depau.bosectl.R
import eu.depau.bosectl.data.DeviceRepository
import eu.depau.bosectl.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the BMAP connection while the earbuds are around, so on-device changes
 * reach the app and the widget without opening anything.
 *
 * The earbuds never push state (verified: no unsolicited frames in 40s while
 * changing modes on the buds), so the repository polls — slowly while this
 * service is the only thing running, quickly when a screen is on top.
 */
class BoseConnectionService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DeviceRepository.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(null))
        // The ACL receiver started us — not a user action, so automatic.
        DeviceRepository.onDeviceAppeared(automatic = true)
        watchJob?.cancel()
        watchJob = scope.launch {
            DeviceRepository.state.collectLatest { state ->
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state.deviceName))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private val notificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Headphone connection",
                // Silent and collapsed: this only exists to keep the link alive.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(deviceName: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(deviceName ?: "Bose Control")
            .setContentText("Connected — controls stay up to date")
            .setSmallIcon(R.drawable.ic_headphones)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, BoseConnectionService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BoseConnectionService::class.java))
        }
    }
}
