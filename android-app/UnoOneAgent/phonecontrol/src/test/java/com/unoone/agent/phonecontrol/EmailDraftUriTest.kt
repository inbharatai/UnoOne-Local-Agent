package com.unoone.agent.phonecontrol

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailDraftUriTest {
    @Test
    fun recipientSubjectAndBodySurviveUriEncoding() {
        val uri = EmailDraftUri.build(
            to = "test@example.com",
            subject = "Project Update & Review",
            body = "The meeting is tomorrow at 4 PM."
        )

        val encodedSsp = uri.encodedSchemeSpecificPart
        val query = encodedSsp.substringAfter("?")
            .split("&")
            .associate { part ->
                val (key, value) = part.split("=", limit = 2)
                key to Uri.decode(value)
            }
        assertEquals("mailto", uri.scheme)
        assertEquals("test@example.com", Uri.decode(encodedSsp.substringBefore("?")))
        assertEquals("Project Update & Review", query["subject"])
        assertEquals("The meeting is tomorrow at 4 PM.", query["body"])
    }
}
