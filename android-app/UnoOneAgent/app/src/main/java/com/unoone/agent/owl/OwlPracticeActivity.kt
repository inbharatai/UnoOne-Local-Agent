package com.unoone.agent.owl

import android.app.Activity
import com.unoone.agent.R
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.*

/** Native framework demo only. Never evidence of model vision or real-phone task success. */
class OwlPracticeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        root.addView(TextView(this).apply { setText(R.string.owl_practice_title) })
        val field = EditText(this).apply {
            id = R.id.owl_practice_search_field
            setHint(R.string.owl_practice_hint)
            showSoftInputOnFocus = false
            isSingleLine = true
        }
        root.addView(field)
        val result = TextView(this).apply { id = R.id.owl_practice_result; setText(R.string.owl_practice_empty) }
        root.addView(TextView(this).apply {
            id = R.id.owl_practice_search_button
            setText(R.string.owl_practice_search)
            setOnClickListener {
                root.removeView(this) // Native navigation: submit disappears and a new readable result appears.
                result.setText(R.string.owl_practice_results)
                result.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        })
        root.addView(result)
        root.addView(TextView(this).apply { setText(R.string.owl_practice_notice) })
        setContentView(root)
        field.requestFocus()
    }
}
