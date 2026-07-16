package com.unoone.agent.phonecontrol

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.unoone.agent.core.document.DocxTemplateProcessor
import com.unoone.agent.core.model.Result
import com.unoone.agent.phonecontrol.document.DocumentFieldType
import com.unoone.agent.phonecontrol.document.DocumentFillEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentFillEngineDeviceTest {

    @Test
    fun pdfAcroFormRoundTripPersistsExactTextAndCheckboxWithoutChangingOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "unoone-fill-source.pdf")
        val output = File(context.cacheDir, "unoone-fill-output.pdf")
        createPdfFixture(source)
        val original = source.readBytes()
        val engine = DocumentFillEngine(context)

        val inspected = engine.inspect(Uri.fromFile(source), DocumentFillEngine.PDF_MIME, source.name)
        assertTrue(inspected is Result.Success)
        val template = (inspected as Result.Success).data
        assertEquals(listOf("full_name", "newsletter"), template.fields.map { it.id }.sorted())
        assertEquals(DocumentFieldType.BOOLEAN, template.fields.first { it.id == "newsletter" }.type)

        val filled = engine.fillCopy(
            Uri.fromFile(source),
            Uri.fromFile(output),
            template,
            mapOf("full_name" to "Reetu Sharma", "newsletter" to "yes")
        )
        assertTrue((filled as? Result.Error)?.message, filled is Result.Success)
        assertArrayEquals("Original PDF must never be overwritten", original, source.readBytes())

        val verified = engine.inspect(Uri.fromFile(output), DocumentFillEngine.PDF_MIME, output.name)
        assertTrue(verified is Result.Success)
        val values = (verified as Result.Success).data.fields.associateBy { it.id }
        assertEquals("Reetu Sharma", values.getValue("full_name").currentValue)
        assertEquals("true", values.getValue("newsletter").currentValue)
    }

    @Test
    fun docxTemplateRoundTripPersistsExactValuesWithoutChangingOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "unoone-fill-source.docx")
        val output = File(context.cacheDir, "unoone-fill-output.docx")
        createDocxFixture(source)
        val original = source.readBytes()
        val engine = DocumentFillEngine(context)

        val inspected = engine.inspect(Uri.fromFile(source), DocumentFillEngine.DOCX_MIME, source.name)
        val inspectionError = inspected as? Result.Error
        assertTrue(
            inspectionError?.let {
                val chain = generateSequence(it.cause) { cause -> cause.cause }
                    .joinToString(" -> ") { cause -> "${cause.javaClass.name}: ${cause.message}" }
                "${it.message}: $chain"
            },
            inspected is Result.Success
        )
        val template = (inspected as Result.Success).data
        assertEquals(listOf("city", "full_name"), template.fields.map { it.id }.sorted())

        val filled = engine.fillCopy(
            Uri.fromFile(source),
            Uri.fromFile(output),
            template,
            mapOf("full_name" to "Reetu Sharma", "city" to "Bengaluru")
        )
        assertTrue((filled as? Result.Error)?.message, filled is Result.Success)
        assertArrayEquals("Original DOCX must never be overwritten", original, source.readBytes())

        val text = DocxTemplateProcessor.extractText(output.readBytes())
        assertTrue(text.contains("Name: Reetu Sharma"))
        assertTrue(text.contains("City: Bengaluru"))
        assertTrue(!text.contains("{{"))
    }

    private fun createPdfFixture(file: File) {
        PDDocument().use { document ->
            val pdfPage = PDPage()
            document.addPage(pdfPage)
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            val resources = PDResources()
            resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            form.defaultResources = resources
            form.defaultAppearance = "/Helv 11 Tf 0 g"

            val name = PDTextField(form).apply {
                partialName = "full_name"
                alternateFieldName = "Full name"
            }
            name.widgets.first().apply {
                rectangle = PDRectangle(40f, 700f, 250f, 24f)
                page = pdfPage
            }
            pdfPage.annotations.add(name.widgets.first())
            form.fields.add(name)

            val checkbox = PDCheckBox(form).apply {
                partialName = "newsletter"
                alternateFieldName = "Subscribe to newsletter"
            }
            checkbox.widgets.first().apply {
                rectangle = PDRectangle(40f, 650f, 24f, 24f)
                page = pdfPage
                val normal = COSDictionary()
                normal.setItem(COSName.Off, PDAppearanceStream(document).cosObject)
                normal.setItem(COSName.getPDFName("Yes"), PDAppearanceStream(document).cosObject)
                val appearances = COSDictionary()
                appearances.setItem(COSName.N, normal)
                cosObject.setItem(COSName.AP, appearances)
            }
            pdfPage.annotations.add(checkbox.widgets.first())
            form.fields.add(checkbox)

            document.save(file)
        }
    }

    private fun createDocxFixture(file: File) {
        val parts = linkedMapOf(
            "[Content_Types].xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
            """.trimIndent(),
            "_rels/.rels" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
            """.trimIndent(),
            "word/document.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Name: {{full_name}}</w:t></w:r></w:p>
                    <w:p><w:r><w:t>City: &lt;&lt;city&gt;&gt;</w:t></w:r></w:p>
                    <w:sectPr/>
                  </w:body>
                </w:document>
            """.trimIndent()
        )
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            parts.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
