package io.github.taehunkim.malbotdari.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.taehunkim.malbotdari.ui.common.inlineMarkdown
import io.github.taehunkim.malbotdari.ui.common.parseMarkdown
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun inline(md: String) = inlineMarkdown(parseMarkdown(md).firstChild as Paragraph, Color.Gray, Color.Blue)

    @Test fun plainTextKeepsItsContent() = assertEquals("hello world", inline("hello world").text)

    @Test fun boldBecomesASpanAndMarkersDisappear() {
        val s = inline("a **b** c")
        assertEquals("a b c", s.text)
        assertTrue(s.spanStyles.any { it.item.fontWeight == FontWeight.Bold && s.text.substring(it.start, it.end) == "b" })
    }

    @Test fun inlineCodeIsMonospace() {
        val s = inline("use `x`")
        assertEquals("use x", s.text)
        assertTrue(s.spanStyles.any { it.item.fontFamily != null })
    }

    @Test fun linkKeepsItsLabelAndUrl() {
        val s = inline("[site](https://example.com)")
        assertEquals("site", s.text)
        assertEquals(1, s.getLinkAnnotations(0, s.length).size)
    }

    @Test fun unbalancedMarkersDoNotThrow() = assertEquals("**a", inline("**a").text)
}
