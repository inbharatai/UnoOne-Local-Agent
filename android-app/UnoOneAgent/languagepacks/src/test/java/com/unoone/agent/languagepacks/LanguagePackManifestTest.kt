package com.unoone.agent.languagepacks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePackManifestTest {

    private val loader = LanguagePackCatalogLoader()

    @Test
    fun `parses baseline and planned packs without pretending planned models exist`() {
        val manifest = loader.parse(
            """
            {
              "manifestVersion": 1,
              "packs": [
                {
                  "id": "en-IN-base",
                  "languageCode": "en-IN",
                  "displayName": "English",
                  "nativeName": "English",
                  "version": "1.0.0",
                  "status": "baseline",
                  "requiredModelIds": ["asr-en", "tts-en"],
                  "required": true,
                  "removable": false,
                  "downloadable": true
                },
                {
                  "id": "as-IN-standard",
                  "languageCode": "as-IN",
                  "displayName": "Assamese",
                  "nativeName": "অসমীয়া",
                  "version": "planned",
                  "status": "planned",
                  "requiredModelIds": [],
                  "downloadable": false
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, manifest.manifestVersion)
        assertEquals(2, manifest.packs.size)

        val english = manifest.find("en-IN-base")
        assertNotNull(english)
        assertTrue(english!!.required)
        assertFalse(english.removable)
        assertEquals(listOf("asr-en", "tts-en"), english.requiredModelIds)

        val assamese = manifest.findByLanguageCode("as-in")
        assertNotNull(assamese)
        assertEquals(LanguagePackStatus.planned, assamese!!.status)
        assertFalse(assamese.downloadable)
        assertTrue(assamese.requiredModelIds.isEmpty())
    }

    @Test
    fun `unknown pack lookups are null`() {
        val manifest = LanguagePackManifest(manifestVersion = 1, packs = emptyList())
        assertEquals(null, manifest.find("missing"))
        assertEquals(null, manifest.findByLanguageCode("xx-IN"))
    }
}
