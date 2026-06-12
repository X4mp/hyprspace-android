package cooking.schizo.hyprspace.ui.identity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cooking.schizo.hyprspace.model.HyprspaceConfig
import cooking.schizo.hyprspace.ui.components.SectionCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdentityScreen(
    config: HyprspaceConfig?,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showPeerIdDialog by remember { mutableStateOf(false) }
    var isKeyVisible by remember { mutableStateOf(false) }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        coroutineScope.launch { snackbarHostState.showSnackbar("Copied") }
    }

    fun revealKeyWithBiometric() {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isKeyVisible = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // USER_CANCELED / NEGATIVE_BUTTON are intentional — don't show a snackbar.
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Auth error: $errString")
                    }
                }
            }
        }

        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Reveal Private Key")
            .setSubtitle("Confirm your identity to view the private key")
            .setAllowedAuthenticators(authenticators)
            .apply {
                // DEVICE_CREDENTIAL handles its own cancellation on API 30+;
                // on older APIs we need an explicit negative button.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    setNegativeButtonText("Cancel")
                }
            }
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    // Full peer ID detail dialog (shown on long-press of the truncated ID)
    if (showPeerIdDialog && config != null) {
        AlertDialog(
            onDismissRequest = { showPeerIdDialog = false },
            title = { Text("Peer ID") },
            text = {
                Text(
                    text = config.peerId,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard("Peer ID", config.peerId)
                    showPeerIdDialog = false
                }) { Text("Copy & Close") }
            },
            dismissButton = {
                TextButton(onClick = { showPeerIdDialog = false }) { Text("Close") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── Peer ID ──────────────────────────────────────────────────────────
        SectionCard(label = "Peer ID") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = config?.peerId?.let { truncateCenter(it) } ?: "Loading…",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showPeerIdDialog = true },
                        ),
                )
                IconButton(
                    onClick = { config?.let { copyToClipboard("Peer ID", it.peerId) } },
                    enabled = config != null,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Peer ID",
                    )
                }
            }
        }

        // ── Private Key ───────────────────────────────────────────────────────
        SectionCard(label = "Private Key") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isKeyVisible) config?.privateKey ?: "Loading…" else "•".repeat(24),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // Toggle visibility (requires biometric to reveal)
                IconButton(
                    onClick = {
                        if (isKeyVisible) isKeyVisible = false else revealKeyWithBiometric()
                    },
                ) {
                    Icon(
                        imageVector = if (isKeyVisible) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                        contentDescription = if (isKeyVisible) "Hide private key"
                        else "Reveal private key",
                    )
                }
                IconButton(
                    onClick = { config?.let { copyToClipboard("Private Key", it.privateKey) } },
                    enabled = config != null,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Private Key",
                    )
                }
            }
        }

        // ── Addresses ─────────────────────────────────────────────────────────
        SectionCard(label = "Addresses") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AddressRow(label = "IPv4", value = config?.ipv4 ?: "—")
                AddressRow(label = "IPv6", value = config?.ipv6 ?: "—")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun AddressRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Truncates [text] to show the first N/2 and last N/2 chars with a center ellipsis. */
private fun truncateCenter(text: String, maxLen: Int = 22): String {
    if (text.length <= maxLen) return text
    val half = (maxLen - 1) / 2
    return "${text.take(half)}…${text.takeLast(half)}"
}
