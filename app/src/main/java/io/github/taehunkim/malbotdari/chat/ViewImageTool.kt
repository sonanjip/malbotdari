package io.github.taehunkim.malbotdari.chat

import io.github.taehunkim.malbotdari.files.FileIndex
import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Images requested by the LLM are supplied to a second, image-bearing generation in the same turn. */
class ViewImageTool(private val files: FileIndex, private val vision: Boolean, private val capacity: Int = 2) : ChatTool {
    override val name = "view_image"
    override val description = "See a Nunsori image, a document preview or a representative video frame. Call before describing visual contents. At most two distinct images per question. This does not show a whole video or every document page."
    override val parameters = """{"type":"object","properties":{"item_id":{"type":"integer"}},"required":["item_id"]}"""
    val loaded = ArrayList<Pair<String, ByteArray>>()
    private val ids = HashSet<Long>()
    override fun execute(args: JSONObject): String = runBlocking {
        if (!vision) return@runBlocking "This model cannot see images. Use read_file for OCR or transcripts; tell the user if these are missing."
        val id = args.optLong("item_id", -1)
        if (id <= 0) return@runBlocking "Error: item_id must be positive."
        if (id in ids) return@runBlocking "Image already requested."
        if (loaded.size >= capacity) return@runBlocking "Only two images can be viewed per question. Ask about the remaining ones in another message."
        try {
            val bytes = files.thumbnail(id, 1024) ?: return@runBlocking "No image preview available for this item. Do not guess its visual contents."
            val label = files.items(listOf(id)).firstOrNull()?.name ?: "item_id=$id"
            loaded += "$label (item_id=$id)" to bytes
            ids += id
            "Image requested. It will be supplied before the final answer; do not invent its contents yet."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { "Could not load image: ${e.message}" }
    }
}
