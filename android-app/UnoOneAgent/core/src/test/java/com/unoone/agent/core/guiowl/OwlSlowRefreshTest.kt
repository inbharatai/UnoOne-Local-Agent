package com.unoone.agent.core.guiowl

import com.unoone.agent.core.device.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic snapshots/receipt hashes are unit fixtures, NOT evidence of real capture or model qualification. */
class OwlSlowRefreshTest {
    private val bounds = RectData(0, 0, 1000, 2000)
    private val node = UiNode("button", 1, "0.1", "example.app", "android.widget.Button", bounds = RectData(100, 200, 300, 400), clickable = true, semantic = TargetSemantic.NAVIGATION)
    private fun snap(id: String, at: Long, n: UiNode = node) = UiSnapshot(id, at, 7, bounds, listOf(UiWindow(1, n.packageName, bounds, listOf(n))))
    private val source = snap("source", 100)
    private val scope = OwlScope("task", 1, 1, "example.app", 1, mapOf(node.id to node.signature()), clickNodeId = node.id)
    private val receipt = OwlCaptureReceipt("task", 1, 1, source.id, UiStateHasher.hash(source), 7, 100, "a".repeat(64), 250, 500, bounds, 0, 0, true, true, true, "example.app", 1)
    private fun output(args: String) = "Action: Review.\n<tool_call>{\"name\":\"mobile_use\",\"arguments\":$args}</tool_call>"
    private val raw = output("{\"action\":\"click\",\"coordinate\":[200,150]}")
    @Test fun slowProposalRequiresRealFreshEquivalentSnapshot() {
        assertTrue(OwlNativeBinding.translate(raw, source, receipt, scope, 60000) is OwlBindingResult.NeedsUser)
        val proposal = OwlNativeBinding.propose(raw, source, receipt, scope)
        val refreshed = OwlNativeBinding.refresh(proposal, snap("fresh", 60000), scope, 60001) as OwlBindingResult.Bound
        assertEquals(DeviceAction.ClickNode("fresh", node.id), refreshed.action)
        assertEquals(receipt.imageSha256, refreshed.imageSha256)
        assertTrue(OwlNativeBinding.refresh(proposal, source, scope, 60001) is OwlBindingResult.NeedsUser)
        assertTrue(OwlNativeBinding.refresh(proposal, snap("fresh", 60000, node.copy(text = "changed")), scope, 60001) is OwlBindingResult.NeedsUser)
    }
    @Test fun cancelledEpochAndWrongPackageNeverRefresh() {
        val proposal = OwlNativeBinding.propose(raw, source, receipt, scope)
        assertTrue(OwlNativeBinding.refresh(proposal, snap("fresh", 60000), scope.copy(epoch = 2), 60001) is OwlBindingResult.NeedsUser)
        assertTrue(OwlNativeBinding.refresh(proposal, snap("fresh", 60000, node.copy(packageName = "other.app")), scope, 60001) is OwlBindingResult.NeedsUser)
    }
    @Test fun doneRemainsAdviceNotVerifiedAction() {
        val proposal = OwlNativeBinding.propose(output("{\"action\":\"terminate\",\"status\":\"success\"}"), source, receipt, scope)
        assertTrue(OwlNativeBinding.refresh(proposal, snap("fresh", 60000), scope, 60001) is OwlBindingResult.ModelDone)
    }
}
