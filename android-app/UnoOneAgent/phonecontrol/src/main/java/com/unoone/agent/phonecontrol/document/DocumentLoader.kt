package com.unoone.agent.phonecontrol.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.unoone.agent.core.document.DocKind
import com.unoone.agent.core.document.DocxTemplateProcessor
import com.unoone.agent.core.document.ExtractedDoc
import com.unoone.agent.core.document.HtmlTextExtractor
import com.unoone.agent.core.document.PlainTextExtractor
import com.unoone.agent.core.document.XlsxTextExtractor
import com.unoone.agent.core.model.Result
import com.unoone.agent.phonecontrol.OcrControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * C8: real document loading + text extraction for the "load a PDF/page/Excel/form for the AI to read
 * and work on" capability. No heavy new native libraries:
 *  - PDF: platform [PdfRenderer] renders each page to a bitmap, then the bundled ML Kit recognizer
 *    ([OcrControl.recognizeText]) reads it — works for any PDF, scanned or text-layer, fully offline.
 *  - Image: decode + ML Kit OCR (same path).
 *  - .xlsx / HTML / CSV / plain text: pure-JVM extractors in `core.document` (JVM-tested).
 *
 * Legacy binary .xls is honestly reported unsupported rather than faked. Output is capped to fit the
 * brain's context window; [ExtractedDoc.truncated] says so out loud. The whole load runs on
 * Dispatchers.IO — PdfRenderer + OCR are blocking.
 */
class DocumentLoader(private val context: Context) {

    private val ocrControl: OcrControl by lazy { OcrControl(context) }

    companion object {
        /** Maximum PDF pages OCR'd per load (bounded RAM + time on-device). */
        private const val MAX_PDF_PAGES = 8
        /** Render PDF pages to ~this width for OCR (smaller = faster, bounded bitmap memory). */
        private const val PDF_RENDER_TARGET_WIDTH = 1100
    }

    suspend fun load(uri: Uri, mimeType: String?, displayName: String? = null): Result<ExtractedDoc> {
        return try {
            val name = displayName ?: queryDisplayName(uri) ?: "document"
            val kind = classify(mimeType, name)
            val resolver = context.contentResolver
            val (text, pages, truncated) = when (kind) {
                DocKind.PDF -> {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        extractPdf(pfd)
                    } ?: throw java.io.IOException("Could not open the PDF for reading")
                }
                DocKind.IMAGE -> {
                    resolver.openInputStream(uri)?.use { extractImage(it) }
                        ?: throw java.io.IOException("Could not open the image")
                }
                DocKind.XLSX -> {
                    resolver.openInputStream(uri)?.use { Triple(XlsxTextExtractor.extract(it), 0, false) }
                        ?: throw java.io.IOException("Could not open the spreadsheet")
                }
                DocKind.DOCX -> {
                    resolver.openInputStream(uri)?.use { stream ->
                        Triple(DocxTemplateProcessor.extractText(stream.readBytes()), 0, false)
                    } ?: throw java.io.IOException("Could not open the DOCX file")
                }
                DocKind.HTML -> {
                    resolver.openInputStream(uri)?.use { stream ->
                        Triple(HtmlTextExtractor.extract(stream.readBytes().toString(Charsets.UTF_8)), 0, false)
                    } ?: throw java.io.IOException("Could not open the HTML file")
                }
                DocKind.CSV, DocKind.TEXT -> {
                    resolver.openInputStream(uri)?.use { Triple(PlainTextExtractor.extract(it), 0, false) }
                        ?: throw java.io.IOException("Could not open the text file")
                }
                DocKind.UNSUPPORTED -> throw UnsupportedOperationException(
                    "Unsupported file type. Use PDF, DOCX, image, Excel (.xlsx), HTML, CSV, or plain text."
                )
            }
            Result.Success(ExtractedDoc(name = name, text = text, kind = kind, pagesOrSheets = pages, truncated = truncated))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to read the document", e)
        }
    }

    /** Releases the OCR recognizer. Call when the loader is no longer needed (e.g. ViewModel onCleared). */
    fun release() {
        runCatching { ocrControl.release() }
    }

    private suspend fun extractPdf(pfd: ParcelFileDescriptor): Triple<String, Int, Boolean> =
        withContext(Dispatchers.IO) {
            val renderer = PdfRenderer(pfd)
            try {
                val sb = StringBuilder()
                var pages = 0
                val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (i in 0 until count) {
                    if (sb.length >= PlainTextExtractor.DEFAULT_MAX_CHARS) break
                    renderer.openPage(i).use { page ->
                        val scale = (PDF_RENDER_TARGET_WIDTH.toFloat() / page.width).coerceAtMost(1f)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val r = ocrControl.recognizeText(bmp)
                        bmp.recycle()
                        val t = (r as? Result.Success)?.data?.trim() ?: ""
                        if (t.isNotBlank()) sb.append(t).append("\n\n")
                        pages++
                    }
                }
                val truncated = sb.length >= PlainTextExtractor.DEFAULT_MAX_CHARS || count < renderer.pageCount
                Triple(PlainTextExtractor.cap(sb.toString(), PlainTextExtractor.DEFAULT_MAX_CHARS), pages, truncated)
            } finally {
                renderer.close()
            }
        }

    private suspend fun extractImage(stream: java.io.InputStream): Triple<String, Int, Boolean> =
        withContext(Dispatchers.IO) {
            val bmp = BitmapFactory.decodeStream(stream)
            if (bmp == null) {
                Triple("", 0, false)
            } else {
                val r = ocrControl.recognizeText(bmp)
                bmp.recycle()
                val t = (r as? Result.Success)?.data?.trim() ?: ""
                Triple(t, 0, false)
            }
        }

    private fun classify(mimeType: String?, name: String): DocKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mimeType == "application/pdf" || ext == "pdf" -> DocKind.PDF
            mimeType?.startsWith("image/") == true ||
                ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "tiff") -> DocKind.IMAGE
            mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || ext == "xlsx" -> DocKind.XLSX
            mimeType == DocumentFillEngine.DOCX_MIME || ext == "docx" -> DocKind.DOCX
            mimeType == "text/html" || ext == "html" || ext == "htm" -> DocKind.HTML
            mimeType == "text/csv" || ext == "csv" -> DocKind.CSV
            mimeType?.startsWith("text/") == true || ext in setOf("txt", "md", "log", "json", "xml") -> DocKind.TEXT
            else -> DocKind.UNSUPPORTED
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
