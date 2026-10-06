package com.unoone.agent.core.device

import com.unoone.agent.core.task.*
import org.junit.Assert.*
import org.junit.Test

class ReviewedInteractionTest {
    private val pkg = "org.example.unlisted"
    private val bounds = RectData(0, 0, 100, 100)
    private val owner = InteractionOwner(TaskId("task"), 1, 1, 1)
    private val scope = TaskScope(setOf(TaskCapability.UI_READ, TaskCapability.UI_WRITE), setOf(pkg))
    private fun node(id: String = "n", label: String = "Search", semantic: TargetSemantic = TargetSemantic.NAVIGATION) =
        UiNode(id, 1, id, pkg, "android.widget.Button", text = label, bounds = bounds, clickable = true, semantic = semantic)
    private fun state(vararg nodes: UiNode, id: String = "s", time: Long = 100, event: Long = 1, truncated: Boolean = false) =
        PerceptionState(UiSnapshot(id, time, event, bounds, listOf(UiWindow(1, pkg, bounds, nodes.toList())), truncated))
    private fun bind(s: PerceptionState, op: ReviewedOperation = ReviewedOperation.CLICK, value: String? = null) =
        BoundReviewedInteraction.bind(ReviewedInteraction(NativeTargetSelector("Search"), op, value), pkg, s, owner, scope, 100)

    private fun spoken(label: String) = NativeTargetSelector(label, matchMode = NativeTargetMatchMode.CASE_INSENSITIVE_UNIQUE)

    @Test fun spokenCaseOnlyUniqueAcrossTextDescriptionAndHint() {
        for (n in listOf(node(), node(label = "").copy(description = "Search"), node(label = "").copy(hint = "Search"))) {
            assertEquals(n, NativeTargetResolver.resolve(spoken("search"), state(n), pkg, 100))
        }
        // Even an exact spelling is not privileged over a second case-folded match.
        assertNull(NativeTargetResolver.resolve(spoken("Search"), state(node(), node("two", "SEARCH")), pkg, 100))
        assertNull(NativeTargetResolver.resolve(spoken("search"), state(node(label = "Sеarch")), pkg, 100)) // Cyrillic е
        assertNull(NativeTargetResolver.resolve(spoken("cafe"), state(node(label = "Café")), pkg, 100))
        assertNull(NativeTargetResolver.resolve(spoken("search"), state(node(label = "Search ")), pkg, 100))
        assertNull(NativeTargetResolver.resolve(NativeTargetSelector("search"), state(node()), pkg, 100))
        assertEquals(NativeTargetMatchMode.EXACT, NativeTargetSelector("Search").matchMode)
    }

    @Test fun spokenWritesKeepExactValueAndSensitiveTargetsStayBlocked() {
        val field = node(semantic = TargetSemantic.FORM_FIELD).copy(editable = true, hint = "Search")
        val write = BoundReviewedInteraction.bind(ReviewedInteraction(spoken("search"), ReviewedOperation.WRITE, "AbC"),
            pkg, state(field), owner, scope, 100)!!
        assertEquals(DeviceAction.SetText("s", field.id, "AbC"), write.action)
        assertEquals("AbC", write.request.exactValue)
        assertFalse(write.verified(state(field.copy(text = "abc"), id = "next", time = 101, event = 2), owner, 101))
        assertTrue(write.verified(state(field.copy(text = "AbC"), id = "next", time = 101, event = 2), owner, 101))
        val secret = node(label = "", semantic = TargetSemantic.SECRET).copy(password = true)
        assertNull(BoundReviewedInteraction.bind(ReviewedInteraction(spoken("search"), ReviewedOperation.CLICK),
            pkg, state(secret), owner, scope, 100))
        for (n in listOf(node(label = "Send"), node(label = "Search").copy(description = "Confirm payment"))) {
            assertNull(BoundReviewedInteraction.bind(ReviewedInteraction(spoken(n.text), ReviewedOperation.CLICK),
                pkg, state(n), owner, scope, 100))
        }
    }

    @Test fun uniqueExactOnlyAndNoPackageAllowlist() {
        assertNotNull(bind(state(node())))
        assertNull(bind(state(node(), node("other"))))
        assertNull(bind(state(node(label = "Search everywhere"))))
        assertNull(bind(state(node().copy(enabled = false))))
        assertNull(bind(state(node().copy(visible = false))))
        assertNull(bind(state(node(), truncated = true)))
        assertNull(NativeTargetResolver.resolve(NativeTargetSelector("Search"), state(node()), pkg, 5101))
    }
    @Test fun unknownAndHardBlocksCannotBeExplicitlyApproved() {
        assertNull(bind(state(node(semantic = TargetSemantic.UNKNOWN))))
        for (semantic in TargetSemantic.entries.filter { it.sensitiveObservation() }) {
            val n = node(label = "", semantic = semantic)
            val s = state(n)
            val action = DeviceAction.ClickNode("s", n.id)
            assertTrue(DeviceSafetyPolicy.decide(action, s, DeviceAuthorization(true, setOf(pkg), nativeActionIntent = { _, _ -> true })) is SafetyDecision.Handover)
        }
        assertNull(bind(state(node().copy(description = "Confirm payment"))))
    }
    @Test fun taskOwnerScopeAndSnapshotAreExact() {
        val s = state(node()); val b = bind(s)!!
        val auth = b.authorization(scope) { owner.copy(taskId = TaskId("other")) }
        assertTrue(DeviceSafetyPolicy.decide(b.action, s, auth) is SafetyDecision.Handover)
        assertNull(BoundReviewedInteraction.bind(b.request, pkg, s, owner, TaskScope(), 100))
        assertTrue(DeviceSafetyPolicy.decide(b.action, state(node(), id = "other"), b.authorization(scope) { owner }) is SafetyDecision.Handover)
    }
    @Test fun indexedSelectorNeedsNativeRowAndStillRejectsAmbiguity() {
        val selector = NativeTargetSelector("Search", 2)
        val one = node().copy(collectionRowIndex = 0)
        val two = node("two").copy(collectionRowIndex = 1)
        assertEquals(two, NativeTargetResolver.resolve(selector, state(one, two), pkg, 100))
        assertNull(NativeTargetResolver.resolve(selector, state(one), pkg, 100))
        assertNull(NativeTargetResolver.resolve(selector, state(two, two.copy(id = "three", path = "three")), pkg, 100))
    }
    @Test fun noOpSearchAndSameValueAreNotVerified() {
        val search = node()
        val field = node("field", "hello", TargetSemantic.FORM_FIELD).copy(editable = true)
        val b = bind(state(search, field))!!
        assertFalse(b.verified(state(search, field, id = "next", time = 101, event = 2), owner, 101))
        assertTrue(bind(state(search))!!.verified(state(search, field, id = "next", time = 101, event = 2), owner, 101))
        val editable = node(semantic = TargetSemantic.FORM_FIELD).copy(editable = true, hint = "Search")
        val write = bind(state(editable), ReviewedOperation.WRITE, "hello")!!
        assertFalse(write.verified(state(editable.copy(text = "hello"), id = "next", time = 101, event = 1), owner, 101))
        assertTrue(write.verified(state(editable.copy(text = "hello"), id = "next", time = 101, event = 2), owner, 101))
        assertFalse(bind(state(editable.copy(text = "hello")), ReviewedOperation.WRITE, "hello")!!.verified(
            state(editable.copy(text = "hello"), id = "next", time = 101, event = 2), owner, 101))
    }
}
