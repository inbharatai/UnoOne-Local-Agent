package com.unoone.agent.core.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocxTemplateProcessorTest {

    @Test
    fun inspectsAndFillsSplitPlaceholdersAndContentControlsExactly() {
        val source = templateDocx()
        val sourceCopy = source.copyOf()

        val fields = DocxTemplateProcessor.inspect(source)
        assertEquals(listOf("city", "email", "full_name"), fields.map { it.id }.sorted())

        val result = DocxTemplateProcessor.fill(
            source,
            mapOf("full_name" to "Reetu Sharma", "email" to "reetu@example.com", "city" to "Delhi")
        )

        assertArrayEquals("The original template bytes must remain unchanged", sourceCopy, source)
        assertEquals(setOf("full_name", "email", "city"), result.replacedFieldIds)
        val text = DocxTemplateProcessor.extractText(result.bytes)
        assertTrue(text.contains("Applicant: Reetu Sharma"))
        assertTrue(text.contains("reetu@example.com"))
        assertTrue(text.contains("City: Delhi"))
        assertFalse(text.contains("{{"))
        assertEquals("unchanged-image", unzip(result.bytes).getValue("word/media/image1.txt").toString(Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFieldInsteadOfPretendingItWasFilled() {
        DocxTemplateProcessor.fill(templateDocx(), mapOf("not_a_real_field" to "value"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonDocxZip() {
        DocxTemplateProcessor.inspect(zip(mapOf("hello.txt" to "world".toByteArray())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDocxXmlWithExternalEntityDeclaration() {
        val malicious = zip(
            linkedMapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to """
                    <?xml version="1.0"?>
                    <!DOCTYPE w:document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
                    </w:document>
                """.trimIndent().toByteArray()
            )
        )

        DocxTemplateProcessor.inspect(malicious)
    }

    private fun templateDocx(): ByteArray = zip(
        linkedMapOf(
            "[Content_Types].xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>
            """.trimIndent().toByteArray(),
            "word/document.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Applicant: {{full_</w:t></w:r><w:r><w:t>name}}</w:t></w:r></w:p>
                    <w:p><w:sdt><w:sdtPr><w:tag w:val="email"/><w:alias w:val="Email address"/></w:sdtPr><w:sdtContent><w:r><w:t>old@example.com</w:t></w:r></w:sdtContent></w:sdt></w:p>
                    <w:p><w:r><w:t>City: &lt;&lt;city&gt;&gt;</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent().toByteArray(),
            "word/media/image1.txt" to "unchanged-image".toByteArray()
        )
    )

    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return parts
    }
}
