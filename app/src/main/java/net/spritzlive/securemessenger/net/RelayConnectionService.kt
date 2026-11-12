package net.spritzlive.securemessenger.net

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.ui.MainActivity
import net.spritzlive.securemessenger.ui.Notifications

/**
 * Keeps the relay socket alive and messages flowing while the app isn't in the
 * foreground. Owns the single NetworkRepository instance (one connection for every
 * contact); screens bind to this service rather than constructing their own, so
 * there's never two live connections registering as the same relay peer at once.
 *
 * [visiblePeerId] is a simple in-process flag (service and activity share the same
 * process — no IPC needed) that ThreadScreen sets while that contact's conversation
 * is actually on screen, so their notifications are suppressed exactly then.
 */
class RelayConnectionService : Service() {
    companion object {
        @Volatile
        var visiblePeerId: String? = null
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    lateinit var repository: NetworkRepository
        private set

    inner class LocalBinder : Binder() {
        fun getService(): RelayConnectionService = this@RelayConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        repository = NetworkRepository(applicationContext)
        repository.onIncomingMessage = { peerId, name ->
            if (visiblePeerId != peerId) {
                Notifications.notifyIncoming(applicationContext, MainActivity::class.java, peerId, name)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            val names = repository.contacts().map { it.displayName }
            startForeground(Notifications.FOREGROUND_ID, Notifications.foregroundNotification(this, names))
            scope.launch { repository.connect() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repository.disconnect()
        super.onDestroy()
    }
}
