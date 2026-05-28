package com.unoone.agent.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.unoone.agent.ui.screens.AgentScreen
import com.unoone.agent.ui.screens.LogsScreen
import com.unoone.agent.ui.screens.NotesScreen
import com.unoone.agent.ui.screens.SettingsScreen
import com.unoone.agent.ui.screens.SkillsScreen
import com.unoone.agent.ui.viewmodel.AgentViewModel
import com.unoone.agent.ui.viewmodel.LogsViewModel
import com.unoone.agent.ui.viewmodel.NotesViewModel
import com.unoone.agent.ui.viewmodel.SettingsViewModel
import com.unoone.agent.ui.viewmodel.SkillsViewModel

@Composable
fun UnoOneNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    agentViewModel: AgentViewModel,
    notesViewModel: NotesViewModel,
    logsViewModel: LogsViewModel,
    skillsViewModel: SkillsViewModel,
    settingsViewModel: SettingsViewModel
) {
    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    val icon = if (selected) screen.selectedIcon else screen.unselectedIcon
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = selected,
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Agent.route,
            modifier = modifier.padding(innerPadding)
        ) {
            composable(Screen.Agent.route) { AgentScreen(viewModel = agentViewModel) }
            composable(Screen.Notes.route) { NotesScreen(viewModel = notesViewModel) }
            composable(Screen.Skills.route) { SkillsScreen(viewModel = skillsViewModel) }
            composable(Screen.Logs.route) { LogsScreen(viewModel = logsViewModel) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel = settingsViewModel) }
        }
    }
}

private val Screen.selectedIcon: ImageVector
    get() = when (this) {
        Screen.Agent -> Icons.Filled.Home
        Screen.Notes -> Icons.AutoMirrored.Filled.Article
        Screen.Skills -> Icons.Filled.Build
        Screen.Logs -> Icons.Filled.List
        Screen.Settings -> Icons.Filled.Settings
    }

private val Screen.unselectedIcon: ImageVector
    get() = when (this) {
        Screen.Agent -> Icons.Outlined.Home
        Screen.Notes -> Icons.AutoMirrored.Outlined.Article
        Screen.Skills -> Icons.Outlined.Build
        Screen.Logs -> Icons.Outlined.List
        Screen.Settings -> Icons.Outlined.Settings
    }