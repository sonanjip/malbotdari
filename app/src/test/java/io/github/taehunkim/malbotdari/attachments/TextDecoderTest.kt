package io.github.taehunkim.malbotdari.attachments

import io.github.taehunkim.malbotdari.attachments.extract.TextDecoder
import org.junit.Assert.*
import org.junit.Test

class TextDecoderTest {
    @Test fun decodesUtf16BomAndRejectsBinaryNuls() {
        val text = "안녕".toByteArray(Charsets.UTF_16LE)
        assertEquals("안녕", TextDecoder.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text))
        assertNull(TextDecoder.decode(byteArrayOf(1, 0, 2)))
    }
}
