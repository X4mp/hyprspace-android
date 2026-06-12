package cooking.schizo.hyprspace.ui.peers

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cooking.schizo.hyprspace.model.HyprspaceConfig
import cooking.schizo.hyprspace.model.PeerConfig
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    config: HyprspaceConfig?,
    onAddPeer: (PeerConfig) -> Unit,
    onRemovePeer: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var peerToDelete by remember { mutableStateOf<PeerConfig?>(null) }
    val peers = config?.peers ?: emptyList()

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
                        onDeleteRequest = { peerToDelete = peer },
                    )
                }
                // Extra space so the last card isn't hidden behind the FAB
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }

        FloatingActionButton(
            onClick = { showAddSheet = true },
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
                        onRemovePeer(peer.id)
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

    // ── Add peer sheet ────────────────────────────────────────────────────────
    if (showAddSheet) {
        AddPeerSheet(
            onAdd = { peer ->
                onAddPeer(peer)
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
    onDeleteRequest: () -> Unit,
) {
    // confirmValueChange returns false so the item never auto-dismisses;
    // instead we show a confirmation dialog and let the ViewModel mutate the list.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDeleteRequest()
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
            modifier = Modifier.fillMaxWidth(),
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

/** Shows the last 8 chars of a peer ID with an ellipsis prefix. */
private fun truncatePeerId(id: String): String =
    if (id.length > 12) "…${id.takeLast(8)}" else id
