package cooking.schizo.hyprspace.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** High-level VPN lifecycle, derived from gomobile [hyprspace.mobile.Events]. */
enum class VpnState { Stopped, Connecting, Connected, Stopping, Error }

/**
 * Snapshot of the tunnel's status shown in the UI.
 *
 * [detail] carries the error message when [state] is [VpnState.Error].
 * [connectedPeers]/[totalPeers] track how many configured peers are reachable.
 */
data class VpnStatus(
    val state: VpnState = VpnState.Stopped,
    val detail: String = "",
    val connectedPeers: Int = 0,
    val totalPeers: Int = 0,
)

/**
 * Process-wide VPN status, shared between [HyprspaceVpnService] (the writer) and
 * the UI (the reader). The service runs in the same process as the Activity, so
 * a plain singleton holding a [StateFlow] is sufficient — no IPC needed.
 *
 * Writers come from several threads: the Android main thread (start/stop
 * commands) and arbitrary Go goroutines (via the Events callback).
 * [MutableStateFlow] is safe to update concurrently.
 */
object VpnStateHolder {

    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    /** Marks the service as starting; clears any stale peer counts/errors. */
    fun setConnecting() {
        _status.value = VpnStatus(state = VpnState.Connecting)
    }

    /** Marks shutdown in progress; the old node may still be releasing sockets. */
    fun setStopping() {
        _status.update { it.copy(state = VpnState.Stopping, detail = "") }
    }

    /** Marks a clean shutdown. Late Go callbacks from the stopped node are ignored. */
    fun setStopped() {
        _status.value = VpnStatus(state = VpnState.Stopped)
    }

    /** Marks a fatal failure, preserving [detail] for the UI to surface. */
    fun setError(detail: String) {
        _status.update { it.copy(state = VpnState.Error, detail = detail) }
    }

    /** Applies a gomobile `Events.onStateChange` transition. */
    fun onGoState(goState: String, detail: String) {
        _status.update {
            val mapped = when (goState) {
                // A stopped UI state is authoritative after an explicit app or
                // notification stop. Ignore late callbacks from the old Go node
                // until a new user start calls setConnecting().
                "running", "connected" -> when (it.state) {
                    VpnState.Connecting, VpnState.Connected -> VpnState.Connected
                    else -> it.state
                }
                // A fatal error path tears the service down and may be followed by
                // a Go "stopped" callback. Keep the error visible until the next
                // explicit start/stop transition instead of immediately clearing it.
                "stopped" -> if (it.state == VpnState.Error) VpnState.Error else VpnState.Stopped
                "error" -> if (it.state == VpnState.Stopped || it.state == VpnState.Stopping) {
                    it.state
                } else {
                    VpnState.Error
                }

                else -> it.state
            }
            val nextDetail = if (goState == "stopped" && it.state == VpnState.Error) {
                it.detail
            } else if (mapped == VpnState.Stopped) {
                ""
            } else {
                detail
            }
            it.copy(state = mapped, detail = nextDetail)
        }
    }

    /** Applies a gomobile `Events.onPeerCountChange` update. */
    fun onPeerCount(connected: Int, total: Int) {
        _status.update {
            when (it.state) {
                VpnState.Connecting, VpnState.Connected ->
                    it.copy(connectedPeers = connected, totalPeers = total)

                else -> it
            }
        }
    }
}
