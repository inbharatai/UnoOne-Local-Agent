package com.unoone.agent.ui.navigation

sealed class Screen(val route: String, val label: String) {
    data object Agent : Screen("agent", "Agent")
    data object Notes : Screen("notes", "Notes")
    data object Skills : Screen("skills", "Skills")
    data object Logs : Screen("logs", "Logs")
    data object Settings : Screen("settings", "Settings")
    data object PrivacySettings : Screen("privacy_settings", "Privacy")
    // Reached from Settings — not part of the bottom nav, to keep nav clean.
    data object Models : Screen("models", "Models")
    data object VoiceTest : Screen("voice_test", "Voice Test")
    data object Audit : Screen("audit", "Audit")
}

val bottomNavItems = listOf(
    Screen.Agent,
    Screen.Notes,
    Screen.Skills,
    Screen.Logs,
    Screen.Settings
)