package io.github.taehunkim.malbotdari.sharing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.attachments.AttachmentFormat
import io.github.taehunkim.malbotdari.attachments.extract.OfficeParsers
import io.github.taehunkim.malbotdari.attachments.extract.ZipSource
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficeMimeParsingTest {
    @Test fun extensionlessDocxMimeSelectsTheOfficeParser() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Office content</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertTrue(AttachmentFormat.supported("untitled", mime))
        ZipSource.fromBytes(out.toByteArray()).use { zip ->
            val doc = OfficeParsers.parse(AttachmentFormat.extension("untitled", mime), zip)
            assertEquals("Office content", doc!!.units.single().text)
        }
    }
}
