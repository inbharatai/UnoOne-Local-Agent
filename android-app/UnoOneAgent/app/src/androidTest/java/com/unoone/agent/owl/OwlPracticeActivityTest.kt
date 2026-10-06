package com.unoone.agent.owl

import android.content.Intent
import com.unoone.agent.R
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Native practice UI test only: does NOT invoke capture, inference, or prove agent task success. */
class OwlPracticeActivityTest {
    @Test fun searchUsesFrameworkViewsAndChangesNativeResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, OwlPracticeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OwlPracticeActivity
        try {
            instrumentation.runOnMainSync {
                val field = activity.findViewById<EditText>(R.id.owl_practice_search_field)
                assertTrue(field.hasFocus())
                assertFalse(field.showSoftInputOnFocus)
                val button = activity.findViewById<TextView>(R.id.owl_practice_search_button)
                val parent = button.parent as ViewGroup
                assertTrue(button.performClick())
                assertNull(button.parent)
                assertTrue((0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.owl_practice_results) })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
