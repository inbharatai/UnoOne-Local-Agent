# Offline Document Skills

UnoOne can inspect and fill supported PDF and DOCX templates entirely on the phone. No document text or filled value is sent to a server. The workflow is deliberately **save as a verified copy**: the source is read-only, the completed file is reopened, and success is reported only after the requested values persist.

## How to use it

From the Agent landing screen, tap **Fill PDF / DOCX Offline**, choose a supported document, review the detected fields, enter values, then tap **Save Verified Copy** and choose a new destination.

The same picker can be opened by voice or typed commands such as:

- “Fill a PDF form”
- “Complete a PDF form offline”
- “Fill a DOCX template”
- “Complete a Word template offline”

Two enabled built-in skills provide these command routes. They call the canonical `prepare_document_fill` tool through the normal agent pipeline; the actual values are entered in the local review card and are not stored as learned-skill data.

For reading or asking questions about a document instead, use **Load Document (PDF / DOCX / Excel / image / text)**. That workflow extracts text but does not alter the file.

## Supported PDF behavior

Supported PDFs must contain AcroForm fields. UnoOne discovers and fills:

- text fields;
- checkboxes;
- radio-button groups;
- list and combo-box choices.

UnoOne rejects encrypted PDFs, files without editable AcroForm fields, and unknown field identifiers. Digital-signature fields are not filled. A scanned or visually flat PDF can be read with OCR, but it cannot be safely rewritten as a form by this feature.

## Authoring a DOCX template

Use either Word content controls with a **Tag** or **Title**, or a named text placeholder:

```text
{{full_name}}
${email_address}
<<city>>
```

Field names may contain letters, digits, spaces, `_`, `.`, or `-`. UnoOne recognizes placeholders even when Word has split their text across multiple runs in one paragraph. It processes `word/document.xml`, headers and footers while preserving the other ZIP package parts.

This feature fills named template fields; it is not a general-purpose Word layout editor. Legacy `.doc`, macros, tracked-change decisions, digital signatures, encrypted documents and arbitrary edits without named fields are outside its current contract.

## Accuracy and safety contract

- Maximum input and expanded DOCX size: 48 MB, with stricter per-XML-part and ZIP-entry limits.
- DOCX XML parsing disables external entities and DTD access; ZIP paths and duplicates are validated.
- A requested field must exist. Unknown or missing fields fail the operation instead of being silently ignored.
- PDF values and checkbox state are read back from the saved bytes and compared with the request.
- DOCX output is reopened, parsed and checked for the requested values.
- Output is written only after in-memory verification succeeds.
- The source and output URI must differ, and the Android UI always launches a new-document destination picker.

These checks establish deterministic field persistence, not semantic truth. A user should still review dates, legal declarations, financial information and final submission in the destination app.

## Automated tests

The JVM suite covers DOCX content controls, all placeholder formats, run-split placeholders, unknown-field rejection, invalid packages, asset preservation and source immutability. Connected-device tests create real PDF and DOCX fixtures on Android, fill them through `DocumentFillEngine`, reopen them, compare exact values and confirm that the originals did not change.
