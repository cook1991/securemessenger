package net.spritzlive.securemessenger.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.net.RelayConnectionService

/**
 * Handles a reply typed directly into the notification shade, without opening the
 * app. RelayConnectionService is already alive in the background for the app's
 * entire lifetime (its own doc comment) — this binds to it just long enough to call
 * sendMessage, rather than constructing a second NetworkRepository, which the
 * service explicitly guards against (never two live connections registering as the
 * same relay peer at once).
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (replyText.isEmpty()) return

        // Extends this receiver's lifetime past onReceive's return — the bind/send/
        // unbind sequence below is async and would otherwise be killed mid-flight.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, RelayConnectionService::class.java)
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as RelayConnectionService.LocalBinder).getService()
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { service.repository.sendMessage(replyText) }
                    Notifications.dismissIncoming(appContext)
                    appContext.unbindService(connection)
                    pendingResult.finish()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }
}
