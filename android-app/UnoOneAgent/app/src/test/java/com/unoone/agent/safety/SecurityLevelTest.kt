package com.unoone.agent.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM coverage for the in-app [SecurityLevel] setting. The orchestrator enforcement behavior
 * (STANDARD keeps judge+block+confirm; RELAXED drops judge + auto-confirms; OFF drops all three)
 * is exercised on-device by the existing safety-pipeline instrumented tests; here we lock the
 * persistence contract: default is STANDARD (never silently weakens safety), every level
 * round-trips, and a corrupt stored value falls back to STANDARD rather than to OFF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityLevelTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Isolate from any other writer of the shared unoone_settings store.
        context.getSharedPreferences(SecurityLevel.PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToStandardWhenUnset() {
        assertEquals(
            "Security level must default to STANDARD (never silently weaken safety)",
            SecurityLevel.STANDARD,
            SecurityLevel.current(context)
        )
    }

    @Test
    fun roundTripsEveryLevel() {
        SecurityLevel.entries.forEach { level ->
            SecurityLevel.set(context, level)
            assertEquals("round-trip failed for $level", level, SecurityLevel.current(context))
        }
    }

    @Test
    fun corruptStoredValueFallsBackToStandard() {
        context.getSharedPreferences(SecurityLevel.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(SecurityLevel.PREF_KEY, "GARBAGE_VALUE").commit()
        assertEquals(
            "A corrupt/unknown stored level must fall back to STANDARD, not OFF",
            SecurityLevel.STANDARD,
            SecurityLevel.current(context)
        )
    }
}