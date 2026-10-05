package com.unoone.agent.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.accessibilitycontrol.AndroidDeviceAdapter
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.device.*
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.skills.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SkillsV2ViewModel(app: Application) : AndroidViewModel(app) {
    private val directory = File(app.filesDir, "skills-v2")
    private val store = SkillsV2Store(directory)
    private val epochs = DeviceEpoch()
    private var job: Job? = null
    private val registration = GlobalTaskCancellation.register(this) { it.stop() }
    private val _versions = MutableStateFlow<List<SkillsV2>>(emptyList())
    val versions = _versions.asStateFlow()
    private val _candidate = MutableStateFlow<SkillsV2?>(null)
    val candidate = _candidate.asStateFlow()
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    init { refresh() }
    private fun refresh() {
        _versions.value = directory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("[a-zA-Z0-9_-]+-v[0-9]+\\.json")) }
            .mapNotNull { file -> runCatching { file.inputStream().use { SkillsV2Policy.parse(SkillsV2Policy.readBounded(it)) } }.getOrNull() }
            .sortedWith(compareBy<SkillsV2> { it.id }.thenBy { it.version })
    }
    fun import(uri: Uri) { viewModelScope.launch {
        try {
            _candidate.value = withContext(Dispatchers.IO) {
                require(uri.scheme == "content")
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                    SkillsV2Policy.parse(SkillsV2Policy.readBounded(it))
                }
            }
            _status.value = "Imported for review only; not executable until approved."
        } catch (_: Exception) { _status.value = "Import rejected: invalid, unsupported or oversized workflow." }
    } }
    fun dismissCandidate() { _candidate.value = null }
    fun approve(digest: String) {
        val skill = _candidate.value ?: return
        try {
            require(digest == skill.digest())
            require(_versions.value.none { it.id == skill.id }) { "Candidate requires trusted replay fixtures; unavailable" }
            store.saveInitial(skill, explicitlyApproved = true)
            _candidate.value = null
            refresh()
            _status.value = "Exact version approved and stored immutably. Runtime policy still applies."
        } catch (_: Exception) { _status.value = "Approval blocked: existing ID/version or trusted candidate fixtures unavailable." }
    }
    fun export(skill: SkillsV2, uri: Uri) { viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                require(store.read(skill.id, skill.version).digest() == skill.digest())
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(SkillsV2Policy.export(skill).toByteArray(Charsets.UTF_8))
                }
            }
            _status.value = "Exported workflow definition only."
        } catch (_: Exception) { _status.value = "Export failed." }
    } }
    @Suppress("DEPRECATION")
    private fun installed(skill: SkillsV2) = skill.appVersions.keys.associateWith { pkg ->
        val info = getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
        info.longVersionCode
    }
    fun run(skill: SkillsV2, digest: String, steps: Set<Int>) {
        if (_running.value) return
        val generation = GlobalTaskCancellation.generation
        val epoch = epochs.current()
        _running.value = true
        job = viewModelScope.launch {
            var installedVersions = emptyMap<String, Long>()
            try {
                installedVersions = installed(skill)
                require(store.read(skill.id, skill.version).digest() == digest)
                require(SkillsV2Policy.canRun(skill, digest, steps.toSet(), AgentRuntimeGate.isEnabled(),
                    UnoOneAccessibilityService.isEnabled(), installedVersions))
                val adapter = AndroidDeviceAdapter(requireNotNull(UnoOneAccessibilityService.getInstance()))
                // Native defaults retain UNKNOWN semantics: review never upgrades targets to safe.
                val auth = DeviceAuthorization(observe = true, allowedPackages = skill.appVersions.keys,
                    navigation = skill.steps.any { it.action in listOf(DeviceAction.Back, DeviceAction.Home, DeviceAction.Recents, DeviceAction.Notifications) })
                val guard = DeviceExecutionGuard(epochs, epoch, {
                    AgentRuntimeGate.isEnabled() && generation == GlobalTaskCancellation.generation
                }, auth)
                val verified = SkillsV2Runner(adapter, SystemClock::elapsedRealtime).run(skill, guard, installedVersions)
                guard.check()
                store.recordNativeRun(skill, verified, installedVersions, Build.FINGERPRINT.take(256))
                _status.value = if (verified) "Native postconditions verified." else "Not verified; no automatic retry."
            } catch (e: CancellationException) {
                _status.value = "Stopped; approvals revoked."
            } catch (_: Exception) {
                _status.value = "Blocked or not verified. Check app versions, permissions and preconditions. Unknown/sensitive targets require manual takeover; review never bypasses native policy."
            } finally { _running.value = false }
        }
    }
    fun stop() { epochs.cancel(); job?.cancel() }
    fun stopAll() { GlobalTaskCancellation.cancelAll() }
    override fun onCleared() { stop(); registration.close(); super.onCleared() }
}
