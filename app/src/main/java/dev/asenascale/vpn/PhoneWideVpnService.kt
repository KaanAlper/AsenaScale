package dev.asenascale.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.asenascale.App
import dev.asenascale.MainActivity
import dev.asenascale.R
import java.util.concurrent.Executors

/**
 * The phone-wide VPN. It routes only the tailnet ranges into a TUN device,
 * whose packets tsbridge terminates in a userspace TCP/IP stack and dials
 * through the embedded Tailscale node. Everything else keeps using the
 * normal network. This app itself is left out of the VPN, so its own
 * Tailscale traffic and SSH forwards never loop through it.
 */
class PhoneWideVpnService : VpnService() {
    private var current: VpnAddresses? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            App.instance.phoneWide.setEnabled(false)
            shutdown()
            return START_NOT_STICKY
        }
        // Foreground first: Android gives a started foreground service 5 s.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification())
        }
        running = this
        // Whatever the app wants right now, not what it wanted when this
        // start was sent (the switch may have been flipped off since), and
        // the same when the system starts us (always-on VPN).
        val want = App.instance.phoneWide.currentTarget()
        if (want == null) {
            shutdown()
            return START_NOT_STICKY
        }
        // Also when the tunnel died underneath (Go stops serving a failed device).
        if (want != current || !tsbridge.Tsbridge.vpnActive()) establish(want)
        return START_NOT_STICKY
    }

    /** Bumped on every establish/shutdown, so a late worker result is dropped. */
    private var generation = 0

    private fun establish(a: VpnAddresses) {
        current = null
        val gen = ++generation
        try {
            val b = Builder()
                .setSession(getString(R.string.phone_wide))
                .setMtu(VPN_MTU)
                .setBlocking(false)
            a.ipv4?.let { b.addAddress(it, 32) }
            a.ipv6?.let { b.addAddress(it, 128) }
            for ((ip, bits) in TAILNET_ROUTES) {
                if (':' in ip && a.ipv6 == null) continue
                if ('.' in ip && a.ipv4 == null) continue
                b.addRoute(ip, bits)
            }
            b.addDisallowedApplication(packageName)
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            b.setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            // Null when the permission was taken away in the meantime.
            val pfd = b.establish() ?: throw IllegalStateException("VPN permission missing")
            current = a
            // Go owns the descriptor from here (it replaces any running
            // tunnel) and closes it on stopVPN. Off the main thread, in order.
            val fd = pfd.detachFd().toLong()
            worker.execute {
                val err = runCatching { tsbridge.Tsbridge.startVPN(fd, VPN_MTU.toLong()) }.exceptionOrNull()
                main.post {
                    if (gen != generation) return@post
                    if (err == null) {
                        App.instance.phoneWide.onServiceState(active = true, note = PhoneWideNote.None)
                    } else {
                        Log.w(TAG, "startVPN", err)
                        App.instance.phoneWide.onServiceState(active = false, note = PhoneWideNote.Failed(err.message ?: err.toString()))
                        shutdown()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "establish", e)
            App.instance.phoneWide.onServiceState(active = false, note = PhoneWideNote.Failed(e.message ?: e.toString()))
            shutdown()
        }
    }

    /** Takes the VPN down and ends the service. Safe to call more than once. */
    fun shutdown() {
        generation++
        worker.execute { tsbridge.Tsbridge.stopVPN() }
        current = null
        if (running === this) running = null
        App.instance.phoneWide.onServiceState(active = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Another VPN app was turned on: Android ends ours. */
    override fun onRevoke() {
        App.instance.phoneWide.onRevoked()
        shutdown()
    }

    override fun onDestroy() {
        generation++
        worker.execute { tsbridge.Tsbridge.stopVPN() }
        if (running === this) running = null
        App.instance.phoneWide.onServiceState(active = false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vpn_notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(
            this, 1, Intent(this, PhoneWideVpnService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.vpn_notif_title))
            .setContentText(getString(R.string.vpn_notif_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.turn_off), off).build())
            .setOngoing(true)
            .build()
    }

    private val main = Handler(Looper.getMainLooper())

    companion object {
        /** Starts and stops of the Go tunnel, one at a time and off the main thread. */
        private val worker = Executors.newSingleThreadExecutor()
        private const val TAG = "phonewide"
        private const val CHANNEL = "phonewide"
        private const val NOTIF_ID = 2
        const val ACTION_TURN_OFF = "dev.asenascale.PHONE_WIDE_OFF"

        /** The live instance (main thread), if the VPN service is running. */
        @Volatile var running: PhoneWideVpnService? = null
            private set
    }
}
