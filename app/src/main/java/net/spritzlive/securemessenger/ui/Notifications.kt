package net.spritzlive.securemessenger.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import net.spritzlive.securemessenger.R

private const val CHANNEL_ID = "securemessenger_messages"
private const val FOREGROUND_NOTIFICATION_ID = 1
private const val MESSAGE_NOTIFICATION_ID = 2
const val KEY_TEXT_REPLY = "key_text_reply"

/**
 * Tap-to-open, plus a RemoteInput reply action handled by ReplyReceiver — see that
 * class's doc for how a reply gets from the notification shade back into
 * NetworkRepository without opening the app.
 */
object Notifications {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "New messages and connection status"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun foregroundNotification(context: Context, peerDisplayName: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Secure Messenger")
            .setContentText("Connected to $peerDisplayName")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    fun notifyIncoming(context: Context, mainActivityClass: Class<*>, peerDisplayName: String, body: String) {
        val intent = Intent(context, mainActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Reply").build()
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReplyReceiver::class.java),
            // Must be MUTABLE, unlike the tap-to-open intent above — RemoteInput
            // writes its result into this intent's own extras.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_reply, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(peerDisplayName)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .build()
        NotificationManagerCompat.from(context).notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    fun dismissIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
    }

    const val FOREGROUND_ID = FOREGROUND_NOTIFICATION_ID
}
