package com.unoone.agent

import android.content.Intent
import android.net.Uri
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
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var agentOrchestrator: AgentOrchestrator

    companion object {
        private const val PREFS_NAME = "unoone_permissions"
        private const val KEY_PROMPTED_VERSION = "permissions_prompted_version"
        private const val CURRENT_PERMISSIONS_VERSION = 2
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            agentOrchestrator.clearPendingAndReExecute()
            markPermissionsPrompted()
        } else {
            val permanentlyDenied = PermissionManager.getPermanentlyDeniedPermissions(this)
            if (permanentlyDenied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Some permissions were permanently denied. Please enable them in Settings.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(PermissionManager.getAppSettingsIntent(this))
            } else {
                Toast.makeText(this, "Expert features require the requested permissions.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as UnoOneApplication
        agentOrchestrator = app.orchestrator
        val database = DatabaseProvider.getDatabase(this)
        val voiceModule = app.sharedVoiceModule

        agentOrchestrator.onPermissionRequired = { missing ->
            requestPermissionLauncher.launch(missing.toTypedArray())
        }

        val agentViewModel = AgentViewModel(agentOrchestrator, voiceModule)
        val notesViewModel = NotesViewModel(database.noteDao())
        val logsViewModel = LogsViewModel(database.actionLogDao())
        val skillsViewModel = SkillsViewModel(agentOrchestrator.skillsModule)
        val settingsViewModel = SettingsViewModel(this)
        val privacySettingsViewModel = PrivacySettingsViewModel(this)
        val modelStatusViewModel = ModelStatusViewModel(this, database.modelMetadataDao(), agentOrchestrator)
        val languagePacksViewModel = LanguagePacksViewModel(this)
        val voiceTestViewModel = VoiceTestViewModel(voiceModule)
        val auditViewerViewModel = AuditViewerViewModel(database.actionLogDao())
        val secureBrowserViewModel = SecureBrowserViewModel(
            this,
            app.secureBrowserModelLease,
            database.actionLogDao()
        )

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
                        settingsViewModel = settingsViewModel,
                        privacySettingsViewModel = privacySettingsViewModel,
                        modelStatusViewModel = modelStatusViewModel,
                        languagePacksViewModel = languagePacksViewModel,
                        voiceTestViewModel = voiceTestViewModel,
                        auditViewerViewModel = auditViewerViewModel,
                        secureBrowserViewModel = secureBrowserViewModel
                    )
                }
            }
        }

        checkInitialExpertPermissionsIfNeeded()
    }

    private fun checkInitialExpertPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val promptedVersion = prefs.getInt(KEY_PROMPTED_VERSION, 0)
        if (promptedVersion < CURRENT_PERMISSIONS_VERSION) checkInitialExpertPermissions()
    }

    private fun markPermissionsPrompted() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_PROMPTED_VERSION, CURRENT_PERMISSIONS_VERSION)
            .apply()
    }

    private fun checkInitialExpertPermissions() {
        val missing = PermissionManager.getMissingPermissions(this)
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing.toTypedArray())

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "Enable 'Display over other apps' for the floating AI.", Toast.LENGTH_LONG).show()
        } else {
            startService(Intent(this, FloatingAgentService::class.java))
        }

        if (!UnoOneAccessibilityService.isEnabled()) {
            Toast.makeText(
                this,
                "Enable UnoOne Accessibility Service for native-app and external-browser automation.",
                Toast.LENGTH_LONG
            ).show()
        }

        requestBatteryOptimizationExemption()
        markPermissionsPrompted()
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Please disable battery optimization for UnoOne in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        PermissionManager.getAutostartIntent(this)?.let { intent ->
            try {
                startActivity(intent)
                Toast.makeText(this, "Please enable autostart for UnoOne.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                // Manufacturer-specific activity is unavailable.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingAgentService::class.java))
        }
        (application as? UnoOneApplication)?.reloadLlmIfUnloaded()
    }
}

@Composable
fun UnoOneApp(
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
    val navController = rememberNavController()
    UnoOneNavHost(
        navController = navController,
        agentViewModel = agentViewModel,
        notesViewModel = notesViewModel,
        logsViewModel = logsViewModel,
        skillsViewModel = skillsViewModel,
        settingsViewModel = settingsViewModel,
        privacySettingsViewModel = privacySettingsViewModel,
        modelStatusViewModel = modelStatusViewModel,
        languagePacksViewModel = languagePacksViewModel,
        voiceTestViewModel = voiceTestViewModel,
        auditViewerViewModel = auditViewerViewModel,
        secureBrowserViewModel = secureBrowserViewModel
    )
}
