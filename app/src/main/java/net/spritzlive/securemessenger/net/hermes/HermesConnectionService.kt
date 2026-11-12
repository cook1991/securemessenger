package net.spritzlive.securemessenger.net.hermes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.R
import net.spritzlive.securemessenger.ui.MainActivity

private const val HERMES_CHANNEL_ID = "securemessenger_hermes"
private const val HERMES_MESSAGES_CHANNEL_ID = "securemessenger_hermes_messages"
private const val HERMES_FOREGROUND_ID = 3
private const val HERMES_MESSAGE_NOTIFICATION_ID = 4

// Distinct from the primary contact's notification PendingIntent (request code 0):
// with FLAG_UPDATE_CURRENT, sharing a request code would overwrite one intent's
// extras with the other's.
private const val OPEN_HERMES_REQUEST_CODE = 1

/**
 * Keeps the Hermes link (WebSocket + ntfy wake stream) alive independent of the
 * app's UI, same shape as RelayConnectionService but a fully separate Service/
 * notification/instance — intentionally no shared code path with the primary
 * contact's connection, so this feature being new and less battle-tested can't
 * regress it.
 */
class HermesConnectionService : Service() {
    companion object {
        /** Set by HermesScreen while it's actually on screen, so a new-message
         * notification is skipped when the conversation is already visible. */
        @Volatile
        var isScreenVisible: Boolean = false

        /** MainActivity intent extra: open straight to the Hermes conversation. */
        const val EXTRA_OPEN_HERMES = "net.spritzlive.securemessenger.OPEN_HERMES"

        fun dismissIncoming(context: Context) {
            NotificationManagerCompat.from(context).cancel(HERMES_MESSAGE_NOTIFICATION_ID)
        }
    }

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
        ensureChannels()
        repository = HermesRepository(applicationContext)
        repository.onIncomingMessage = {
            if (!isScreenVisible) notifyIncoming()
        }
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

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(HERMES_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(HERMES_CHANNEL_ID, "Hermes agent", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Connection status for the Hermes agent link"
                },
            )
        }
        // Separate from the status channel above: that one is deliberately silent
        // (IMPORTANCE_LOW), while new messages should make a sound / banner.
        if (manager.getNotificationChannel(HERMES_MESSAGES_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(HERMES_MESSAGES_CHANNEL_ID, "Hermes messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New messages from your Hermes agent"
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

    private fun notifyIncoming() {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_HERMES, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            OPEN_HERMES_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Never the message text — same rule as the primary contact's notification:
        // a lock-screen/shade peek shouldn't show content.
        val notification = NotificationCompat.Builder(this, HERMES_MESSAGES_CHANNEL_ID)
            .setContentTitle("Hermes")
            .setContentText("New message received")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(HERMES_MESSAGE_NOTIFICATION_ID, notification)
    }
}
