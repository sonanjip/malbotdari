package io.github.taehunkim.malbotdari.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.taehunkim.malbotdari.attachments.BoundedImport
import io.github.taehunkim.malbotdari.attachments.ImportBudget
import io.github.taehunkim.malbotdari.attachments.AttachmentFormat
import io.github.taehunkim.malbotdari.data.db.AttachmentDao
import io.github.taehunkim.malbotdari.data.db.AttachmentEntity
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A private, durable draft created from an Android share. Originals are copied while the URI grant is valid. */
object SharedDraft {
    const val MAX_TEXT = 30000
    private val importLock = Mutex()
    private val draftLock = Any()
    private val _imports = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val imports = _imports.asStateFlow()
    fun beginImport(sessionId: Long) = synchronized(_imports) {
        _imports.value = _imports.value + (sessionId to ((_imports.value[sessionId] ?: 0) + 1))
    }
    fun endImport(sessionId: Long) = synchronized(_imports) {
        val count = (_imports.value[sessionId] ?: 0) - 1
        _imports.value = if (count > 0) _imports.value + (sessionId to count) else _imports.value - sessionId
    }
    data class Draft(
        val text: String, val images: List<String>, val quote: String = "", val rejected: Boolean = false,
        val itemIds: List<Long> = emptyList(), val noteIds: List<Long> = emptyList(),
    )

    suspend fun receive(context: Context, sessionId: Long, intent: Intent, dao: AttachmentDao? = null, persist: Boolean = true, onImported: (Long) -> Unit = {}): Draft = importLock.withLock {
        val raw = sharedText(intent)
        var rejected = intent.action == Intent.ACTION_PROCESS_TEXT && raw.length > MAX_TEXT
        val uris = contentUris(intent)
        val dir = File(context.filesDir, "chat/$sessionId").also { it.mkdirs() }
        val images = mutableListOf<String>()
        val budget = ImportBudget()
        dao?.ofSession(sessionId)?.filter { !it.sent }?.forEach { budget.added(it.size) }
        load(context, sessionId)?.images.orEmpty().forEach { name ->
            val size = File(dir, name).length()
            if (budget.hasSlot() && size <= budget.nextLimit()) budget.added(size) else rejected = true
        }
        for (uri in uris) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: intent.type ?: "application/octet-stream"
            val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } }.getOrNull()?.take(255).orEmpty().ifBlank { "attachment" }
            val supported = AttachmentFormat.supported(name, mime)
            if (!budget.hasSlot() || !supported || (!mime.startsWith("image/", ignoreCase = true) && dao == null)) { rejected = true; continue }
            val file = File(dir, "incoming-${UUID.randomUUID()}")
            val ok = runCatching {
                require(uri.scheme == "content")
                context.contentResolver.openInputStream(uri)!!.use { BoundedImport.copy(it, file, budget.nextLimit()) }
            }.isSuccess
            if (!ok) { rejected = true; continue }
            if (dao == null && mime.startsWith("image/", ignoreCase = true)) images += file.name
            else {
                val id = try {
                    requireNotNull(dao).insert(AttachmentEntity(sessionId = sessionId, name = name, mime = mime, fileName = file.name, size = file.length(), state = "QUEUED"))
                } catch (e: Exception) { file.delete(); rejected = true; continue }
                try { onImported(id) } catch (e: Exception) { requireNotNull(dao).setState(id, "FAILED", e.message ?: "Could not start processing") }
            }
            budget.added(file.length())
        }
        Draft(if (intent.action == Intent.ACTION_PROCESS_TEXT) "" else raw, images, if (intent.action == Intent.ACTION_PROCESS_TEXT) raw.take(MAX_TEXT) else "", rejected).also { if (persist) save(context, sessionId, it) }
    }

    fun sharedText(intent: Intent): String {
        val extra = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }?.toString().orEmpty()
        val clipText = if (intent.action == Intent.ACTION_PROCESS_TEXT) emptyList() else
            (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.text?.toString() }
        return (listOf(extra) + clipText.filterNot { it == extra }).filter { it.isNotEmpty() }.joinToString("\n")
    }

    @Suppress("DEPRECATION")
    fun contentUris(intent: Intent): List<Uri> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        val extra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty() else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> emptyList()
        }
        val clip = intent.clipData
        val fromClip = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        val combined = extra + fromClip.filter { it !in extra }
        val data = intent.data
        return if (data != null && data !in combined) combined + data else combined
    }

    /** Remove incomplete imports after a process death, without touching registered originals or old drafts. */
    suspend fun cleanOrphans(context: Context, dao: AttachmentDao) = importLock.withLock {
        File(context.filesDir, "chat").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val id = dir.name.toLongOrNull() ?: return@forEach
            val keep = dao.ofSession(id).map { it.fileName }.toSet() + load(context, id)?.images.orEmpty()
            dir.listFiles()?.filter { it.isFile && it.name.startsWith("incoming-") && it.name !in keep }?.forEach { it.delete() }
        }
    }

    fun save(context: Context, id: Long, draft: Draft) = synchronized(draftLock) {
        val value = JSONObject().put("text", draft.text).put("images", JSONArray(draft.images)).put("quote", draft.quote).put("rejected", draft.rejected).put("itemIds", JSONArray(draft.itemIds)).put("noteIds", JSONArray(draft.noteIds)).toString()
        context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).edit().putString(id.toString(), value).apply()
    }

    fun load(context: Context, id: Long): Draft? = synchronized(draftLock) {
        runCatching {
            val raw = context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).getString(id.toString(), null) ?: return null
            val o = JSONObject(raw)
            val a = o.getJSONArray("images")
            fun ids(key: String): List<Long> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { arr.optLong(it, -1) }.filter { it > 0 }.distinct()
            }
            Draft(o.optString("text"), (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("incoming-") && !it.contains('/') },
                o.optString("quote"), o.optBoolean("rejected"), ids("itemIds"), ids("noteIds"))
        }.getOrNull()
    }

    /** Draft edits cannot accidentally clear a concurrently recorded import failure. */
    fun savePreservingRejection(context: Context, id: Long, draft: Draft, replaceRejection: Boolean = false) = synchronized(draftLock) {
        val rejected = if (replaceRejection) draft.rejected else draft.rejected || load(context, id)?.rejected == true
        save(context, id, draft.copy(rejected = rejected))
    }

    fun setRejected(context: Context, id: Long, rejected: Boolean) = synchronized(draftLock) {
        val current = load(context, id) ?: Draft("", emptyList())
        save(context, id, current.copy(rejected = rejected))
    }

    fun clearIfUnchanged(context: Context, id: Long, expected: Draft): Boolean = synchronized(draftLock) {
        if (load(context, id) != expected) false else { clear(context, id); true }
    }

    fun clear(context: Context, id: Long) = synchronized(draftLock) {
        context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
    }

}
