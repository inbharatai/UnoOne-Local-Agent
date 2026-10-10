package com.unoone.agent.task

import com.unoone.agent.NativeDeviceGoal
import com.unoone.agent.core.device.DeviceOutcomeStatus
import com.unoone.agent.core.device.ReviewedInteraction
import com.unoone.agent.core.device.ReviewedOperation
import com.unoone.agent.core.device.NativeTargetSelector
import com.unoone.agent.core.task.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTaskCompletionTest {
    private val gmail = "com.google.android.gm"

    @Test fun openingAndReadingCanCompleteTheirExplicitGoals() {
        assertEquals(TaskOutcome.VERIFIED, deviceTaskResult(DeviceOutcomeStatus.VERIFIED,
            NativeDeviceGoal.OpenApp(gmail)).outcome)
        assertEquals(TaskOutcome.VERIFIED, deviceTaskResult(DeviceOutcomeStatus.VERIFIED,
            NativeDeviceGoal.Current("read")).outcome)
        assertEquals(TaskOutcome.VERIFIED, deviceTaskResult(DeviceOutcomeStatus.VERIFIED,
            NativeDeviceGoal.Sequence(listOf(NativeDeviceGoal.OpenApp(gmail), NativeDeviceGoal.ReadScreen(gmail)))).outcome)
    }

    @Test fun visibleNameAndNativeInteractionNeverClaimWiderCompletion() {
        val interaction = NativeDeviceGoal.Interact(ReviewedInteraction(
            NativeTargetSelector("Search"), ReviewedOperation.FOCUS), gmail)
        listOf(NativeDeviceGoal.Find(gmail, "Madhav"), interaction,
            NativeDeviceGoal.Sequence(listOf(NativeDeviceGoal.OpenApp(gmail), interaction)),
            NativeDeviceGoal.Current("back")).forEach { goal ->
            assertEquals(goal.toString(), TaskOutcome.ACTION_VERIFIED,
                deviceTaskResult(DeviceOutcomeStatus.VERIFIED, goal).outcome)
        }
        assertEquals("Action verified. The wider task was not established as complete. Native check passed",
            deviceTaskReport(deviceTaskResult(DeviceOutcomeStatus.VERIFIED, interaction), "Native check passed"))
    }

    @Test fun failedActionCannotBePromotedByItsGoalDescriptor() {
        val goal = NativeDeviceGoal.Find(gmail, "Madhav")
        assertEquals(TaskOutcome.NEEDS_USER, deviceTaskResult(DeviceOutcomeStatus.NEEDS_USER, goal).outcome)
        assertEquals(TaskOutcome.FAILED, deviceTaskResult(DeviceOutcomeStatus.LIMIT_REACHED, goal).outcome)
    }
}
