package com.unoone.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {

    // === Result core ===

    @Test
    fun successIsSuccess() {
        val result = Result.Success("hello")
        assertTrue(result.isSuccess)
        assertFalse(result.isError)
    }

    @Test
    fun errorIsError() {
        val result = Result.Error("failed")
        assertTrue(result.isError)
        assertFalse(result.isSuccess)
    }

    // === onSuccess / onError ===

    @Test
    fun onSuccessCallsAction() {
        var called = false
        Result.Success("data").onSuccess { called = true }
        assertTrue(called)
    }

    @Test
    fun onErrorDoesNotCallSuccessAction() {
        var called = false
        Result.Error("fail").onSuccess { called = true }
        assertFalse(called)
    }

    @Test
    fun onErrorCallsAction() {
        var called = false
        Result.Error("fail").onError { _, _ -> called = true }
        assertTrue(called)
    }

    @Test
    fun onSuccessDoesNotCallErrorAction() {
        var called = false
        Result.Success("data").onError { _, _ -> called = true }
        assertFalse(called)
    }

    // === mapCatching ===

    @Test
    fun mapCatchingTransformsSuccess() {
        val result = Result.Success(5).mapCatching { it * 2 }
        assertEquals(Result.Success(10), result)
    }

    @Test
    fun mapCatchingPassesThroughError() {
        val result: Result<Int> = Result.Error("fail")
        val mapped = result.mapCatching { it * 2 }
        assertTrue(mapped is Result.Error)
        assertEquals("fail", (mapped as Result.Error).message)
    }

    @Test
    fun mapCatchingCatchesException() {
        val result = Result.Success("hello").mapCatching { throw IllegalStateException("boom") }
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message!!.contains("boom"))
    }

    // === map ===

    @Test
    fun mapTransformsSuccess() {
        val result = Result.Success(10).map { it + 5 }
        assertEquals(Result.Success(15), result)
    }

    @Test
    fun mapPassesThroughError() {
        val result: Result<Int> = Result.Error("fail")
        val mapped = result.map { it + 5 }
        assertTrue(mapped is Result.Error)
    }

    // === flatMapCatching ===

    @Test
    fun flatMapCatchingChainsSuccess() {
        val result = Result.Success(5).flatMapCatching { Result.Success(it * 3) }
        assertEquals(Result.Success(15), result)
    }

    @Test
    fun flatMapCatchingPassesThroughError() {
        val result: Result<Int> = Result.Error("fail")
        val mapped = result.flatMapCatching { Result.Success(it * 3) }
        assertTrue(mapped is Result.Error)
    }

    @Test
    fun flatMapCatchingCatchesException() {
        val result: Result<Int> = Result.Success(5).flatMapCatching { throw IllegalStateException("chain fail") }
        assertTrue(result is Result.Error)
    }

    // === getOrDefault ===

    @Test
    fun getOrDefaultReturnsDataOnSuccess() {
        assertEquals("hello", Result.Success("hello").getOrDefault("default"))
    }

    @Test
    fun getOrDefaultReturnsDefaultOnError() {
        assertEquals("default", Result.Error("fail").getOrDefault("default"))
    }

    // === getOrNull ===

    @Test
    fun getOrNullReturnsDataOnSuccess() {
        assertEquals("hello", Result.Success("hello").getOrNull())
    }

    @Test
    fun getOrNullReturnsNullOnError() {
        assertNull(Result.Error("fail").getOrNull())
    }

    // === errorOrNull ===

    @Test
    fun errorOrNullReturnsNullOnSuccess() {
        assertNull(Result.Success("hello").errorOrNull())
    }

    @Test
    fun errorOrNullReturnsMessageOnError() {
        assertEquals("fail", Result.Error("fail").errorOrNull())
    }
}