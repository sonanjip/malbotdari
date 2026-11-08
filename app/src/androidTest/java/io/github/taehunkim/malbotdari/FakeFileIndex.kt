package io.github.taehunkim.malbotdari

import io.github.taehunkim.malbotdari.files.FileIndex
import io.github.taehunkim.nunsori.sdk.Changes
import io.github.taehunkim.nunsori.sdk.CommitResult
import io.github.taehunkim.nunsori.sdk.Confirmation
import io.github.taehunkim.nunsori.sdk.FileRef
import io.github.taehunkim.nunsori.sdk.ItemInfo
import io.github.taehunkim.nunsori.sdk.NunsoriTool
import org.json.JSONArray
import org.json.JSONObject

/** Kinds as the tests name them (Nunsori's item kinds); the index reports them by Nunsori's names. */
enum class ItemKind(val apiName: String) { TEXT("document"), DOC("document"), IMAGE("image"), AUDIO("audio"), VIDEO("video") }
enum class SegmentType { TEXT_CHUNK, OCR_TEXT, TRANSCRIPT, IMAGE }

/** A piece of a file's indexed text, as the tests describe files. */
data class Seg(val type: SegmentType, val ordinal: Int, val text: String?)

/**
 * Nunsori's index without Nunsori: files in memory, keyword search and matching, tools with the names and shape Nunsori
 * serves, and a change stream whose cursor is the order files were added.
 */
class FakeFileIndex : FileIndex {
    data class File(val id: Long, val name: String, val kind: ItemKind, val text: String, val seq: Long)
    val files = ArrayList<File>()
    val commits = ArrayList<Pair<String, String>>()
    val thumbnails = HashMap<Long, ByteArray>()
    var available = true
    var people = listOf<String>()
    private var seq = 100L

    fun add(name: String, kind: ItemKind, text: String): Long {
        val id = (files.maxOfOrNull { it.id } ?: 0) + 1
        files += File(id, name, kind, text, ++seq); return id
    }

    private fun check() { if (!available) throw io.github.taehunkim.nunsori.sdk.NunsoriUnavailableException(io.github.taehunkim.nunsori.sdk.NunsoriUnavailableException.Reason.NOT_INSTALLED) }

    override suspend fun tools(): List<NunsoriTool> = if (!available) emptyList() else listOf(
        NunsoriTool("search_index", "Search the user's files.", """{"type":"object","properties":{"query":{"type":"string"}}}""", false),
        NunsoriTool("read_file", "Read a file.", """{"type":"object","properties":{"item_id":{"type":"integer"}}}""", false),
        NunsoriTool("move_files", "Move files.", """{"type":"object","properties":{"item_ids":{"type":"string"},"destination":{"type":"string"}}}""", true),
        NunsoriTool("delete_files", "Delete files.", """{"type":"object","properties":{"item_ids":{"type":"string"}}}""", true),
    )

    private fun words(q: String) = q.lowercase().split('|', ' ').filter { it.isNotBlank() }

    override suspend fun runTool(name: String, argsJson: String): Any {
        check()
        val a = JSONObject(argsJson)
        return when (name) {
            "search_index" -> {
                val hits = files.filter { f -> words(a.optString("query")).any { (f.text + " " + f.name).lowercase().contains(it) } }.take(a.optInt("limit", 5))
                if (hits.isEmpty()) "No matching files." else JSONArray(hits.map { JSONObject().put("item_id", it.id).put("name", it.name).put("kind", it.kind.apiName).put("snippet", it.text.take(160)) }).toString()
            }
            "read_file" -> files.firstOrNull { it.id == a.optLong("item_id") }?.let { JSONObject().put("item_id", it.id).put("name", it.name).put("text", it.text).toString() } ?: "Error: no file."
            "move_files", "delete_files" -> {
                val ids = Regex("\\d+").findAll(a.optString("item_ids")).map { it.value.toLong() }.toList()
                val refs = files.filter { it.id in ids }.map { FileRef(it.id, it.name, "Documents/") }
                val payload = JSONObject().put("files", JSONArray(refs.map { JSONObject().put("id", it.itemId).put("name", it.name) }))
                    .put("destination", a.optString("destination")).put("permanent", a.optBoolean("permanent"))
                Confirmation(name, "summary", refs, payload.toString())
            }
            else -> "Error: unknown tool"
        }
    }

    override suspend fun commit(name: String, payload: String): CommitResult { check(); commits += name to payload; return CommitResult(true, "1 done") }
    override suspend fun items(ids: List<Long>) = files.filter { it.id in ids }.map { ItemInfo(it.id, it.name, it.kind.apiName, null, "2026-01-01", "Documents/", "DONE") }
    override suspend fun thumbnail(id: Long, maxPx: Int): ByteArray? = thumbnails[id]
    override suspend fun readText(id: Long, maxChars: Int) = files.firstOrNull { it.id == id }?.text?.take(maxChars).orEmpty()
    override suspend fun changesSince(cursor: Long, limit: Int): Changes {
        check()
        if (cursor <= 0) return Changes(emptyList(), seq)
        val fresh = files.filter { it.seq > cursor }.sortedBy { it.seq }.take(limit)
        return Changes(fresh.map { it.id }, fresh.lastOrNull()?.seq ?: cursor)
    }
    override suspend fun matchItems(query: String, ids: Collection<Long>, kinds: Collection<String>) =
        files.filter { it.id in ids && (kinds.isEmpty() || it.kind.apiName in kinds) && words(query).any { w -> (it.text + " " + it.name).lowercase().contains(w) } }.map { it.id }
    override suspend fun people() = people
}
