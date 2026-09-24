package dev.asenascale

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
import android.os.PowerManager

/**
 * Foreground service that exists only while a terminal session is open, so
 * Android doesn't kill the SSH connection when you switch apps or lock the
 * screen.
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra("count", 0) ?: 0
        if (count <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(count)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asenascale:ssh")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(count: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Açık oturumlar", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "1 terminal açık" else "$count terminal açık")
            .setContentText("Tailscale üzerinden bağlı")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"

        fun update(context: Context, openSessions: Int) {
            val i = Intent(context, KeepAliveService::class.java).putExtra("count", openSessions)
            // Starting a foreground service from the background can be refused
            // on newer Android; the running one simply keeps its old count.
            runCatching {
                if (openSessions > 0) context.startForegroundService(i) else context.stopService(i)
            }
        }
    }
}
