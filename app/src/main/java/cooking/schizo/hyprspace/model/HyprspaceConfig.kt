package cooking.schizo.hyprspace.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A configured remote peer.
 *
 * Config JSON shape:
 * ```json
 * { "id": "12D3KooW…", "name": "hostname1" }
 * ```
 */
data class PeerConfig(
    val id: String,
    val name: String,
)

/**
 * Root configuration for a Hyprspace node.
 *
 * Persisted as a single JSON file at [android.content.Context.getFilesDir]/hyprspace.json.
 * Read at startup; written back on every mutation (add/remove peer).
 *
 * The [peerId], [ipv4], and [ipv6] fields are derived from [privateKey] by the libp2p
 * layer on first launch and stored for display only; Go ignores them when reading the
 * config back.
 *
 * Full JSON shape:
 * ```json
 * {
 *   "privateKey": "z<base58btc>",
 *   "peerId":     "12D3KooW…",
 *   "ipv4":       "100.64.x.x",
 *   "ipv6":       "fd00::x:x",
 *   "peers": [
 *     { "id": "12D3KooW…", "name": "hostname1" }
 *   ]
 * }
 * ```
 */
data class HyprspaceConfig(
    val privateKey: String,
    val peerId: String,
    val ipv4: String,
    val ipv6: String,
    val peers: List<PeerConfig>,
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("privateKey", privateKey)
            put("peerId", peerId)
            put("ipv4", ipv4)
            put("ipv6", ipv6)
            put(
                "peers",
                JSONArray().also { arr ->
                    peers.forEach { peer ->
                        arr.put(
                            JSONObject().apply {
                                put("id", peer.id)
                                put("name", peer.name)
                            },
                        )
                    }
                },
            )
        }.toString(2)
    }

    companion object {
        fun fromJson(jsonString: String): HyprspaceConfig {
            val root = JSONObject(jsonString)
            val peersArr = root.optJSONArray("peers") ?: JSONArray()
            val peers = (0 until peersArr.length()).map { i ->
                val p = peersArr.getJSONObject(i)
                PeerConfig(id = p.getString("id"), name = p.optString("name", ""))
            }
            return HyprspaceConfig(
                privateKey = root.getString("privateKey"),
                peerId = root.optString("peerId", ""),
                ipv4 = root.optString("ipv4", ""),
                ipv6 = root.optString("ipv6", ""),
                peers = peers,
            )
        }
    }
}
