package cooking.schizo.hyprspace.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.schizo.hyprspace.data.ConfigRepository
import cooking.schizo.hyprspace.model.HyprspaceConfig
import cooking.schizo.hyprspace.model.PeerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for the Hyprspace node configuration.
 *
 * Exposes the current [HyprspaceConfig] as a [StateFlow]; screens observe it
 * and re-compose when it changes. Mutations are applied optimistically to the
 * in-memory state and then persisted to disk on a background thread.
 *
 * The future Connection/VPN screen should receive this ViewModel (or its
 * StateFlow) so it can read the peer list and private key without a second
 * read of the config file.
 */
class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ConfigRepository(application)

    private val _config = MutableStateFlow<HyprspaceConfig?>(null)
    val config: StateFlow<HyprspaceConfig?> = _config.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _config.value = repository.loadOrCreate()
        }
    }

    fun addPeer(peer: PeerConfig) {
        val current = _config.value ?: return
        val updated = current.copy(peers = current.peers + peer)
        _config.value = updated
        persist(updated)
    }

    fun removePeer(peerId: String) {
        val current = _config.value ?: return
        val updated = current.copy(peers = current.peers.filter { it.id != peerId })
        _config.value = updated
        persist(updated)
    }

    private fun persist(config: HyprspaceConfig) {
        viewModelScope.launch(Dispatchers.IO) { repository.save(config) }
    }
}
