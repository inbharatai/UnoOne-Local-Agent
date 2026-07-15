package com.unoone.agent.core.document

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * C8: real .xlsx (Office Open XML) text extraction — no Apache POI. An .xlsx is a zip; we read
 * `xl/sharedStrings.xml` for the string table and each `xl/worksheets/sheetN.xml` for cell
 * references, resolving shared-string indices and literal values into a tab-separated, newline-per-row
 * plain-text representation the brain reads directly. Pure JDK SAX, JVM-testable. Legacy binary .xls
 * is intentionally unsupported (returned as [DocKind.UNSUPPORTED] by the loader, not faked).
 */
object XlsxTextExtractor {

    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private val SHEET = Regex("xl/worksheets/sheet\\d+\\.xml", RegexOption.IGNORE_CASE)

    fun extract(input: InputStream, maxChars: Int = PlainTextExtractor.DEFAULT_MAX_CHARS): String {
        // Read the zip entries once (ZipInputStream is a single forward pass). We materialize only
        // the two entry families we parse; everything else is skipped — bounded memory for large files.
        val shared = mutableListOf<String>()
        val sheetEntries = mutableListOf<Pair<String, ByteArray>>()

        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    when {
                        entry.name.equals(SHARED_STRINGS, ignoreCase = true) -> {
                            shared.addAll(parseSharedStrings(ByteArrayInputStream(zis.readBytes())))
                        }
                        SHEET.matches(entry.name) -> sheetEntries.add(entry.name to zis.readBytes())
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (sheetEntries.isEmpty()) return ""
        sheetEntries.sortBy { it.first }
        val sb = StringBuilder()
        for ((index, pair) in sheetEntries.withIndex()) {
            if (sb.length >= maxChars) break
            if (index > 0) sb.append("\n--- Sheet ").append(index + 1).append(" ---\n")
            sb.append(parseSheet(ByteArrayInputStream(pair.second), shared, maxChars - sb.length))
        }
        return PlainTextExtractor.cap(sb.toString(), maxChars)
    }

    /** Parses `xl/sharedStrings.xml`: concatenates `<t>` runs inside each `<si>` into one string. */
    private fun parseSharedStrings(stream: InputStream): List<String> {
        val out = mutableListOf<String>()
        val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()
        parser.parse(stream, object : DefaultHandler() {
            private val current = StringBuilder()
            private var inT = false
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
                when (qName) {
                    "si" -> current.clear()
                    "t" -> inT = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) current.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "t" -> inT = false
                    "si" -> out.add(current.toString())
                }
            }
        })
        return out
    }

    /** Parses a `xl/worksheets/sheetN.xml`: emits one line per row, tab-separated cells. */
    private fun parseSheet(stream: InputStream, shared: List<String>, maxChars: Int): String {
        val out = StringBuilder()
        val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()
        parser.parse(stream, object : DefaultHandler() {
            private var inValue = false
            private var cellType: String? = null
            private val value = StringBuilder()
            private var rowStarted = false

            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
                when (qName) {
                    "row" -> { if (rowStarted) out.append('\n'); rowStarted = true }
                    "c" -> { cellType = attrs?.getValue("t"); value.clear() }
                    "v", "t" -> inValue = true // <v> = stored value; <t> = inline-string value
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        if (out.length >= maxChars) return
                        val raw = value.toString()
                        val cell = when (cellType) {
                            "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                            "inlineStr", "str" -> raw
                            else -> raw // numeric / boolean / error: literal text
                        }
                        if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\t')
                        out.append(cell)
                        cellType = null
                    }
                }
            }
        })
        return out.toString()
    }
}