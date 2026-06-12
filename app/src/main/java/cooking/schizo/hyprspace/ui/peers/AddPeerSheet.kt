package cooking.schizo.hyprspace.ui.peers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import cooking.schizo.hyprspace.model.PeerConfig

/**
 * Modal bottom sheet for adding a new peer.
 *
 * Validates that the Peer ID field contains a plausible libp2p peer ID before
 * enabling the "Add" button. Full cryptographic validation can be added when the
 * libp2p backend is integrated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPeerSheet(
    onAdd: (PeerConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf("") }
    var peerId by remember { mutableStateOf("") }
    var peerIdError by remember { mutableStateOf<String?>(null) }

    val peerIdValid = peerId.isNotBlank() && peerIdError == null && isValidPeerId(peerId)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Add Peer",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Name (optional)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Peer ID (required)
            OutlinedTextField(
                value = peerId,
                onValueChange = { raw ->
                    peerId = raw.trim()
                    peerIdError = when {
                        raw.isBlank() -> null
                        !isValidPeerId(raw.trim()) ->
                            "Must be a valid libp2p peer ID (e.g. starts with 12D3KooW…)"
                        else -> null
                    }
                },
                label = { Text("Peer ID *") },
                isError = peerIdError != null,
                supportingText = peerIdError?.let { err ->
                    { Text(err, color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        if (peerIdValid) {
                            onAdd(PeerConfig(id = peerId, name = name.trim()))
                        }
                    },
                    enabled = peerIdValid,
                ) {
                    Text("Add")
                }
            }
        }
    }
}

/**
 * Lightweight peer ID validator.
 *
 * Accepts strings that start with the canonical libp2p base58btc multihash prefix
 * ("12D3KooW") OR any base58btc-looking string of sufficient length. Full
 * validation (multihash decode + public key check) belongs in the crypto backend.
 */
private fun isValidPeerId(id: String): Boolean {
    if (id.length < 10) return false
    // base58btc alphabet — no 0, O, I, l
    val base58 = Regex("[1-9A-HJ-NP-Za-km-z]+")
    return base58.matches(id)
}
