package io.github.taehunkim.malbotdari

import io.github.taehunkim.jogakbo.sdk.JogakboTool
import io.github.taehunkim.malbotdari.notes.NotesSource
import org.json.JSONArray
import org.json.JSONObject

/** Jogakbo without Jogakbo: notes in memory, words matched like its search tool. */
class FakeNotesSource : NotesSource {
    data class Note(val id: Long, val title: String, val summary: String, val body: String)
    val notes = ArrayList<Note>()
    var available = true
    val calls = ArrayList<String>()

    fun add(title: String, summary: String, body: String) = (notes.size + 1L).also { notes += Note(it, title, summary, body) }

    override suspend fun tools(): List<JogakboTool> = if (!available) emptyList() else listOf(
        JogakboTool("search_notes", "Search the user's notes.", """{"type":"object","properties":{"query":{"type":"string"}}}"""),
        JogakboTool("read_note", "Read one note.", """{"type":"object","properties":{"note_id":{"type":"integer"}},"required":["note_id"]}"""),
    )

    override suspend fun runTool(name: String, argsJson: String): String {
        calls += name
        val a = JSONObject(argsJson)
        return when (name) {
            "search_notes" -> {
                val words = a.optString("query").lowercase().split(' ').filter { it.isNotBlank() }
                val hits = notes.filter { n -> words.all { it in (n.title + " " + n.summary + " " + n.body).lowercase() } }
                if (hits.isEmpty()) "No matching notes." else JSONArray(hits.map { JSONObject().put("note_id", it.id).put("title", it.title).put("kind", "memo").put("snippet", it.summary) }).toString()
            }
            "read_note" -> notes.firstOrNull { it.id == a.optLong("note_id") }?.let {
                JSONObject().put("note_id", it.id).put("title", it.title).put("text", "# ${it.title}\n\n## Summary\n\n${it.summary}\n\n## Original\n\n${it.body}").toString()
            } ?: "Error: no note with note_id ${a.optLong("note_id")}."
            else -> "Error: unknown tool $name"
        }
    }
}
