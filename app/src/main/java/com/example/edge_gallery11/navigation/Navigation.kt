package com.example.edge_gallery11.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.edge_gallery11.ui.screens.AIChatScreen
import com.example.edge_gallery11.ui.screens.SettingsScreen
import com.example.edge_gallery11.viewmodel.EdgeGalleryViewModel

sealed class Screen(val route: String, val icon: ImageVector, val title: String) {
    object AIChat : Screen("ai_chat", Icons.Filled.Chat, "AI Chat")
    object Settings : Screen("settings", Icons.Filled.Settings, "Settings")
}

val items = listOf(
    Screen.AIChat,
    Screen.Settings
)

@Composable
fun BottomNavigationBar(navController: NavController) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        items.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = null) },
                label = { Text(screen.title) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    viewModel: EdgeGalleryViewModel,
) {
    NavHost(navController = navController, startDestination = Screen.AIChat.route) {
        composable(Screen.AIChat.route) {
            AIChatScreen(viewModel = viewModel)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(viewModel = viewModel)
        }
    }
}