package net.hexnet.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.hexnet.app.core.designsystem.theme.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    object Chats : Screen("chats", "Chats", Icons.Default.ChatBubble)
    object Peers : Screen("peers", "Peers", Icons.Default.People)
    object Mesh : Screen("mesh", "Mesh", Icons.Default.Hub)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val BottomNavScreens = listOf(
    Screen.Dashboard,
    Screen.Chats,
    Screen.Peers,
    Screen.Mesh,
    Screen.Settings
)

@Composable
fun HexnetBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = HexSurfaceDark,
        contentColor = HexCyanPrimary,
        tonalElevation = 8.dp
    ) {
        BottomNavScreens.forEach { screen ->
            val isSelected = currentRoute == screen.route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(screen.route) },
                icon = {
                    Icon(
                        imageVector = screen.icon,
                        contentDescription = screen.title,
                        tint = if (isSelected) HexCyanPrimary else HexTextMutedDark
                    )
                },
                label = {
                    Text(
                        text = screen.title,
                        fontSize = 11.sp,
                        color = if (isSelected) HexCyanPrimary else HexTextMutedDark
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = HexCyanContainer
                )
            )
        }
    }
}
