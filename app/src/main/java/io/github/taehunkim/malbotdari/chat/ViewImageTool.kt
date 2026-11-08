package io.github.taehunkim.malbotdari.chat

import io.github.taehunkim.malbotdari.files.FileIndex
import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Images requested by the LLM are supplied to a second, image-bearing generation in the same turn. */
class ViewImageTool(
    private val files: FileIndex, private val vision: Boolean, private val capacity: Int = 2,
    private val localImages: Set<String> = emptySet(),
    private val loadLocal: suspend (String) -> ByteArray? = { null },
) : ChatTool {
    override val name = "view_image"
    override val description = "See a local chat image by attachment_id, or a Nunsori image, document preview or representative video frame by item_id. Call before describing visual contents. At most two distinct images per question. This does not show a whole video or every document page."
    override val parameters = """{"type":"object","properties":{"item_id":{"type":"integer"},"attachment_id":{"type":"string"}},"oneOf":[{"required":["item_id"]},{"required":["attachment_id"]}]}"""
    val loaded = ArrayList<Pair<String, ByteArray>>()
    private val ids = HashSet<String>()
    override fun execute(args: JSONObject): String = runBlocking {
        val hasItem = args.has("item_id")
        val hasLocal = args.has("attachment_id")
        if (hasItem == hasLocal) return@runBlocking "Error: supply exactly one of item_id or attachment_id."
        val localId = if (hasLocal && !args.isNull("attachment_id")) args.optString("attachment_id", "").trim() else ""
        val id = if (hasItem && !args.isNull("item_id")) args.optLong("item_id", -1) else -1
        if ((hasLocal && localId.isEmpty()) || (hasItem && id <= 0)) return@runBlocking "Error: the selected id must be valid."
        if (!vision) return@runBlocking "This model cannot see images. Use read_attachment for local image OCR or read_file for indexed OCR."
        val key = if (localId.isNotEmpty()) "local:$localId" else "item:$id"
        if (key in ids) return@runBlocking "Image already requested."
        if (loaded.size >= capacity) return@runBlocking "Only two images can be viewed per question. Ask about the remaining ones in another message."
        try {
            if (localId.isNotEmpty()) {
                if (localId !in localImages) return@runBlocking "No such image in this chat."
                val bytes = loadLocal(localId) ?: return@runBlocking "No image preview available."
                loaded += "attachment_id=$localId" to bytes
                ids += key
                return@runBlocking "Image requested. It will be supplied before the final answer; do not invent its contents yet."
            }
            val bytes = files.thumbnail(id, 1024) ?: return@runBlocking "No image preview available for this item. Do not guess its visual contents."
            val label = files.items(listOf(id)).firstOrNull()?.name ?: "item_id=$id"
            loaded += "$label (item_id=$id)" to bytes
            ids += key
            "Image requested. It will be supplied before the final answer; do not invent its contents yet."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { "Could not load image: ${e.message}" }
    }
}
