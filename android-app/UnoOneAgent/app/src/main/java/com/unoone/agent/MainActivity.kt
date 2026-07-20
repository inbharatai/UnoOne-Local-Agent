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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.runtime.AgentRuntimeGate
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

        // System permissions (Accessibility / MediaProjection / Overlay) cannot be granted via the
        // runtime-permission dialog — they each need their own settings/consent screen. Surface the
        // first still-missing one as a one-tap deep-link, stash the command in the orchestrator
        // (it sets pendingCommand itself before invoking this callback), and resume it on return.
        agentOrchestrator.onSystemPermissionRequired = { missing ->
            val intent = missing.firstNotNullOfOrNull { req ->
                PermissionManager.getRequirementIntent(this, req)
            }
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        "Grant the system access, then return — your command resumes.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Unable to open system settings.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "System access is required for that action.", Toast.LENGTH_LONG).show()
            }
        }

        // Keep the AgentViewModel across configuration changes. Constructing it manually here
        // recreated disable collectors and transient state on every Activity recreation.
        val agentViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
                        return AgentViewModel(agentOrchestrator, voiceModule, app) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        )[AgentViewModel::class.java]
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
            database.actionLogDao(),
            voiceModule
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
        if (AgentRuntimeGate.isEnabled() && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingAgentService::class.java))
        }
        if (AgentRuntimeGate.isEnabled()) {
            (application as? UnoOneApplication)?.reloadLlmIfUnloaded()
        }
        // Resume a command that was paused on a missing system permission once the user returns
        // from the settings/consent screen. No-op when nothing is pending; the orchestrator re-checks
        // access and re-surfaces only whatever is still missing.
        if (AgentRuntimeGate.isEnabled()) agentOrchestrator.clearPendingAndReExecute()
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

    // Eyes-free (WS4): bridge the `secure_browser_task` tool (fired by the orchestrator with an
    // already-approved origin) to the Secure Browser screen — navigate there and stash the pending
    // (origin, task) so the PageAgent run starts once the Gemma lease + runtime are ready. The live
    // executeTask + spoken page read are device-time gates; this wiring only requests the UI handoff.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        agentViewModel.setSecureBrowserTaskHandler { origin, task ->
            secureBrowserViewModel.setPendingTask(origin, task)
            navController.navigate(com.unoone.agent.ui.navigation.Screen.SecureBrowser.route)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { agentViewModel.setSecureBrowserTaskHandler(null) }
    }

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
