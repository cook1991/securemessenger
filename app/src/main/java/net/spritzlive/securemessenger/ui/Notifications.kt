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
const val KEY_TEXT_REPLY = "key_text_reply"

/** MainActivity / ReplyReceiver intent extra: which contact's conversation. */
const val EXTRA_PEER_ID = "net.spritzlive.securemessenger.PEER_ID"

/**
 * Tap-to-open, plus a RemoteInput reply action handled by ReplyReceiver — see that
 * class's doc for how a reply gets from the notification shade back into
 * NetworkRepository without opening the app.
 *
 * One notification per contact, so a new message from one person doesn't replace
 * another's, and Reply goes to the right person.
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

    fun foregroundNotification(context: Context, contactNames: List<String>): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Secure Messenger")
            .setContentText(if (contactNames.size == 1) "Connected to ${contactNames[0]}" else "Connected")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    // Per-contact ids, kept clear of the fixed ones (1 = this foreground
    // notification, 3/4 = Hermes) and of each other's PendingIntent request codes.
    private fun slot(peerId: String): Int = peerId.hashCode() and 0xFFFF
    private fun notificationId(peerId: String): Int = 10_000 + slot(peerId)
    private fun tapRequestCode(peerId: String): Int = 100_000 + slot(peerId)
    private fun replyRequestCode(peerId: String): Int = 200_000 + slot(peerId)

    fun notifyIncoming(context: Context, mainActivityClass: Class<*>, peerId: String, peerDisplayName: String) {
        val intent = Intent(context, mainActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PEER_ID, peerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            tapRequestCode(peerId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Reply").build()
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            replyRequestCode(peerId),
            Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_PEER_ID, peerId),
            // Must be MUTABLE, unlike the tap-to-open intent above — RemoteInput
            // writes its result into this intent's own extras.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_reply, "Reply", replyPendingIntent)
            .addRemoteInput(remoteInput)
            .build()
        // Deliberately never shows the plaintext — a lock-screen/shade peek shouldn't
        // leak an E2EE message's content. The peer's name is fine (already known to
        // this device; it's who, not what).
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(peerDisplayName)
            .setContentText("New message received")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(peerId), notification)
    }

    fun dismissIncoming(context: Context, peerId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(peerId))
    }

    const val FOREGROUND_ID = FOREGROUND_NOTIFICATION_ID
}
