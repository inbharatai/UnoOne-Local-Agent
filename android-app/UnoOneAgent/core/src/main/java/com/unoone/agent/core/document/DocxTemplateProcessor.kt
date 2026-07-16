package com.unoone.agent.core.document

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.SAXException

data class DocxTemplateField(
    val id: String,
    val label: String,
    val currentValue: String = ""
)

data class DocxFillResult(
    val bytes: ByteArray,
    val replacedFieldIds: Set<String>
)

/**
 * Dependency-free DOCX template reader/filler used by the Android Document Agent.
 *
 * Supported fields:
 *  - Word content controls whose `w:tag` or `w:alias` supplies the field name;
 *  - placeholders written as `{{name}}`, `${name}` or `<<name>>`;
 *  - placeholders split across multiple Word runs in the same paragraph.
 *
 * Only document/header/footer XML is changed. Images, relationships, styles and every other ZIP
 * part are copied byte-for-byte. Input limits and XXE protections make untrusted picked documents
 * bounded and keep parsing fully offline.
 */
object DocxTemplateProcessor {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_UNCOMPRESSED_BYTES = 48L * 1024L * 1024L
    private const val MAX_XML_PART_BYTES = 8 * 1024 * 1024
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
    private const val ACCESS_EXTERNAL_STYLESHEET = "http://javax.xml.XMLConstants/property/accessExternalStylesheet"
    private val editablePart = Regex("word/(?:document|header\\d+|footer\\d+)\\.xml")
    private val placeholder = Regex(
        // Escape closing braces explicitly: the desktop JVM accepts bare `}` outside a
        // quantifier, while Android ART's regex engine rejects it.
        "\\{\\{\\s*([A-Za-z0-9_. -]{1,80})\\s*\\}\\}|" +
            "\\$\\{\\s*([A-Za-z0-9_. -]{1,80})\\s*\\}|" +
            "<<\\s*([A-Za-z0-9_. -]{1,80})\\s*>>"
    )

    fun inspect(docx: ByteArray): List<DocxTemplateField> {
        val parts = readParts(docx)
        val fields = linkedMapOf<String, DocxTemplateField>()
        parts.filterKeys(editablePart::matches).forEach { (_, bytes) ->
            val document = parseXml(bytes)
            contentControls(document).forEach { control ->
                val id = controlId(control) ?: return@forEach
                fields.putIfAbsent(normalize(id), DocxTemplateField(id.trim(), humanize(id), controlText(control)))
            }
            paragraphs(document).forEach { paragraph ->
                val text = textNodes(paragraph).joinToString("") { it.textContent.orEmpty() }
                placeholder.findAll(text).forEach { match ->
                    val id = match.groupValues.drop(1).first { it.isNotBlank() }.trim()
                    fields.putIfAbsent(normalize(id), DocxTemplateField(id, humanize(id)))
                }
            }
        }
        return fields.values.toList()
    }

    fun extractText(docx: ByteArray, maxChars: Int = PlainTextExtractor.DEFAULT_MAX_CHARS): String {
        val parts = readParts(docx)
        val output = StringBuilder()
        parts.filterKeys(editablePart::matches).forEach { (_, bytes) ->
            if (output.length >= maxChars) return@forEach
            val document = parseXml(bytes)
            paragraphs(document).forEach { paragraph ->
                if (output.length >= maxChars) return@forEach
                val text = textNodes(paragraph).joinToString("") { it.textContent.orEmpty() }.trim()
                if (text.isNotBlank()) output.append(text).append('\n')
            }
        }
        return PlainTextExtractor.cap(output.toString().trim(), maxChars)
    }

