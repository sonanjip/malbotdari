package io.github.taehunkim.malbotdari.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.taehunkim.malbotdari.attachments.BoundedImport
import io.github.taehunkim.malbotdari.attachments.ImportBudget
import io.github.taehunkim.malbotdari.data.db.AttachmentDao
import io.github.taehunkim.malbotdari.data.db.AttachmentEntity
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A private, durable draft created from an Android share. Originals are copied while the URI grant is valid. */
object SharedDraft {
    const val MAX_TEXT = 30000
    private val importLock = Mutex()
    data class Draft(val text: String, val images: List<String>, val quote: String = "", val rejected: Boolean = false)

    suspend fun receive(context: Context, sessionId: Long, intent: Intent, dao: AttachmentDao? = null, persist: Boolean = true): Draft = importLock.withLock {
        val raw = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }?.toString().orEmpty()
        var rejected = raw.length > MAX_TEXT
        val uris = streams(intent)
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
            val extension = name.substringAfterLast('.', "").lowercase()
            val supported = mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("text/") ||
                mime == "application/pdf" || extension in setOf("txt", "md", "csv", "tsv", "pdf", "docx", "pptx", "xlsx", "odt", "odp", "ods", "mp3", "m4a", "wav", "ogg", "opus", "flac", "aac")
            if (!budget.hasSlot() || !supported || (!mime.startsWith("image/") && dao == null)) { rejected = true; continue }
            val file = File(dir, "incoming-${UUID.randomUUID()}")
            val ok = runCatching {
                require(uri.scheme == "content")
                context.contentResolver.openInputStream(uri)!!.use { BoundedImport.copy(it, file, budget.nextLimit()) }
            }.isSuccess
            if (!ok) { rejected = true; continue }
            if (dao == null && mime.startsWith("image/")) images += file.name
            else {
                try {
                    requireNotNull(dao).insert(AttachmentEntity(sessionId = sessionId, name = name, mime = mime, fileName = file.name, size = file.length()))
                } catch (e: Exception) { file.delete(); rejected = true; continue }
            }
            budget.added(file.length())
        }
        Draft(if (intent.action == Intent.ACTION_PROCESS_TEXT) "" else raw.take(MAX_TEXT), images, if (intent.action == Intent.ACTION_PROCESS_TEXT) raw.take(MAX_TEXT) else "", rejected).also { if (persist) save(context, sessionId, it) }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val extra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty() else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> emptyList()
        }
        val clip = intent.clipData
        return (extra + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }).distinct()
    }

    /** Remove incomplete imports after a process death, without touching registered originals or old drafts. */
    suspend fun cleanOrphans(context: Context, dao: AttachmentDao) = importLock.withLock {
        File(context.filesDir, "chat").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val id = dir.name.toLongOrNull() ?: return@forEach
            val keep = dao.ofSession(id).map { it.fileName }.toSet() + load(context, id)?.images.orEmpty()
            dir.listFiles()?.filter { it.isFile && it.name.startsWith("incoming-") && it.name !in keep }?.forEach { it.delete() }
        }
    }

    fun save(context: Context, id: Long, draft: Draft) {
        val value = JSONObject().put("text", draft.text).put("images", JSONArray(draft.images)).put("quote", draft.quote).toString()
        context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).edit().putString(id.toString(), value).apply()
    }

    fun load(context: Context, id: Long): Draft? = runCatching {
        val raw = context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).getString(id.toString(), null) ?: return null
        val o = JSONObject(raw)
        val a = o.getJSONArray("images")
        Draft(o.optString("text"), (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("incoming-") && !it.contains('/') }, o.optString("quote"))
    }.getOrNull()

    fun clear(context: Context, id: Long) {
        context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
    }
}
