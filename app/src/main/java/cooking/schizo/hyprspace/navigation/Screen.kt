package cooking.schizo.hyprspace.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.People
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation destinations for the bottom nav bar.
 *
 * Add new screens here as sealed class objects. The future VPN/Connection screen
 * will share the same [cooking.schizo.hyprspace.viewmodel.ConfigViewModel] since it
 * needs access to peer and key data for the VpnService.
 */
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    data object Identity : Screen(
        route = "identity",
        title = "Hyprspace",
        icon = Icons.Outlined.Fingerprint,
    )

    data object Peers : Screen(
        route = "peers",
        title = "Peers",
        icon = Icons.Outlined.People,
    )

    // TODO: Uncomment and implement when VpnService integration is ready.
    // data object Connection : Screen(
    //     route = "connection",
    //     title = "Connection",
    //     icon = Icons.Outlined.VpnKey,
    // )

    companion object {
        /** Ordered list used to build the NavigationBar items. */
        val all: List<Screen> get() = listOf(Identity, Peers)
    }
}
