package net.spritzlive.securemessenger.net.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.R

private const val HERMES_CHANNEL_ID = "securemessenger_hermes"
private const val HERMES_FOREGROUND_ID = 3

/**
 * Keeps the Hermes link (WebSocket + ntfy wake stream) alive independent of the
 * app's UI, same shape as RelayConnectionService but a fully separate Service/
 * notification/instance — intentionally no shared code path with the primary
 * contact's connection, so this feature being new and less battle-tested can't
 * regress it.
 */
class HermesConnectionService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    lateinit var repository: HermesRepository
        private set

    inner class LocalBinder : Binder() {
        fun getService(): HermesConnectionService = this@HermesConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        repository = HermesRepository(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForeground(HERMES_FOREGROUND_ID, foregroundNotification())
            if (repository.isPaired) scope.launch { repository.connect() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repository.disconnect()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(HERMES_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(HERMES_CHANNEL_ID, "Hermes agent", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Connection status for the Hermes agent link"
                },
            )
        }
    }

    private fun foregroundNotification(): Notification =
        NotificationCompat.Builder(this, HERMES_CHANNEL_ID)
            .setContentTitle("Hermes")
            .setContentText("Agent link active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
