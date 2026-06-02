package com.unoone.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.ui.navigation.UnoOneNavHost
import com.unoone.agent.ui.theme.UnoOneTheme
import com.unoone.agent.ui.viewmodel.AgentViewModel
import com.unoone.agent.ui.viewmodel.LogsViewModel
import com.unoone.agent.ui.viewmodel.NotesViewModel
import com.unoone.agent.ui.viewmodel.SettingsViewModel
import com.unoone.agent.ui.viewmodel.SkillsViewModel
import com.unoone.agent.voice.VoiceModule

class MainActivity : ComponentActivity() {

    private lateinit var agentOrchestrator: AgentOrchestrator

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            // Re-execute pending command after permissions granted
            agentOrchestrator.clearPendingAndReExecute()
        } else {
            val permanentlyDenied = PermissionManager.getPermanentlyDeniedPermissions(this)
            if (permanentlyDenied.isNotEmpty()) {
                Toast.makeText(this, "Some permissions were permanently denied. Please enable them in Settings.", Toast.LENGTH_LONG).show()
                val intent = PermissionManager.getAppSettingsIntent(this)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Expert features require all permissions.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as UnoOneApplication
        agentOrchestrator = app.orchestrator
        val database = DatabaseProvider.getDatabase(this)

        // Use the single shared VoiceModule from the Application — no duplicate instances
        val voiceModule = app.sharedVoiceModule

        agentOrchestrator.onPermissionRequired = { missing ->
            requestPermissionLauncher.launch(missing.toTypedArray())
        }

        val agentViewModel = AgentViewModel(agentOrchestrator, voiceModule)
        val notesViewModel = NotesViewModel(database.noteDao())
        val logsViewModel = LogsViewModel(database.actionLogDao())
        val skillsViewModel = SkillsViewModel(agentOrchestrator.skillsModule)
        val settingsViewModel = SettingsViewModel(this)

        setContent {
            UnoOneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnoOneApp(
                        agentViewModel = agentViewModel,
                        notesViewModel = notesViewModel,
                        logsViewModel = logsViewModel,
                        skillsViewModel = skillsViewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }

        checkInitialExpertPermissions()
    }

    private fun checkInitialExpertPermissions() {
        val missing = PermissionManager.getMissingPermissions(this)
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }

        // Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Enable 'Display over other apps' for the floating AI.", Toast.LENGTH_LONG).show()
        } else {
            startService(Intent(this, FloatingAgentService::class.java))
        }

        // Accessibility service
        if (!UnoOneAccessibilityService.isEnabled()) {
            Toast.makeText(this, "Please enable UnoOne Accessibility Service in Settings for deep automation.", Toast.LENGTH_LONG).show()
        }

        // Battery optimization — important for all manufacturers that kill background services
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Some devices don't support this intent
                Toast.makeText(this, "Please disable battery optimization for UnoOne in Settings.", Toast.LENGTH_LONG).show()
            }
        }

        // Manufacturer-specific autostart settings (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Asus)
        val autostartIntent = PermissionManager.getAutostartIntent(this)
        if (autostartIntent != null) {
            try {
                startActivity(autostartIntent)
                Toast.makeText(this, "Please enable autostart for UnoOne.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                // Activity not available on this device
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingAgentService::class.java))
        }
        // Re-check system permissions
        if (!UnoOneAccessibilityService.isEnabled()) {
            // Don't toast on every resume, only on initial check
        }
    }
}

@Composable
fun UnoOneApp(
    agentViewModel: AgentViewModel,
    notesViewModel: NotesViewModel,
    logsViewModel: LogsViewModel,
    skillsViewModel: SkillsViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
    UnoOneNavHost(
        navController = navController,
        agentViewModel = agentViewModel,
        notesViewModel = notesViewModel,
        logsViewModel = logsViewModel,
        skillsViewModel = skillsViewModel,
        settingsViewModel = settingsViewModel
    )
}