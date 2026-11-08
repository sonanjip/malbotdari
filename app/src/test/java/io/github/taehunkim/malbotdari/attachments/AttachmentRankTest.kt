package io.github.taehunkim.malbotdari.attachments

import io.github.taehunkim.malbotdari.data.db.AttachmentChunkEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentRankTest {
    private fun vector(vararg v: Float) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(v) }.array()

    @Test fun matchingModelRanksSemanticResults() {
        val chunks = listOf(AttachmentChunkEntity(attachmentId = 1, ordinal = 0, text = "one", embedding = vector(0f, 1f), modelId = "m:1"),
            AttachmentChunkEntity(attachmentId = 1, ordinal = 1, text = "two", embedding = vector(1f, 0f), modelId = "m:1"))
        assertEquals(1, AttachmentTool.rank(chunks, "question", floatArrayOf(1f, 0f), "m:1").first().ordinal)
    }

    @Test fun changedModelFallsBackToKeywords() {
        val chunks = listOf(AttachmentChunkEntity(attachmentId = 1, ordinal = 0, text = "other", embedding = vector(1f), modelId = "old:1"),
            AttachmentChunkEntity(attachmentId = 1, ordinal = 1, text = "budget", embedding = vector(0f), modelId = "old:1"))
        assertEquals(listOf(1), AttachmentTool.rank(chunks, "budget", floatArrayOf(1f), "new:1").map { it.ordinal })
    }

    @Test fun missingModelUsesKeywordMatches() {
        val chunks = listOf(AttachmentChunkEntity(attachmentId = 1, ordinal = 0, text = "other"), AttachmentChunkEntity(attachmentId = 1, ordinal = 1, text = "annual budget"))
        assertEquals(listOf(1), AttachmentTool.rank(chunks, "budget", null, null).map { it.ordinal })
    }
}
