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
 * foreground — without this, NetworkRepository's connection only existed as long as
 * ThreadScreen was composed (foreground-only, per Phase 6's first pass). Owns the
 * single NetworkRepository instance for the app's lifetime; ThreadScreen/ViewModel
 * bind to this service rather than constructing their own, so there's never two
 * live connections registering as the same relay peer at once (that was a real bug
 * fixed earlier this phase with the old TempRelayClientSpike).
 *
 * [RelayConnectionService.isThreadVisible] is a simple in-process flag (service and
 * activity share the same process — no IPC needed) that ThreadScreen sets while
 * composed, so incoming-message notifications are suppressed exactly when the user
 * is already looking at the conversation.
 *
 */
class RelayConnectionService : Service() {
    companion object {
        @Volatile
        var isThreadVisible: Boolean = false
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
        repository.onIncomingMessage = {
            if (!isThreadVisible) {
                Notifications.notifyIncoming(applicationContext, MainActivity::class.java, repository.displayName)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startForeground(Notifications.FOREGROUND_ID, Notifications.foregroundNotification(this, repository.displayName))
            scope.launch { repository.connect() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repository.disconnect()
        super.onDestroy()
    }
}
