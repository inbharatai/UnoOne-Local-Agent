package com.unoone.agent.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxTextExtractorTest {

    /**
     * Builds a minimal but valid .xlsx (OOXML zip) in memory: a shared-strings table + one worksheet
     * with two rows mixing shared-string cells and numeric cells. This is the real on-disk shape an
     * Excel export produces — not a stub — so the test exercises the actual SAX path.
     */
    private fun buildXlsx(): ByteArrayInputStream {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "xl/sharedStrings.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
<si><t>Name</t></si>
<si><t>UnoOne</t></si>
</sst>""".trimIndent()
            )
            put(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>42</v></c></row>
<row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>7</v></c></row>
</sheetData>
</worksheet>""".trimIndent()
            )
        }
        return ByteArrayInputStream(baos.toByteArray())
    }

    @Test
    fun extractsSharedStringAndNumericCellsByRow() {
        val text = XlsxTextExtractor.extract(buildXlsx())

        // Shared-string indices (0 -> "Name", 1 -> "UnoOne") resolved, numerics kept literal.
        assertTrue("shared string 0", text.contains("Name"))
        assertTrue("shared string 1", text.contains("UnoOne"))
        assertTrue("numeric B1", text.contains("42"))
        assertTrue("numeric B2", text.contains("7"))

        // Row 1 cells are tab-separated on one line; row 2 on the next.
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue("row 1 order", lines[0].contains("Name") && lines[0].contains("42") && lines[0].indexOf("Name") < lines[0].indexOf("42"))
        assertTrue("row 2 order", lines[1].contains("UnoOne") && lines[1].contains("7"))
        assertFalse("no raw xml tags leak", text.contains("<"))
    }

    @Test
    fun returnsEmptyForZipWithNoSheets() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>".toByteArray())
            zip.closeEntry()
        }
        val text = XlsxTextExtractor.extract(ByteArrayInputStream(baos.toByteArray()))
        assertEquals("", text)
    }

    @Test
    fun capsLargeSheetOutput() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>".toByteArray())
            zip.closeEntry()
            val sb = StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            for (r in 1..5000) {
                sb.append("<row><c><v>").append(r).append("</v></c></row>")
            }
            sb.append("</sheetData></worksheet>")
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sb.toString().toByteArray())
            zip.closeEntry()
        }
        val text = XlsxTextExtractor.extract(ByteArrayInputStream(baos.toByteArray()), maxChars = 1000)
        assertEquals(1000, text.length)
    }
}