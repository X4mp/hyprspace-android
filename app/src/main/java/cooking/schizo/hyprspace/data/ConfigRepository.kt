package cooking.schizo.hyprspace.data

import android.content.Context
import cooking.schizo.hyprspace.model.HyprspaceConfig
import cooking.schizo.hyprspace.model.PeerConfig
import hyprspace.mobile.Mobile
import java.io.File

/**
 * Reads and writes the single Hyprspace config file located at
 * [Context.getFilesDir]/hyprspace.json.
 *
 * All I/O is blocking; callers are expected to dispatch to a background thread
 * (e.g. via [kotlinx.coroutines.Dispatchers.IO]).
 */
class ConfigRepository(context: Context) {

    private val configFile = File(context.filesDir, "hyprspace.json")

    /**
     * Returns the persisted config, or creates a fresh one on first launch.
     *
     * A corrupted file — or one written by a pre-libp2p build whose private key
     * isn't a real libp2p key — is regenerated. Configured peers are preserved
     * across regeneration.
     */
    fun loadOrCreate(): HyprspaceConfig {
        val existing = if (configFile.exists()) {
            runCatching { HyprspaceConfig.fromJson(configFile.readText()) }.getOrNull()
        } else {
            null
        }

        return when {
            existing == null -> createConfig(peers = emptyList())
            // Real libp2p keys are multibase Base58BTC ("z" prefix). Older builds
            // stored a fake base64url key ("u"); regenerate but keep the peers.
            !existing.privateKey.startsWith("z") -> createConfig(peers = existing.peers)
            else -> existing
        }
    }

    fun save(config: HyprspaceConfig) {
        configFile.writeText(config.toJson())
    }

    // -------------------------------------------------------------------------
    // Identity generation (real libp2p crypto via the gomobile binding)
    // -------------------------------------------------------------------------

    /**
     * Generates a fresh libp2p identity and writes a config carrying [peers].
     *
     * The display addresses are derived by the Go layer ([Mobile.getVPNConfig]),
     * which needs the file on disk first — hence the write/read/write sequence.
     * This only runs on first launch (or migration), so the double write is fine.
     */
    private fun createConfig(peers: List<PeerConfig>): HyprspaceConfig {
        val identity = Mobile.generateIdentity()

        var config = HyprspaceConfig(
            privateKey = identity.privateKey,
            peerId = identity.peerID,
            ipv4 = "",
            ipv6 = "",
            peers = peers,
        )
        save(config)

        config = runCatching {
            val vpn = Mobile.getVPNConfig(configFile.absolutePath)
            config.copy(
                ipv4 = vpn.address4.substringBefore('/'),
                ipv6 = vpn.address6.substringBefore('/'),
            )
        }.getOrDefault(config)
        save(config)

        return config
    }
}
