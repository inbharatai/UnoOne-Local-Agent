package com.unoone.agent.ui.navigation

sealed class Screen(val route: String, val label: String) {
    data object Agent : Screen("agent", "Agent")
    data object Notes : Screen("notes", "Notes")
    data object Skills : Screen("skills", "Skills")
    data object Logs : Screen("logs", "Logs")
    data object Settings : Screen("settings", "Settings")
}

val bottomNavItems = listOf(
    Screen.Agent,
    Screen.Notes,
    Screen.Skills,
    Screen.Logs,
    Screen.Settings
)