    fun fill(docx: ByteArray, values: Map<String, String>): DocxFillResult {
        require(values.isNotEmpty()) { "Enter at least one document field value" }
        val parts = readParts(docx)
        val canonicalValues = values.entries.associate { normalize(it.key) to it.value }
        val known = inspect(docx).associateBy { normalize(it.id) }
        val unknown = canonicalValues.keys - known.keys
        require(unknown.isEmpty()) { "Unknown DOCX field(s): ${unknown.sorted().joinToString()}" }

        val replaced = linkedSetOf<String>()
        val outputParts = LinkedHashMap(parts)
        parts.filterKeys(editablePart::matches).forEach { (name, bytes) ->
            val document = parseXml(bytes)
            var changed = false

            contentControls(document).forEach { control ->
                val id = controlId(control) ?: return@forEach
                val value = canonicalValues[normalize(id)] ?: return@forEach
                val content = descendants(control, "sdtContent").firstOrNull() ?: return@forEach
                val nodes = textNodes(content)
                if (nodes.isNotEmpty()) {
                    setCombinedText(nodes, value)
                    replaced += known.getValue(normalize(id)).id
                    changed = true
                }
            }

            paragraphs(document).forEach { paragraph ->
                val nodes = textNodes(paragraph)
                if (nodes.isEmpty()) return@forEach
                val original = nodes.joinToString("") { it.textContent.orEmpty() }
                var paragraphChanged = false
                val updated = placeholder.replace(original) { match ->
                    val id = match.groupValues.drop(1).first { it.isNotBlank() }.trim()
                    canonicalValues[normalize(id)]?.also {
                        replaced += known.getValue(normalize(id)).id
                        paragraphChanged = true
                    } ?: match.value
                }
                if (paragraphChanged) {
                    setCombinedText(nodes, updated)
                    changed = true
                }
            }

            if (changed) outputParts[name] = serialize(document)
        }
        val requested = canonicalValues.keys.mapNotNull { known[it]?.id }.toSet()
        require(replaced.containsAll(requested)) {
            "Some DOCX fields could not be written: ${(requested - replaced).sorted().joinToString()}"
        }
        return DocxFillResult(writeParts(outputParts), replaced)
    }

    private fun readParts(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        require(bytes.isNotEmpty()) { "The DOCX is empty" }
        val parts = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(parts.size < MAX_ENTRIES) { "DOCX contains too many ZIP entries" }
                val name = entry.name
                require(!name.startsWith("/") && !name.split('/').contains("..")) { "Unsafe DOCX ZIP path" }
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_UNCOMPRESSED_BYTES) { "DOCX expands beyond the 48 MB safety limit" }
                        if (editablePart.matches(name)) {
                            require(out.size() + count <= MAX_XML_PART_BYTES) { "DOCX XML part is too large" }
                        }
                        out.write(buffer, 0, count)
                    }
                    require(parts.put(name, out.toByteArray()) == null) { "DOCX contains duplicate ZIP entries" }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require("[Content_Types].xml" in parts && "word/document.xml" in parts) { "Not a valid DOCX package" }
        return LinkedHashMap(parts)
    }

    private fun writeParts(parts: LinkedHashMap<String, ByteArray>): ByteArray {
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

    private fun parseXml(bytes: ByteArray): Document {
        // Android's built-in XML parser does not implement Xerces' disallow-doctype feature.
        // Reject declarations before parsing (NUL stripping also catches UTF-16/32 ASCII markup),
        // then apply every parser-level defense that the current runtime supports.
        val markupProbe = buildString(bytes.size.coerceAtMost(MAX_XML_PART_BYTES)) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                if (value != 0) append(value.toChar())
            }
        }
        require(!markupProbe.contains("<!DOCTYPE", ignoreCase = true)) { "DOCX XML must not contain a DOCTYPE" }
        require(!markupProbe.contains("<!ENTITY", ignoreCase = true)) { "DOCX XML must not declare entities" }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are disabled for DOCX") }
        }.parse(ByteArrayInputStream(bytes))
    }

    private fun serialize(document: Document): ByteArray {
        val output = ByteArrayOutputStream()
        val factory = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_STYLESHEET, "") }
        }
        factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            transform(DOMSource(document), StreamResult(output))
        }
        return output.toByteArray()
    }

    private fun paragraphs(document: Document): List<Element> = descendants(document.documentElement, "p")
    private fun contentControls(document: Document): List<Element> = descendants(document.documentElement, "sdt")

    private fun descendants(root: Node, localName: String): List<Element> {
        val nodes = if (root is Document) root.getElementsByTagNameNS("*", localName)
        else (root as Element).getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun textNodes(root: Node): List<Element> = descendants(root, "t")

    private fun controlId(control: Element): String? {
        val properties = descendants(control, "sdtPr").firstOrNull() ?: return null
        for (name in listOf("tag", "alias")) {
            val element = descendants(properties, name).firstOrNull() ?: continue
            val value = element.getAttributeNS(WORD_NS, "val").ifBlank { element.getAttribute("w:val") }
            if (value.isNotBlank()) return value.trim()
        }
        return null
    }

    private fun controlText(control: Element): String =
        descendants(control, "sdtContent").firstOrNull()?.let(::textNodes)
            ?.joinToString("") { it.textContent.orEmpty() }?.trim().orEmpty()

    private fun setCombinedText(nodes: List<Element>, value: String) {
        nodes.first().textContent = value
        nodes.first().setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
        nodes.drop(1).forEach { it.textContent = "" }
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun humanize(value: String): String = value.trim()
        .replace(Regex("[_ .-]+"), " ")
        .split(' ').filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
