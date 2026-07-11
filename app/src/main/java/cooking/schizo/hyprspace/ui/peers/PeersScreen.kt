package cooking.schizo.hyprspace.ui.peers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.schizo.hyprspace.model.HyprspaceConfig
import cooking.schizo.hyprspace.model.PeerConfig
import cooking.schizo.hyprspace.vpn.VpnState
import cooking.schizo.hyprspace.vpn.VpnStatus
import java.math.BigInteger
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    config: HyprspaceConfig?,
    vpnStatus: VpnStatus,
    onAddPeer: (PeerConfig) -> Unit,
    onRemovePeer: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var peerToDelete by remember { mutableStateOf<PeerConfig?>(null) }
    var peerToShow by remember { mutableStateOf<PeerConfig?>(null) }
    val peers = config?.peers ?: emptyList()
    val editsLocked = vpnStatus.state == VpnState.Connecting ||
            vpnStatus.state == VpnState.Connected ||
            vpnStatus.state == VpnState.Stopping

    fun showEditLockedMessage() {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Stop the VPN before editing peers")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (peers.isEmpty()) {
            EmptyPeersState(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers, key = { it.id }) { peer ->
                    PeerListItem(
                        peer = peer,
                        editsLocked = editsLocked,
                        onEditBlocked = { showEditLockedMessage() },
                        onClick = { peerToShow = peer },
                        onDeleteRequest = { peerToDelete = peer },
                    )
                }
                // Extra space so the last card isn't hidden behind the FAB
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }

        FloatingActionButton(
            onClick = {
                if (editsLocked) showEditLockedMessage() else showAddSheet = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(imageVector = Icons.Outlined.Add, contentDescription = "Add peer")
        }
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    peerToDelete?.let { peer ->
        AlertDialog(
            onDismissRequest = { peerToDelete = null },
            title = { Text("Remove Peer") },
            text = {
                val displayName = peer.name.ifBlank { "Unnamed peer" }
                Text("Remove \"$displayName\" from your peer list?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editsLocked) {
                            showEditLockedMessage()
                        } else {
                            onRemovePeer(peer.id)
                        }
                        peerToDelete = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { peerToDelete = null }) { Text("Cancel") }
            },
        )
    }

    // ── Peer address detail ───────────────────────────────────────────────────
    peerToShow?.let { peer ->
        PeerAddressDialog(
            peer = peer,
            snackbarHostState = snackbarHostState,
            coroutineScope = coroutineScope,
            onDismiss = { peerToShow = null },
        )
    }

    // ── Add peer sheet ────────────────────────────────────────────────────────
    if (showAddSheet) {
        AddPeerSheet(
            onAdd = { peer ->
                if (editsLocked) {
                    showEditLockedMessage()
                } else {
                    onAddPeer(peer)
                }
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }
}

// ─── Peer list item with swipe-to-delete ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerListItem(
    peer: PeerConfig,
    editsLocked: Boolean,
    onEditBlocked: () -> Unit,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // confirmValueChange returns false so the item never auto-dismisses;
    // instead we show a confirmation dialog and let the ViewModel mutate the list.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                if (editsLocked) onEditBlocked() else onDeleteRequest()
            }
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bgColor by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                label = "swipe bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor, shape = CardDefaults.shape),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete peer",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            ListItem(
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = {
                    Text(
                        text = peer.name.ifBlank { "Unnamed peer" },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                supportingContent = {
                    Text(
                        text = truncatePeerId(peer.id),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

// ─── Peer address dialog ─────────────────────────────────────────────────────

@Composable
private fun PeerAddressDialog(
    peer: PeerConfig,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val addresses = remember(peer.id) { derivePeerAddresses(peer.id) }
    val displayName = peer.name.ifBlank { "Unnamed peer" }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        coroutineScope.launch { snackbarHostState.showSnackbar("Copied") }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Peer ID: ${truncatePeerId(peer.id)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (addresses == null) {
                    Text("Unable to derive peer IP addresses from this peer ID.")
                } else {
                    AddressLine(
                        label = "IPv4",
                        value = addresses.ipv4,
                        onCopy = { copyToClipboard("Peer IPv4", addresses.ipv4) },
                    )
                    AddressLine(
                        label = "IPv6",
                        value = addresses.ipv6,
                        onCopy = { copyToClipboard("Peer IPv6", addresses.ipv6) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun AddressLine(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy $label",
            )
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyPeersState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PersonOff,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No peers yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap + to add one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private data class PeerAddresses(
    val ipv4: String,
    val ipv6: String,
)

private fun derivePeerAddresses(peerId: String): PeerAddresses? {
    val peerBytes = decodeBase58(peerId) ?: return null

    val ipv4 = intArrayOf(100, 64, 1, 2)
    peerBytes.forEachIndexed { index, byte ->
        val octet = (index % 2) + 2
        ipv4[octet] = ipv4[octet] xor (byte.toInt() and 0xff)
    }

    val netId = intArrayOf(0xde, 0xad, 0xbe, 0xef)
    peerBytes.forEachIndexed { index, byte ->
        val octet = index % 4
        netId[octet] = netId[octet] xor (byte.toInt() and 0xff)
    }

    val ipv6 = byteArrayOf(
        0xfd.toByte(), 0x00,
        'h'.code.toByte(), 'y'.code.toByte(),
        'p'.code.toByte(), 'r'.code.toByte(),
        's'.code.toByte(), 'p'.code.toByte(),
        'a'.code.toByte(), 'c'.code.toByte(),
        'e'.code.toByte(), 0x00,
        netId[0].toByte(), netId[1].toByte(), netId[2].toByte(), netId[3].toByte(),
    )

    return PeerAddresses(
        ipv4 = ipv4.joinToString("."),
        ipv6 = InetAddress.getByAddress(ipv6).hostAddress ?: return null,
    )
}

private fun decodeBase58(value: String): ByteArray? {
    var decoded = BigInteger.ZERO
    value.forEach { char ->
        val digit = BASE58_ALPHABET.indexOf(char)
        if (digit < 0) return null
        decoded = decoded.multiply(BASE58_BASE).add(BigInteger.valueOf(digit.toLong()))
    }

    val leadingZeros = value.takeWhile { it == '1' }.length
    val decodedBytes = decoded.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    return ByteArray(leadingZeros) + decodedBytes
}

/** Shows the last 8 chars of a peer ID with an ellipsis prefix. */
private fun truncatePeerId(id: String): String =
    if (id.length > 12) "…${id.takeLast(8)}" else id

private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private val BASE58_BASE = BigInteger.valueOf(58L)
