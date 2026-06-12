package cooking.schizo.hyprspace

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cooking.schizo.hyprspace.navigation.Screen
import cooking.schizo.hyprspace.ui.identity.IdentityScreen
import cooking.schizo.hyprspace.ui.peers.PeersScreen
import cooking.schizo.hyprspace.ui.theme.HyprspaceTheme
import cooking.schizo.hyprspace.viewmodel.ConfigViewModel

/**
 * Single activity for the entire app.
 *
 * Extends [AppCompatActivity] (rather than [androidx.activity.ComponentActivity]) so
 * that [androidx.biometric.BiometricPrompt] — which requires a [androidx.fragment.app.FragmentActivity]
 * — can be launched from composables via [androidx.compose.ui.platform.LocalContext].
 *
 * The [ConfigViewModel] lives here and is passed to each screen so that the future
 * Connection/VPN screen can share the same instance without a NavHost.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyprspaceTheme {
                HyprspaceApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HyprspaceApp(viewModel: ConfigViewModel) {
    val config by viewModel.config.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Identity) }
    val isImeVisible = WindowInsets.isImeVisible

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(currentScreen.title) })
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !isImeVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                NavigationBar {
                    Screen.all.forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                )
                            },
                            label = { Text(screen.title) },
                        )
                    }

                    // TODO: Uncomment when VpnService integration is ready.
                    // NavigationBarItem(
                    //     selected = currentScreen == Screen.Connection,
                    //     onClick = { currentScreen = Screen.Connection },
                    //     icon = { Icon(Icons.Outlined.VpnKey, contentDescription = "Connection") },
                    //     label = { Text("Connection") },
                    // )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentScreen) {
                Screen.Identity -> IdentityScreen(
                    config = config,
                    snackbarHostState = snackbarHostState,
                    coroutineScope = coroutineScope,
                    modifier = Modifier.fillMaxSize(),
                )

                Screen.Peers -> PeersScreen(
                    config = config,
                    onAddPeer = viewModel::addPeer,
                    onRemovePeer = viewModel::removePeer,
                    snackbarHostState = snackbarHostState,
                    coroutineScope = coroutineScope,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
