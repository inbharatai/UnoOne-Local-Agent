package com.unoone.agent.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallbackMulticastTest {

    @Test
    fun addAndInvokeListener() {
        val multicast = CallbackMulticast<(String) -> Unit>()
        var received = ""
        multicast.add { msg -> received = msg }
        multicast.invokeAll { it("hello") }
        assertEquals("hello", received)
    }

    @Test
    fun multipleListenersAllInvoked() {
        val multicast = CallbackMulticast<(String) -> Unit>()
        val results = mutableListOf<String>()
        multicast.add { msg -> results.add("1:$msg") }
        multicast.add { msg -> results.add("2:$msg") }
        multicast.invokeAll { it("test") }
        assertEquals(2, results.size)
        assertEquals("1:test", results[0])
        assertEquals("2:test", results[1])
    }

    @Test
    fun removeListenerNoLongerReceives() {
        val multicast = CallbackMulticast<(String) -> Unit>()
        var callCount = 0
        val listener: (String) -> Unit = { _ -> callCount++ }
        multicast.add(listener)
        multicast.invokeAll { it("first") }
        assertEquals(1, callCount)

        multicast.remove(listener)
        multicast.invokeAll { it("second") }
        assertEquals(1, callCount) // Not called again
    }

    @Test
    fun hasListenersReturnsFalseWhenEmpty() {
        val multicast = CallbackMulticast<(String) -> Unit>()
        assertFalse(multicast.hasListeners)
    }

    @Test
    fun hasListenersReturnsTrueAfterAdd() {
        val multicast = CallbackMulticast<(String) -> Unit>()
        multicast.add { _ -> }
        assertTrue(multicast.hasListeners)
    }

    @Test
    fun invokeAllWithNoListenersDoesNothing() {
        val multicast = CallbackMulticast<(String) -> Unit>()
        // Should not throw
        multicast.invokeAll { it("test") }
    }

    // === ConfirmationListener type alias test ===

    @Test
    fun confirmationListenerMulticast() {
        val multicast = CallbackMulticast<ConfirmationListener>()
        var result = false
        multicast.add { message, callback -> callback(true) }
        multicast.invokeAll { listener -> listener("Confirm?") { result = true } }
        assertTrue(result)
    }

    // === PermissionListener type alias test ===

    @Test
    fun permissionListenerMulticast() {
        val multicast = CallbackMulticast<PermissionListener>()
        var receivedPermissions = listOf<String>()
        multicast.add { permissions -> receivedPermissions = permissions }
        multicast.invokeAll { it(listOf("RECORD_AUDIO", "CAMERA")) }
        assertEquals(listOf("RECORD_AUDIO", "CAMERA"), receivedPermissions)
    }
}