package io.github.taehunkim.malbotdari.attachments

import io.github.taehunkim.malbotdari.data.db.AttachmentDao
import io.github.taehunkim.malbotdari.data.db.AttachmentChunkEntity
import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import io.github.taehunkim.ondolsaem.sdk.OndolSaemClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Tools are scoped to the current chat, so a guessed ID cannot expose a different conversation. */
class AttachmentTool(
    private val dao: AttachmentDao, private val host: OndolSaemClient, private val session: Long, private val search: Boolean,
    private val queryEmbedding: (suspend (String) -> Pair<FloatArray, String>?)? = null,
) : ChatTool {
    override val name = if (search) "search_in_attachment" else "read_attachment"
    override val description = if (search) "Search the extracted text of a local attachment using its attachment_id." else "Read a local attachment's extracted text by character offset and length."
    override val parameters = if (search) """{"type":"object","properties":{"attachment_id":{"type":"integer"},"query":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":5}},"required":["attachment_id","query"]}"""
        else """{"type":"object","properties":{"attachment_id":{"type":"integer"},"offset":{"type":"integer","minimum":0},"max_chars":{"type":"integer","minimum":1,"maximum":3000}},"required":["attachment_id"]}"""

    override fun execute(args: JSONObject): String = runBlocking {
        val id = args.optLong("attachment_id", -1)
        val item = dao.get(id, session) ?: return@runBlocking "Error: no such attachment in this chat."
        if (!item.sent) return@runBlocking "Error: this attachment has not been sent to the chat."
        if (item.state != "READY") return@runBlocking "Attachment is ${item.state}; text is not ready. ${item.error.orEmpty()}"
        val chunks = dao.chunks(id)
        if (!search) {
            val offset = args.optInt("offset", 0)
            val maxChars = args.optInt("max_chars", 1200)
            if (offset < 0 || maxChars < 1) return@runBlocking "Error: offset must be nonnegative and max_chars positive."
            val text = chunks.joinToString("") { it.text }
            val start = offset.coerceAtMost(text.length)
            val end = (start.toLong() + maxChars.coerceAtMost(3000)).coerceAtMost(text.length.toLong()).toInt()
            return@runBlocking JSONObject().put("attachment_id", id).put("name", item.name).put("offset", start)
                .put("next_offset", if (end < text.length) end else JSONObject.NULL).put("total_chars", text.length)
                .put("text", text.substring(start, end)).toString()
        }
        val query = args.optString("query").trim()
        if (query.isEmpty()) return@runBlocking "Error: query is required."
        val limit = args.optInt("limit", 5)
        if (limit !in 1..5) return@runBlocking "Error: limit must be between 1 and 5."
        val embedding = try {
            if (queryEmbedding != null) queryEmbedding.invoke(query) else
                host.acquire(OndolSaem.Categories.EMBEDDING).use {
                    host.embedQuery(query).let { it.vector to "${it.modelId}:${it.modelVersion}" }
                }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val matches = rank(chunks, query, embedding?.first, embedding?.second).take(limit)
        val offsets = chunkOffsets(chunks)
        JSONObject().put("attachment_id", id).put("name", item.name).put("total_chunks", chunks.size)
            .put("matches", JSONArray(matches.map { JSONObject().put("ordinal", it.ordinal).put("offset", offsets[it.ordinal]).put("text", it.text.take(400)) })).toString()
    }

    companion object {
        fun chunkOffsets(chunks: List<AttachmentChunkEntity>): Map<Int, Int> {
            var offset = 0
            return chunks.associate { chunk -> chunk.ordinal to offset.also { offset += chunk.text.length } }
        }
        fun rank(chunks: List<AttachmentChunkEntity>, query: String, vector: FloatArray?, model: String?): List<AttachmentChunkEntity> {
            val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return chunks.map { chunk ->
                val blob = chunk.embedding
                val semantic = if (vector != null && blob != null && model == chunk.modelId && blob.size == vector.size * 4) {
                    val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    vector.indices.sumOf { (vector[it] * buf.get(it)).toDouble() }
                } else Double.NaN
                val keyword = words.count { chunk.text.contains(it, ignoreCase = true) }.toDouble()
                if (!semantic.isNaN()) chunk to semantic else if (keyword > 0) chunk to keyword else null
            }.filterNotNull().sortedByDescending { it.second }.map { it.first }
        }
    }
}
