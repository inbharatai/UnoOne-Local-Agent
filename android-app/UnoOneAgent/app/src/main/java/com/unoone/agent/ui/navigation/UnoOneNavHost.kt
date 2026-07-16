package com.unoone.agent.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.unoone.agent.ui.screens.AuditViewerScreen
import com.unoone.agent.ui.screens.LanguagePacksScreen
import com.unoone.agent.ui.screens.LogsScreen
import com.unoone.agent.ui.screens.ModelStatusScreen
import com.unoone.agent.ui.screens.NotesScreen
import com.unoone.agent.ui.screens.PrivacySettingsScreen
import com.unoone.agent.ui.screens.SecureBrowserScreen
import com.unoone.agent.ui.screens.SettingsScreen
import com.unoone.agent.ui.screens.SkillsScreen
import com.unoone.agent.ui.screens.VoiceTestScreen
import com.unoone.agent.ui.viewmodel.AgentViewModel
import com.unoone.agent.ui.viewmodel.AuditViewerViewModel
import com.unoone.agent.ui.viewmodel.LanguagePacksViewModel
import com.unoone.agent.ui.viewmodel.LogsViewModel
import com.unoone.agent.ui.viewmodel.ModelStatusViewModel
import com.unoone.agent.ui.viewmodel.NotesViewModel
import com.unoone.agent.ui.viewmodel.PrivacySettingsViewModel
import com.unoone.agent.ui.viewmodel.SecureBrowserViewModel
import com.unoone.agent.ui.viewmodel.SettingsViewModel
import com.unoone.agent.ui.viewmodel.SkillsViewModel
import com.unoone.agent.ui.viewmodel.VoiceTestViewModel

@Composable
fun UnoOneNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    agentViewModel: AgentViewModel,
    notesViewModel: NotesViewModel,
    logsViewModel: LogsViewModel,
    skillsViewModel: SkillsViewModel,
    settingsViewModel: SettingsViewModel,
    privacySettingsViewModel: PrivacySettingsViewModel,
    modelStatusViewModel: ModelStatusViewModel,
    languagePacksViewModel: LanguagePacksViewModel,
    voiceTestViewModel: VoiceTestViewModel,
    auditViewerViewModel: AuditViewerViewModel,
    secureBrowserViewModel: SecureBrowserViewModel
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
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
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
            composable(Screen.Agent.route) {
                val voiceLanguage by settingsViewModel.voiceLanguage.collectAsState()
                val skills by skillsViewModel.skills.collectAsState()
                AgentScreen(
                    viewModel = agentViewModel,
                    voiceLanguage = voiceLanguage,
                    onVoiceLanguageSelected = settingsViewModel::setVoiceLanguage,
                    onNavigateToSecureBrowser = { navController.navigate(Screen.SecureBrowser.route) },
                    skillCount = skills.size,
                    onNavigateToSkills = { navController.navigate(Screen.Skills.route) }
                )
            }
            composable(Screen.Notes.route) { NotesScreen(viewModel = notesViewModel) }
            composable(Screen.Skills.route) { SkillsScreen(viewModel = skillsViewModel) }
            composable(Screen.Logs.route) { LogsScreen(viewModel = logsViewModel) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToPrivacy = { navController.navigate(Screen.PrivacySettings.route) },
                    onNavigateToModels = { navController.navigate(Screen.Models.route) },
                    onNavigateToLanguagePacks = { navController.navigate(Screen.LanguagePacks.route) },
                    onNavigateToVoiceTest = { navController.navigate(Screen.VoiceTest.route) },
                    onNavigateToAudit = { navController.navigate(Screen.Audit.route) },
                    onNavigateToSecureBrowser = { navController.navigate(Screen.SecureBrowser.route) }
                )
            }
            composable(Screen.PrivacySettings.route) {
                PrivacySettingsScreen(viewModel = privacySettingsViewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Models.route) {
                ModelStatusScreen(viewModel = modelStatusViewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.LanguagePacks.route) {
                LanguagePacksScreen(viewModel = languagePacksViewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.VoiceTest.route) {
                VoiceTestScreen(viewModel = voiceTestViewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.Audit.route) {
                AuditViewerScreen(viewModel = auditViewerViewModel, onBack = { navController.popBackStack() })
            }
            composable(Screen.SecureBrowser.route) {
                SecureBrowserScreen(viewModel = secureBrowserViewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

private val Screen.selectedIcon: ImageVector
    get() = when (this) {
        Screen.Agent -> Icons.Filled.Home
        Screen.Notes -> Icons.AutoMirrored.Filled.Article
        Screen.Skills -> Icons.Filled.Build
        Screen.Logs -> Icons.AutoMirrored.Filled.List
        Screen.Settings -> Icons.Filled.Settings
        Screen.PrivacySettings -> Icons.Filled.Settings
        Screen.Models -> Icons.Filled.Memory
        Screen.LanguagePacks -> Icons.Filled.Language
        Screen.VoiceTest -> Icons.Filled.GraphicEq
        Screen.Audit -> Icons.AutoMirrored.Filled.ReceiptLong
        Screen.SecureBrowser -> Icons.Filled.Language
    }

private val Screen.unselectedIcon: ImageVector
    get() = when (this) {
        Screen.Agent -> Icons.Outlined.Home
        Screen.Notes -> Icons.AutoMirrored.Outlined.Article
        Screen.Skills -> Icons.Outlined.Build
        Screen.Logs -> Icons.AutoMirrored.Outlined.List
        Screen.Settings -> Icons.Outlined.Settings
        Screen.PrivacySettings -> Icons.Outlined.Settings
        Screen.Models -> Icons.Outlined.Memory
        Screen.LanguagePacks -> Icons.Outlined.Language
        Screen.VoiceTest -> Icons.Outlined.GraphicEq
        Screen.Audit -> Icons.AutoMirrored.Outlined.ReceiptLong
        Screen.SecureBrowser -> Icons.Outlined.Language
    }
