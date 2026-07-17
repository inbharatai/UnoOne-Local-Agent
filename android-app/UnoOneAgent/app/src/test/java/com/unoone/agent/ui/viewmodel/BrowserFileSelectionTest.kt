package com.unoone.agent.ui.viewmodel

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserFileSelectionTest {
    @Test
    fun emptyAcceptListOffersEverySupportedDocumentType() {
        val intent = BrowserFileSelection.intent(emptyArray(), allowMultiple = true)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(
            BrowserFileSelection.SUPPORTED_MIME_TYPES,
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        )
    }

    @Test
    fun singleAndMultipleContentUrisRoundTripWithoutPathResolution() {
        val first = Uri.parse("content://documents/report.pdf")
        val second = Uri.parse("content://documents/photo.jpg")
        val intent = Intent().apply {
            data = first
            clipData = ClipData.newRawUri("selected", second)
        }
        assertArrayEquals(
            arrayOf(first, second),
            BrowserFileSelection.uris(Activity.RESULT_OK, intent)
        )
        assertNull(BrowserFileSelection.uris(Activity.RESULT_CANCELED, intent))
    }
}
