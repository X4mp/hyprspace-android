package cooking.schizo.hyprspace.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cooking.schizo.hyprspace.MainActivity
import cooking.schizo.hyprspace.R
import hyprspace.mobile.Events
import hyprspace.mobile.Mobile
import hyprspace.mobile.Node
import hyprspace.mobile.VPNConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground [VpnService] that hosts the Hyprspace node.
 *
 * On start it asks the gomobile binding for the tunnel parameters
 * ([Mobile.getVPNConfig]), builds the TUN interface, hands the detached fd to
 * [Mobile.startNode], and keeps a [Node] handle for shutdown.
 *
 * All blocking/Go work runs on a single worker thread; `onStartCommand` only
 * enters the foreground synchronously to satisfy the OS time window.
 */
class HyprspaceVpnService : VpnService() {

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var node: Node? = null

    /**
     * Reverse-binding from Go. Called from arbitrary libp2p goroutines, so it
     * only touches the thread-safe [VpnStateHolder] and the NotificationManager.
     */
    private val events = object : Events {
        override fun onStateChange(state: String, detail: String) {
            VpnStateHolder.onGoState(state, detail)
            when (state) {
                "running" -> updateNotification("Online — waiting for peers")
                "connected" -> updateNotification("Connected")
                "error" -> updateNotification("Error: $detail")
            }
        }

        override fun onPeerCountChange(connected: Long, total: Long) {
            VpnStateHolder.onPeerCount(connected.toInt(), total.toInt())
            if (connected > 0) {
                updateNotification("Connected — $connected/$total peers")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // ACTION_START, or a null intent redelivered by the OS after the process
        // was killed while the tunnel was up: (re)establish it. startNode() first
        // stops any lingering node, so a redelivery can't double-start.
        VpnStateHolder.setConnecting()
        startForegroundCompat(buildNotification("Starting…"))
        worker.execute { startNode() }
        // REDELIVER (not STICKY) so the OS hands us the original ACTION_START
        // intent on restart and we never relaunch after a clean, user stop.
        return START_REDELIVER_INTENT
    }

    override fun onRevoke() {
        // User revoked the VPN from system settings.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        // Allows an in-flight node.stop() task to finish.
        worker.shutdown()
        super.onDestroy()
    }

    // ── Node lifecycle ──────────────────────────────────────────────────────

    private fun startNode() {
        val configPath = File(filesDir, CONFIG_FILE).absolutePath
        try {
            // Defensive: stop any node lingering from a prior soft error so a
            // restart never leaks a running host.
            node?.let {
                runCatching { it.stop() }
                node = null
            }

            val vpnConfig = Mobile.getVPNConfig(configPath)

            val pfd = buildTunnel(vpnConfig)
            if (pfd == null) {
                Log.e(TAG, "establish() returned null (VPN not prepared?)")
                failAndStop("VPN permission was revoked")
                return
            }

            // Ownership of the fd transfers to Go, which closes it on stop().
            val fd = pfd.detachFd()
            // Go emits "running" via [events] once the node is up, which drives
            // the UI to Connected — we don't set success state here.
            node = Mobile.startNode(fd.toLong(), configPath, events)
            Log.i(TAG, "Hyprspace node started")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Hyprspace node", t)
            failAndStop(t.message ?: "Failed to start the tunnel")
        }
    }

    private fun buildTunnel(cfg: VPNConfig): ParcelFileDescriptor? {
        val builder = Builder().setSession("Hyprspace")

        addAddress(builder, cfg.address4)
        addAddress(builder, cfg.address6)

        cfg.routes.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { addRoute(builder, it) }

        builder.setMtu(cfg.getMTU().toInt())

        // Exclude our own traffic so the in-process libp2p/QUIC sockets egress on
        // the real network instead of looping back into the tunnel.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not exclude own package from VPN", e)
        }

        return builder.establish()
    }

    /** Graceful, user-initiated stop. */
    private fun stopVpn() {
        VpnStateHolder.setStopped()
        teardown()
    }

    /** Stop triggered by a failure; keeps the error visible in the UI. */
    private fun failAndStop(message: String) {
        VpnStateHolder.setError(message)
        teardown()
    }

    private fun teardown() {
        worker.execute {
            try {
                node?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping node", t)
            }
            node = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── TUN builder helpers ─────────────────────────────────────────────────

    private fun addAddress(builder: Builder, cidr: String) {
        val (addr, prefix) = parseCidr(cidr) ?: return
        builder.addAddress(addr, prefix)
    }

    private fun addRoute(builder: Builder, cidr: String) {
        val (addr, prefix) = parseCidr(cidr) ?: return
        builder.addRoute(addr, prefix)
    }

    /** Splits "100.64.1.2/32" into ("100.64.1.2", 32); null if malformed. */
    private fun parseCidr(cidr: String): Pair<String, Int>? {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return null
        val addr = cidr.substring(0, slash)
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return null
        return addr to prefix
    }

    // ── Notification ────────────────────────────────────────────────────────

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HyprspaceVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hyprspace")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_satellite)
            .setColor(0xFF1A73E8.toInt())
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Hyprspace VPN",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Ongoing Hyprspace tunnel status" },
            )
        }
    }

    companion object {
        const val ACTION_START = "cooking.schizo.hyprspace.vpn.START"
        const val ACTION_STOP = "cooking.schizo.hyprspace.vpn.STOP"

        private const val TAG = "HyprspaceVpn"
        private const val CONFIG_FILE = "hyprspace.json"
        private const val CHANNEL_ID = "hyprspace_vpn"
        private const val NOTIFICATION_ID = 1

        /** Starts the service in the foreground. Caller must have completed VPN consent. */
        fun start(context: Context) {
            val intent = Intent(context, HyprspaceVpnService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Requests a graceful stop. */
        fun stop(context: Context) {
            val intent = Intent(context, HyprspaceVpnService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
