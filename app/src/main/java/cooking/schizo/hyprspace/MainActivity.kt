package cooking.schizo.hyprspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cooking.schizo.hyprspace.ui.identity.IdentityScreen
import cooking.schizo.hyprspace.ui.peers.PeersScreen
import cooking.schizo.hyprspace.ui.theme.HyprspaceTheme
import cooking.schizo.hyprspace.viewmodel.ConfigViewModel
import cooking.schizo.hyprspace.vpn.HyprspaceVpnService
import cooking.schizo.hyprspace.vpn.VpnState

class MainActivity : AppCompatActivity() {

    private val viewModel: ConfigViewModel by viewModels()

    /** Launches the system VPN consent dialog; starts the service on approval. */
    private lateinit var vpnConsentLauncher: ActivityResultLauncher<Intent>

    /** Requests POST_NOTIFICATIONS (API 33+) so the foreground notification shows. */
    private lateinit var notificationPermLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService()
            }
            // Declined consent → remain stopped; nothing to do.
        }

        notificationPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* FGS runs regardless; the notification is just hidden if denied. */ }

        enableEdgeToEdge()
        setContent {
            HyprspaceTheme {
                HyprspaceApp(
                    viewModel = viewModel,
                    onToggleVpn = ::toggleVpn,
                )
            }
        }
    }

    /**
     * Entry point for the Start/Stop button. Stops immediately when running;
     * otherwise runs the VPN consent flow before starting the service.
     */
    private fun toggleVpn() {
        val state = viewModel.vpnStatus.value.state
        if (state == VpnState.Connecting || state == VpnState.Connected) {
            HyprspaceVpnService.stop(this)
            return
        }

        // The service expects filesDir/hyprspace.json to exist; first-launch
        // identity creation happens asynchronously in ConfigViewModel.
        if (viewModel.config.value == null) return

        maybeRequestNotificationPermission()

        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsentLauncher.launch(consent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        if (viewModel.config.value == null) return
        HyprspaceVpnService.start(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun HyprspaceApp(
    viewModel: ConfigViewModel,
    onToggleVpn: () -> Unit,
) {
    val config by viewModel.config.collectAsState()
    val vpnStatus by viewModel.vpnStatus.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })

    // Surface fatal VPN errors once, as they arrive.
    LaunchedEffect(vpnStatus.state, vpnStatus.detail) {
        if (vpnStatus.state == VpnState.Error && vpnStatus.detail.isNotEmpty()) {
            snackbarHostState.showSnackbar("VPN error: ${vpnStatus.detail}")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> IdentityScreen(
                        config = config,
                        vpnStatus = vpnStatus,
                        onToggleVpn = onToggleVpn,
                        snackbarHostState = snackbarHostState,
                        coroutineScope = coroutineScope,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 40.dp),
                    )

                    1 -> PeersScreen(
                        config = config,
                        vpnStatus = vpnStatus,
                        onAddPeer = viewModel::addPeer,
                        onRemovePeer = viewModel::removePeer,
                        snackbarHostState = snackbarHostState,
                        coroutineScope = coroutineScope,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 40.dp),
                    )
                }
            }

            // Minimal page indicator — two dots, centered at the bottom.
            // Not a navigation bar: no labels, no icons, no tappable items.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(2) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (selected) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
        }
    }
}
