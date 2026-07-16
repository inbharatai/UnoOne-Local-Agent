package com.unoone.agent.core.document

/**
 * C8: the result of loading + text-extracting a user-picked document (PDF / DOCX / image / Excel /
 * HTML / text / CSV). Carries the plain-text body the on-device brain reads as context, plus enough metadata
 * for the UI to describe what was loaded. [truncated] is true when the extractor capped the body to
 * fit the LLM context window — surfaced honestly to the user instead of silently dropping content.
 */
data class ExtractedDoc(
    val name: String,
    val text: String,
    val kind: DocKind,
    val pagesOrSheets: Int = 0,
    val truncated: Boolean = false
)

/** The kind of document loaded, used for narration ("a PDF", "a spreadsheet"). */
enum class DocKind { TEXT, HTML, CSV, XLSX, DOCX, IMAGE, PDF, UNSUPPORTED }
