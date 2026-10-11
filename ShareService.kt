package com.remoteassist.fyp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Keeps the session alive while the user uses other apps (Android requires a visible
 * notification for this). Owns the Session object.
 */
class ShareService : Service() {

    private val listener: () -> Unit = { onStateChanged() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("session", "Active session", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        AppState.listeners.add(listener)
    }

    override fun onDestroy() {
        AppState.listeners.remove(listener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "owner" -> {
                if (!goForeground(true)) return START_NOT_STICKY
                val data = intent.getParcelableExtra<Intent>("data")
                if (data == null) { fail("Screen sharing permission was missing."); return START_NOT_STICKY }
                AppState.reset()
                AppState.role = Role.OWNER
                AppState.code = Crypto.newCode()
                AppState.phase = Phase.CONNECTING
                val s = Session(applicationContext, Role.OWNER, AppState.code)
                AppState.session = s
                AppState.changed()
                s.startOwner(data)
            }
            "helper" -> {
                if (!goForeground(false)) return START_NOT_STICKY
                val code = intent.getStringExtra("code") ?: ""
                AppState.reset()
                AppState.role = Role.HELPER
                AppState.code = code
                AppState.phase = Phase.CONNECTING
                val s = Session(applicationContext, Role.HELPER, code)
                AppState.session = s
                AppState.changed()
                s.startHelper()
            }
            "takeback" -> AppState.session?.setControl(false)
            "stop" -> AppState.session?.end("You ended the session.")
        }
        return START_NOT_STICKY
    }

    private fun fail(msg: String) {
        AppState.role = AppState.role ?: Role.OWNER
        AppState.message = msg
        AppState.phase = Phase.ENDED
        AppState.changed()
        stopSelf()
    }

    private fun goForeground(owner: Boolean): Boolean {
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                val t = if (owner)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(1, n, t)
            } else if (Build.VERSION.SDK_INT == 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(1, n)
            }
            true
        } catch (e: Exception) {
            fail("Android did not allow the background service: ${e.message}")
            false
        }
    }

    private fun onStateChanged() {
        if (AppState.phase == Phase.ENDED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        }
    }

    private fun pi(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, ShareService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val owner = AppState.role != Role.HELPER
        val title: String
        val text: String
        when (AppState.phase) {
            Phase.LIVE -> {
                title = if (owner) "Sharing your screen" else "Connected to a session"
                text = if (owner && AppState.controlGranted) "Your helper has control. Tap to open."
                else "Tap to open RemoteAssist."
            }
            Phase.WAITING_FOR_HELPER -> { title = "Waiting for your helper"; text = "Code: " + AppState.code.chunked(4).joinToString(" ") }
            Phase.WAITING_APPROVAL -> { title = "Someone joined"; text = "Open the app to allow or decline." }
            else -> { title = "RemoteAssist"; text = "Connecting…" }
        }
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        val b = Notification.Builder(this, "session")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (owner && AppState.controlGranted) {
            b.addAction(Notification.Action.Builder(icon, "Take back control", pi("takeback")).build())
        }
        b.addAction(Notification.Action.Builder(icon, "End session", pi("stop")).build())
        return b.build()
    }
}
