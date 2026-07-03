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
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground [VpnService] that hosts the Hyprspace node.
 *
 * On start it asks the gomobile binding for the tunnel parameters
 * ([Mobile.getVPNConfig]), builds the TUN interface, hands the detached fd to
 * [Mobile.startNode], and keeps a [Node] handle for shutdown.
 *
 * Blocking/Go work runs off the main thread; `onStartCommand` only enters the
 * foreground synchronously to satisfy the OS time window.
 */
class HyprspaceVpnService : VpnService() {

    private val worker = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startGeneration = AtomicInteger(0)
    private val lifecycleLock = Any()

    @Volatile
    private var node: Node? = null

    /**
     * Reverse-binding from Go. Called from arbitrary libp2p goroutines, so it
     * only touches the thread-safe [VpnStateHolder] and the NotificationManager.
     */
    private val events = object : Events {
        override fun onStateChange(state: String, detail: String) {
            VpnStateHolder.onGoState(state, detail)
            if (!isVpnWanted()) return

            when (state) {
                "running" -> updateNotification("Online — waiting for peers")
                "connected" -> updateNotification("Connected")
                "error" -> {
                    val message = detail.ifBlank { "Hyprspace node stopped unexpectedly" }
                    updateNotification("Error: $message")
                    failAndStop(message)
                }
            }
        }

        override fun onPeerCountChange(connected: Long, total: Long) {
            VpnStateHolder.onPeerCount(connected.toInt(), total.toInt())
            if (!isVpnWanted()) return

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

        // ACTION_START, or a redelivered intent after the process was killed
        // while the tunnel was up. Only honor it while the user's desired state
        // is still running; notification/app Stop clears that flag so a stale
        // redelivery cannot bring the UI back to "Online" after an explicit stop.
        if (!isVpnWanted()) {
            VpnStateHolder.setStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        if (VpnStateHolder.status.value.state == VpnState.Connecting) {
            // Ignore duplicate/redelivered starts while a start attempt is already in flight.
            return START_REDELIVER_INTENT
        }
        synchronized(lifecycleLock) {
            if (node != null) {
                // Ignore duplicate/redelivered starts while the current node is alive.
                return START_REDELIVER_INTENT
            }
        }

        val generation = startGeneration.incrementAndGet()
        VpnStateHolder.setConnecting()
        startForegroundCompat(buildNotification("Starting…"))
        worker.execute { startNode(generation) }
        scheduleStartTimeout(generation)
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
        // Interrupt queued work; Go calls may ignore interruption, so stale
        // completions are guarded by [startGeneration].
        worker.shutdownNow()
        super.onDestroy()
    }

    // ── Node lifecycle ──────────────────────────────────────────────────────

    private fun startNode(generation: Int) {
        val configPath = File(filesDir, CONFIG_FILE).absolutePath
        try {
            // Defensive: stop any node lingering from a prior soft error so a
            // restart never leaks a running host.
            synchronized(lifecycleLock) {
                node.also { node = null }
            }?.let { runCatching { it.stop() } }

            val vpnConfig = Mobile.getVPNConfig(configPath)

            val pfd = buildTunnel(vpnConfig)
            if (pfd == null) {
                Log.e(TAG, "establish() returned null (VPN not prepared?)")
                failAndStop(generation, "VPN permission was revoked")
                return
            }
            if (!isCurrentStart(generation)) {
                runCatching { pfd.close() }
                return
            }

            // Ownership of the detached fd transfers to Go when startNode is
            // called; the binding closes it on stop and on its own error paths.
            val fd = pfd.detachFd()
            // Go emits "running" via [events] once the node is up, which drives
            // the UI to Connected — we don't set success state here.
            val startedNode = Mobile.startNode(fd.toLong(), configPath, events)
            if (!isCurrentStart(generation)) {
                runCatching { startedNode.stop() }
                return
            }
            synchronized(lifecycleLock) { node = startedNode }
            Log.i(TAG, "Hyprspace node started")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Hyprspace node", t)
            failAndStop(generation, t.message ?: "Failed to start the tunnel")
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
        startGeneration.incrementAndGet()
        setVpnWanted(false)
        VpnStateHolder.setStopped()
        teardown()
    }

    /** Stop triggered by a failure; keeps the error visible in the UI. */
    private fun failAndStop(generation: Int, message: String) {
        if (!isCurrentStart(generation)) return
        setVpnWanted(false)
        VpnStateHolder.setError(message)
        teardown()
    }

    private fun failAndStop(message: String) {
        startGeneration.incrementAndGet()
        setVpnWanted(false)
        VpnStateHolder.setError(message)
        teardown()
    }

    private fun teardown() {
        val nodeToStop = synchronized(lifecycleLock) {
            node.also { node = null }
        }
        worker.execute {
            try {
                nodeToStop?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping node", t)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isCurrentStart(generation: Int): Boolean =
        isVpnWanted() && startGeneration.get() == generation

    private fun scheduleStartTimeout(generation: Int) {
        mainHandler.postDelayed({
            if (isCurrentStart(generation) && VpnStateHolder.status.value.state == VpnState.Connecting) {
                Log.e(TAG, "Timed out while starting Hyprspace node")
                failAndStop(generation, "Timed out while starting the tunnel")
            }
        }, START_TIMEOUT_MS)
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

    private fun isVpnWanted(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_WANTED, false)

    private fun setVpnWanted(wanted: Boolean) {
        setVpnWanted(this, wanted)
    }

    companion object {
        const val ACTION_START = "cooking.schizo.hyprspace.vpn.START"
        const val ACTION_STOP = "cooking.schizo.hyprspace.vpn.STOP"

        private const val TAG = "HyprspaceVpn"
        private const val CONFIG_FILE = "hyprspace.json"
        private const val CHANNEL_ID = "hyprspace_vpn"
        private const val NOTIFICATION_ID = 1
        private const val START_TIMEOUT_MS = 45_000L
        private const val PREFS_NAME = "hyprspace_vpn"
        private const val PREF_WANTED = "wanted"

        private fun setVpnWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_WANTED, wanted)
                .apply()
        }

        /** Starts the service in the foreground. Caller must have completed VPN consent. */
        fun start(context: Context) {
            setVpnWanted(context, true)
            val intent = Intent(context, HyprspaceVpnService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Requests a graceful stop. */
        fun stop(context: Context) {
            setVpnWanted(context, false)
            val intent = Intent(context, HyprspaceVpnService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
