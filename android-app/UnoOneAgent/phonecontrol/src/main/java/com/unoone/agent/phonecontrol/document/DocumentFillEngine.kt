package com.unoone.agent.phonecontrol.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.unoone.agent.core.document.DocxTemplateProcessor
import com.unoone.agent.core.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class DocumentFillKind { FILLABLE_PDF, DOCX_TEMPLATE }
enum class DocumentFieldType { TEXT, BOOLEAN, CHOICE }

data class DocumentFillField(
    val id: String,
    val label: String,
    val type: DocumentFieldType,
    val currentValue: String = "",
    val options: List<String> = emptyList()
)

data class EditableDocumentTemplate(
    val displayName: String,
    val mimeType: String,
    val kind: DocumentFillKind,
    val fields: List<DocumentFillField>
)

data class DocumentFillSummary(
    val displayName: String,
    val kind: DocumentFillKind,
    val fieldsWritten: Int,
    val outputBytes: Long
)

/** Fully offline, save-as-copy document filling for AcroForm PDFs and DOCX templates. */
class DocumentFillEngine(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    init {
        PDFBoxResourceLoader.init(appContext)
    }

    suspend fun inspect(uri: Uri, mimeType: String?, displayName: String? = null): Result<EditableDocumentTemplate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName ?: queryDisplayName(uri) ?: "document"
                val bytes = readBounded(uri)
                when (classify(mimeType, name)) {
                    DocumentFillKind.DOCX_TEMPLATE -> inspectDocx(bytes, name)
                    DocumentFillKind.FILLABLE_PDF -> inspectPdf(bytes, name)
                }
            }.fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Error(it.message ?: "Could not inspect the document", it) }
            )
        }

    suspend fun fillCopy(
        sourceUri: Uri,
        outputUri: Uri,
        template: EditableDocumentTemplate,
        values: Map<String, String>
    ): Result<DocumentFillSummary> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceUri != outputUri) { "Choose a new output file; UnoOne never overwrites the original" }
            val source = readBounded(sourceUri)
            val known = template.fields.associateBy { it.id }
            val requested = values.filter { (id, value) ->
                value.isNotBlank() || known[id]?.type == DocumentFieldType.BOOLEAN
            }
            require(requested.isNotEmpty()) { "Enter at least one field value" }
            require(requested.keys.all(known::containsKey)) { "The request contains a field not present in this document" }

            val output = when (template.kind) {
                DocumentFillKind.DOCX_TEMPLATE -> {
                    val result = DocxTemplateProcessor.fill(source, requested)
                    // Re-open and parse the produced ZIP/XML before it reaches storage.
                    val text = DocxTemplateProcessor.extractText(result.bytes)
                    require(requested.values.filter { it.isNotBlank() }.all(text::contains)) {
                        "DOCX verification failed after filling"
                    }
                    result.bytes
                }
                DocumentFillKind.FILLABLE_PDF -> fillPdf(source, requested)
            }

            resolver.openOutputStream(outputUri, "w")?.use { it.write(output) }
                ?: error("Could not open the selected output file")
            DocumentFillSummary(
                displayName = queryDisplayName(outputUri) ?: completedName(template.displayName),
                kind = template.kind,
                fieldsWritten = requested.size,
                outputBytes = output.size.toLong()
            )
        }.fold(
            onSuccess = { Result.Success(it) },
            onFailure = { Result.Error(it.message ?: "Could not fill the document", it) }
        )
    }

    private fun inspectDocx(bytes: ByteArray, name: String): EditableDocumentTemplate {
        val fields = DocxTemplateProcessor.inspect(bytes).map {
            DocumentFillField(it.id, it.label, DocumentFieldType.TEXT, it.currentValue)
        }
        require(fields.isNotEmpty()) {
            "No DOCX template fields found. Use Word content controls or placeholders such as {{name}}."
        }
        return EditableDocumentTemplate(
            displayName = name,
            mimeType = DOCX_MIME,
            kind = DocumentFillKind.DOCX_TEMPLATE,
            fields = fields
        )
    }

    private fun inspectPdf(bytes: ByteArray, name: String): EditableDocumentTemplate {
        val fields = readPdfFields(bytes)
        require(fields.isNotEmpty()) { "This PDF has no editable AcroForm fields. Scanned and flat PDFs are read-only." }
        return EditableDocumentTemplate(
            displayName = name,
            mimeType = PDF_MIME,
            kind = DocumentFillKind.FILLABLE_PDF,
            fields = fields
        )
    }

    private fun readPdfFields(bytes: ByteArray): List<DocumentFillField> =
        PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            require(!document.isEncrypted) { "Password-protected PDFs are not supported" }
            val form = document.documentCatalog.acroForm ?: return@use emptyList()
            val output = mutableListOf<DocumentFillField>()
            val iterator = form.fieldTree.iterator()
            while (iterator.hasNext()) {
                val field = iterator.next()
                if (field is PDSignatureField || field is PDNonTerminalField) continue
                val id = field.fullyQualifiedName?.takeIf { it.isNotBlank() } ?: continue
                val label = field.alternateFieldName?.takeIf { it.isNotBlank() } ?: humanize(id)
                val model = when (field) {
                    is PDCheckBox -> DocumentFillField(id, label, DocumentFieldType.BOOLEAN, field.isChecked.toString())
                    is PDRadioButton -> DocumentFillField(
                        id, label, DocumentFieldType.CHOICE, field.valueAsString.orEmpty(), field.exportValues.orEmpty()
                    )
                    is PDChoice -> DocumentFillField(
                        id, label, DocumentFieldType.CHOICE, field.valueAsString.orEmpty(),
                        // Store export values because PDF setValue() and round-trip verification
                        // operate on those exact values. Display labels can differ from exports.
                        (field.optionsExportValues ?: field.optionsDisplayValues).orEmpty()
                    )
                    else -> DocumentFillField(id, label, DocumentFieldType.TEXT, field.valueAsString.orEmpty())
                }
                output += model
            }
            output
        }

    private fun fillPdf(source: ByteArray, values: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument.load(ByteArrayInputStream(source)).use { document ->
            require(!document.isEncrypted) { "Password-protected PDFs are not supported" }
            val form = document.documentCatalog.acroForm ?: error("This PDF has no AcroForm")
            form.needAppearances = true
            values.forEach { (id, value) ->
                val field: PDField = form.getField(id) ?: error("PDF field '$id' no longer exists")
                when (field) {
                    is PDCheckBox -> if (isTruthy(value)) field.check() else field.unCheck()
                    is PDRadioButton -> field.setValue(value)
                    is PDChoice -> field.setValue(listOf(value))
                    is PDSignatureField -> error("Digital-signature fields require manual signing")
                    else -> field.setValue(value)
                }
            }
            document.save(output)
        }
        val result = output.toByteArray()
        val verified = readPdfFields(result).associateBy { it.id }
        values.forEach { (id, expected) ->
            val actual = verified[id] ?: error("PDF verification lost field '$id'")
            if (actual.type == DocumentFieldType.BOOLEAN) {
                require(actual.currentValue.toBoolean() == isTruthy(expected)) { "PDF checkbox '$id' did not persist" }
            } else {
                require(actual.currentValue == expected) { "PDF field '$id' did not persist exactly" }
            }
        }
        return result
    }

    private fun readBounded(uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_INPUT_BYTES) { "Document exceeds the 48 MB offline limit" }
                out.write(buffer, 0, count)
            }
        } ?: error("Could not open the selected document")
        return out.toByteArray()
    }

    private fun classify(mimeType: String?, name: String): DocumentFillKind {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            mimeType == PDF_MIME || extension == "pdf" -> DocumentFillKind.FILLABLE_PDF
            mimeType == DOCX_MIME || extension == "docx" -> DocumentFillKind.DOCX_TEMPLATE
            else -> error("Choose a fillable PDF or DOCX template")
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun completedName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)}-completed${name.substring(dot)}" else "$name-completed"
    }

    private fun humanize(value: String): String = value.replace(Regex("[_.-]+"), " ")
        .trim().split(Regex("\\s+")).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun isTruthy(value: String): Boolean = value.trim().lowercase(Locale.ROOT) in
        setOf("true", "yes", "y", "1", "on", "checked")

    companion object {
        const val PDF_MIME = "application/pdf"
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MAX_INPUT_BYTES = 48 * 1024 * 1024
    }
}
