package cooking.schizo.hyprspace.data

import android.content.Context
import android.util.Base64
import cooking.schizo.hyprspace.model.HyprspaceConfig
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Reads and writes the single Hyprspace config file located at
 * [Context.getFilesDir]/hyprspace.json.
 *
 * All I/O is blocking; callers are expected to dispatch to a background thread
 * (e.g. via [kotlinx.coroutines.Dispatchers.IO]).
 */
class ConfigRepository(context: Context) {

    private val configFile = File(context.filesDir, "hyprspace.json")
    private val random = SecureRandom()

    /**
     * Returns the persisted config, or creates a fresh one on first launch.
     * A corrupted file is silently replaced.
     */
    fun loadOrCreate(): HyprspaceConfig =
        if (configFile.exists()) {
            runCatching { HyprspaceConfig.fromJson(configFile.readText()) }
                .getOrElse { generateNewConfig().also { save(it) } }
        } else {
            generateNewConfig().also { save(it) }
        }

    fun save(config: HyprspaceConfig) {
        configFile.writeText(config.toJson())
    }

    // -------------------------------------------------------------------------
    // Key / address generation (placeholder — replace with real libp2p crypto)
    // -------------------------------------------------------------------------

    private fun generateNewConfig(): HyprspaceConfig {
        // 256-bit private key encoded as multibase base64url ("u" prefix)
        val keyBytes = ByteArray(32).also { random.nextBytes(it) }
        val privateKey =
            "u" + Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        // Derive a deterministic peer ID from SHA-256(privateKey).
        // Real libp2p peer IDs are multihash(SHA-256(publicKey)) encoded base58btc.
        val hash = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val peerId = "12D3KooW" + encodeBase58(hash).take(38)

        // Hyprspace VPN address space: 100.64.0.0/10 (IPv4), fd00::/8 (IPv6)
        val addrBytes = ByteArray(2).also { random.nextBytes(it) }
        val b1 = addrBytes[0].toInt() and 0x3F  // 0–63 → keeps us in 100.64–100.127
        val b2 = addrBytes[1].toInt() and 0xFF
        val ipv4 = "100.64.$b1.$b2"
        val ipv6 = "fd00::${b1.toString(16)}:${b2.toString(16)}"

        return HyprspaceConfig(
            privateKey = privateKey,
            peerId = peerId,
            ipv4 = ipv4,
            ipv6 = ipv6,
            peers = emptyList(),
        )
    }

    /** Bitcoin base58 encoding (base58btc alphabet, no check). */
    private fun encodeBase58(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger(1, input)
        val base = BigInteger.valueOf(58L)
        val sb = StringBuilder()
        while (value > BigInteger.ZERO) {
            val (q, r) = value.divideAndRemainder(base)
            sb.append(alphabet[r.toInt()])
            value = q
        }
        for (byte in input) {
            if (byte == 0.toByte()) sb.append('1') else break
        }
        return sb.reverse().toString()
    }
}
