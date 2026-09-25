package com.c1recorder.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private const val ROUTE_CONTROL = "control"
private const val ROUTE_RECORDINGS = "recordings"
private const val ROUTE_DEVICE = "device"

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab(ROUTE_CONTROL, "控制台", Icons.Filled.Home),
    BottomTab(ROUTE_RECORDINGS, "录音", Icons.AutoMirrored.Filled.List),
    BottomTab(ROUTE_DEVICE, "设备", Icons.Filled.Settings),
)

/**
 * Three tabs sharing one Activity-scoped MainViewModel (passed down as a
 * plain parameter, not re-fetched per screen), so the BLE connection it owns
 * survives switching tabs — navigating only replaces which Composable is on
 * screen, it never touches C1BleClient/C1BleScanner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onRequestPermissions: () -> Unit) {
    val navController = rememberNavController()

    Scaffold(
        topBar = { TopAppBar(title = { Text("C1 Recorder") }) },
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            NavigationBar {
                BOTTOM_TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_CONTROL,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_CONTROL) { ControlScreen(viewModel, onRequestPermissions) }
            composable(ROUTE_RECORDINGS) { RecordingsScreen(viewModel) }
            composable(ROUTE_DEVICE) { DeviceScreen(viewModel) }
        }
    }
}
