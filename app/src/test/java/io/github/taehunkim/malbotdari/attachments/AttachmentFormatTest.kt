package io.github.taehunkim.malbotdari.attachments

import org.junit.Assert.*
import org.junit.Test

class AttachmentFormatTest {
    @Test fun standardOfficeMimeWorksWithoutFileExtension() {
        assertEquals("docx", AttachmentFormat.extension("Document", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("docx", AttachmentFormat.extension("Document", "APPLICATION/VND.OPENXMLFORMATS-OFFICEDOCUMENT.WORDPROCESSINGML.DOCUMENT; charset=utf-8"))
        assertEquals("pptx", AttachmentFormat.extension("Slides", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertEquals("xlsx", AttachmentFormat.extension("Sheet", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals("odt", AttachmentFormat.extension("Document", "application/vnd.oasis.opendocument.text"))
        assertEquals("odp", AttachmentFormat.extension("Slides", "application/vnd.oasis.opendocument.presentation"))
        assertEquals("ods", AttachmentFormat.extension("Sheet", "application/vnd.oasis.opendocument.spreadsheet"))
    }

    @Test fun filenameFallbackHandlesGenericMimeAndUnknownFormatsRemainUnsupported() {
        assertEquals("docx", AttachmentFormat.extension("report.DOCX", "application/octet-stream"))
        assertTrue(AttachmentFormat.supported("report.DOCX", "application/octet-stream"))
        assertFalse(AttachmentFormat.supported("archive.zip", "application/zip"))
    }
}
