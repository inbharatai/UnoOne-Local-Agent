package com.unoone.agent

import com.unoone.agent.core.device.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeDeviceCommandsTest {
    @Test fun naturalCurrentAppCommandsAreNativeScoped() {
        assertEquals(NativeDeviceGoal.Current("read"), NativeDeviceCommands.parse("read screen"))
        assertEquals(NativeDeviceGoal.Current("down"), NativeDeviceCommands.parse("scroll down"))
        assertEquals(NativeDeviceGoal.Current("back"), NativeDeviceCommands.parse("go back"))
        assertEquals(NativeDeviceGoal.Find("com.whatsapp", "Pankaj"), NativeDeviceCommands.parse("find WhatsApp chat with Pankaj"))
        assertTrue(NativeDeviceCommands.parse("draft: hello") is NativeDeviceGoal.NeedsUser)
    }
    @Test fun ordinaryChatIsNotCaptured() {
        listOf("hello", "what is youtube", "how do I open settings", "open settings and delete data")
            .forEach { assertNull(NativeDeviceCommands.parse(it)) }
    }
    @Test fun knownOpenCommandHasExactNativeDescriptor() {
        assertEquals(NativeDeviceGoal.OpenApp("com.android.settings"), NativeDeviceCommands.parse("open settings"))
        assertEquals(NativeDeviceGoal.OpenApp("com.android.chrome"), NativeDeviceCommands.parse("DEVICE: launch chrome"))
    }
    @Test fun explicitUnknownWorkflowHandsOver() {
        listOf("device: buy a ticket", "device: tap continue", "device:", "device: open settings and send mail")
            .forEach { assertTrue(NativeDeviceCommands.parse(it) is NativeDeviceGoal.NeedsUser) }
    }
    @Test fun explicitPackageScopeIsNotInferredFromChat() {
        assertNull(NativeDeviceCommands.parse("open org.example.app"))
        assertEquals(NativeDeviceGoal.OpenApp("org.example.app"), NativeDeviceCommands.parse("device: open org.example.app"))
    }
    @Test fun unknownGoalDoesNotAcquireAdapterOrBrain() = runBlocking {
        val session = DeviceAgentSession(
            brainProvider = { error("must not invoke model") },
            adapterProvider = { error("must not capture screen") },
            enabled = { true }, clockMs = { 0L }
        )
        val outcome = session.run(NativeDeviceGoal.NeedsUser("No native postcondition"))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, outcome.status)
        assertEquals(0, outcome.steps)
    }
    @Test fun missingAccessibilityIsNotSuccess() = runBlocking {
        val session = DeviceAgentSession(adapterProvider = { null }, enabled = { true }, clockMs = { 0L })
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, session.run(NativeDeviceGoal.OpenApp("com.android.settings")).status)
    }
    @Test fun explicitFindPreservesExactTextAndInstalledScope() {
        assertEquals(NativeDeviceGoal.Find("com.google.android.gm", "Exact Case"),
            NativeDeviceCommands.parse("device: find Exact Case in gmail") { if (it == "gmail") "com.google.android.gm" else null })
        assertTrue(NativeDeviceCommands.parse("device: find X in missing") { null } is NativeDeviceGoal.NeedsUser)
        assertNull(NativeDeviceCommands.parse("find X in gmail"))
    }
    @Test fun fieldPackageMustBeExplicitAndInstalled() {
        val goal = NativeDeviceCommands.parse("device: set com.whatsapp:id/search_src_text to hello") { if (it == "com.whatsapp") it else null }
        assertEquals(NativeDeviceGoal.SetField("com.whatsapp", "com.whatsapp:id/search_src_text", "hello"), goal)
        assertEquals(NativeDeviceGoal.Current("click", "Search"), NativeDeviceCommands.parse("device: click Search") { "com.whatsapp" })
    }
    @Test fun reviewedSearchCandidatesAreNarrowNotGenericFields() {
        assertEquals(TargetSemantic.FORM_FIELD, NativeReviewedTargets.semantic("com.whatsapp", "com.whatsapp:id/search_src_text", "android.widget.EditText"))
        assertEquals(TargetSemantic.UNKNOWN, NativeReviewedTargets.semantic("com.whatsapp", "com.whatsapp:id/message", "android.widget.EditText"))
        assertEquals(TargetSemantic.UNKNOWN, NativeReviewedTargets.semantic("com.whatsapp", "com.whatsapp:id/search_src_text", "android.widget.Button"))
    }
}
