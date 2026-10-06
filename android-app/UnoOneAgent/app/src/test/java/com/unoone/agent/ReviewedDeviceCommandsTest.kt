package com.unoone.agent

import com.unoone.agent.core.device.*
import org.junit.Assert.*
import org.junit.Test

class ReviewedDeviceCommandsTest {
    @Test fun exactOperationsAndDraftValue() {
        assertEquals(NativeDeviceGoal.Interact(ReviewedInteraction(NativeTargetSelector("Search"), ReviewedOperation.CLICK)),
            NativeDeviceCommands.parse("device: click \"Search\""))
        assertEquals(NativeDeviceGoal.Interact(ReviewedInteraction(NativeTargetSelector("Message"), ReviewedOperation.WRITE, "hello")),
            NativeDeviceCommands.parse("device: write \"hello\" into \"Message\""))
        assertEquals(NativeDeviceGoal.Interact(ReviewedInteraction(NativeTargetSelector("Inbox"), ReviewedOperation.SELECT_TAB)),
            NativeDeviceCommands.parse("device: select tab \"Inbox\""))
        assertEquals(NativeDeviceGoal.Interact(ReviewedInteraction(NativeTargetSelector("Alice", 2), ReviewedOperation.CLICK)),
            NativeDeviceCommands.parse("device: click \"Alice\" result 2"))
        assertTrue(NativeDeviceCommands.parse("device: write hello into Message") is NativeDeviceGoal.NeedsUser)
    }
    @Test fun legacyCommandsRemainAndFrameworkRulesArePackageIndependent() {
        assertEquals(NativeDeviceGoal.Current("click", "Search"), NativeDeviceCommands.parse("device: click Search"))
        assertEquals(NativeDeviceGoal.Current("read"), NativeDeviceCommands.parse("read screen"))
        assertEquals(TargetSemantic.FORM_FIELD, NativeReviewedTargets.semantic("org.example.notes", "android:id/search_src_text", "android.widget.EditText"))
        assertEquals(TargetSemantic.UNKNOWN, NativeReviewedTargets.semantic("org.example.notes", "org.example.notes:id/send", "android.widget.Button"))
    }
}
