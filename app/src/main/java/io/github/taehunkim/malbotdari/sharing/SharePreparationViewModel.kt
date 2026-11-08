package io.github.taehunkim.malbotdari.sharing

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.taehunkim.malbotdari.MalbotdariApp
import io.github.taehunkim.malbotdari.attachments.AttachmentFormat
import io.github.taehunkim.malbotdari.attachments.BoundedImport
import io.github.taehunkim.malbotdari.attachments.ImportBudget
import io.github.taehunkim.malbotdari.data.db.AttachmentEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps URI grants with the receiving activity until the selected streams have been copied. */
class SharePreparationViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val container = (app as MalbotdariApp).container
    private var uris: List<Uri> = emptyList()
    private var started = false
    private var session: Long? = null
    var items by mutableStateOf<List<ShareCandidate>>(emptyList()); private set
    var selected by mutableStateOf<Set<Int>>(emptySet()); private set
    var copied by mutableStateOf<Set<Int>>(emptySet()); private set
    var failures by mutableStateOf<Map<Int, String>>(emptyMap()); private set
    var text by mutableStateOf(""); private set
    var loading by mutableStateOf(true); private set
    var copying by mutableStateOf(false); private set
    var completeSession by mutableStateOf<Long?>(null); private set
    var fatalError by mutableStateOf(false); private set

    fun start(intent: Intent) {
        if (started) return
        started = true
        uris = SharedDraft.contentUris(intent)
        text = SharedDraft.sharedText(intent)
        viewModelScope.launch {
            items = withContext(Dispatchers.IO) { uris.map { describe(it, intent.type) } }
            selected = ShareSelection.initial(items)
            loading = false
        }
    }

    private fun describe(uri: Uri, fallbackMime: String?): ShareCandidate {
        val resolver = context.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: fallbackMime ?: "application/octet-stream"
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val z = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (z >= 0 && !c.isNull(z)) size = c.getLong(z).takeIf { it >= 0 }
                }
            }
        }
        val display = name?.take(255)?.ifBlank { null } ?: uri.lastPathSegment?.take(255) ?: "attachment"
        return ShareCandidate(display, mime, size, AttachmentFormat.supported(display, mime), uri.scheme == "content")
    }

    fun toggle(index: Int) {
        if (copying || index in copied) return
        if (index in selected) selected = selected - index
        else if (ShareSelection.reason(items, selected, index) == null) selected = selected + index
    }

    /** Confirm is the first operation that creates a chat or opens file content. */
    fun confirm() {
        if (loading || copying || (selected.isEmpty() && text.isBlank())) return
        copying = true
        fatalError = false
        viewModelScope.launch {
            try {
                val id = session ?: container.chat.newSession().also {
                    session = it
                    SharedDraft.save(context, it, SharedDraft.Draft(text, emptyList()))
                }
                SharedDraft.beginImport(id)
                try {
                    val result = withContext(Dispatchers.IO) { copySelected(id) }
                    copied = copied + result.first
                    failures = result.second
                } finally { SharedDraft.endImport(id) }
                if (failures.isEmpty()) completeSession = id
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fatalError = true }
            finally { copying = false }
        }
    }

    private suspend fun copySelected(id: Long): Pair<Set<Int>, Map<Int, String>> {
        val dao = container.db.attachmentDao()
        val budget = ImportBudget()
        dao.ofSession(id).filter { !it.sent }.forEach { budget.added(it.size) }
        val successes = mutableSetOf<Int>()
        val errors = linkedMapOf<Int, String>()
        val dir = File(context.filesDir, "chat/$id").also { it.mkdirs() }
        for (index in selected.sorted()) {
            if (index in copied) continue
            val item = items[index]
            val file = File(dir, "incoming-${UUID.randomUUID()}")
            try {
                val available = budget.nextLimit()
                val size = context.contentResolver.openInputStream(uris[index])?.use { BoundedImport.copy(it, file, available) }
                    ?: throw java.io.IOException("Cannot open file")
                val attachment = try { dao.insert(AttachmentEntity(sessionId = id, name = item.name, mime = item.mime, fileName = file.name, size = size, state = "QUEUED")) }
                    catch (e: Exception) { file.delete(); throw e }
                budget.added(size)
                successes += index
                try { container.attachments.enqueue(id, attachment) }
                catch (e: Exception) { dao.setState(attachment, "FAILED", e.message ?: "Could not start processing") }
            } catch (e: CancellationException) { throw e }
            catch (_: BoundedImport.TooLarge) { errors[index] = "too_large" }
            catch (_: Exception) { errors[index] = "unreadable" }
        }
        return successes to errors
    }

    fun continueWithoutFailed() { if (!copying) session?.let { completeSession = it } }
    fun cancel(onFinished: () -> Unit) {
        if (copying) return
        viewModelScope.launch {
            session?.let { container.chat.deleteSession(it) }
            onFinished()
        }
    }
}
