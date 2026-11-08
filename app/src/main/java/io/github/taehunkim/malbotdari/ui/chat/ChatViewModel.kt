package io.github.taehunkim.malbotdari.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.taehunkim.malbotdari.AppContainer
import io.github.taehunkim.malbotdari.chat.ChatController
import io.github.taehunkim.malbotdari.chat.ChatRun
import io.github.taehunkim.malbotdari.chat.ChatToolNames
import io.github.taehunkim.malbotdari.chat.PendingAttachment
import io.github.taehunkim.malbotdari.data.db.ChatMessageEntity
import io.github.taehunkim.malbotdari.data.db.ChatSessionEntity
import io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories.LLM
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.json.JSONArray

/** A file or photo attached to a message, as shown in the UI. */
data class AttachmentChip(val itemId: Long? = null, val photoFile: String? = null, val noteId: Long? = null, val quote: String? = null, val localId: Long? = null, val label: String)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer, val sessionId: Long) : ViewModel() {
    private val dao = c.db.chatDao()
    private val controller: ChatController = c.chat

    val session: StateFlow<ChatSessionEntity?> = dao.observeSession(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val messages: StateFlow<List<ChatMessageEntity>> = dao.observeMessages(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val run: StateFlow<ChatRun?> = controller.run.map { r -> r?.takeIf { it.sessionId == sessionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** True while any chat session is generating (one model, one answer at a time). */
    val busy: StateFlow<Boolean> = controller.run.map { it != null && it.phase != io.github.taehunkim.malbotdari.chat.ChatPhase.FAILED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val taskDao = c.db.taskDao()
    /** Scheduled tasks of this chat (confirmed ones: active, paused, finished). */
    val tasks: StateFlow<List<io.github.taehunkim.malbotdari.data.db.ScheduledTaskEntity>> = taskDao.observeOfSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun task(id: Long) = taskDao.observe(id)
    fun confirmTask(id: Long) { viewModelScope.launch { c.taskManager.confirm(id) } }
    fun deleteTask(id: Long) { viewModelScope.launch { c.taskManager.delete(id) } }
    fun action(id: Long) = c.db.actionDao().observe(id)
    /** Approve a card: [start] hands the intent to a system screen and returns false when no app can take it. */
    fun approveAction(id: Long, permanent: Boolean = false, start: (android.content.Intent) -> Boolean = { false }) {
        viewModelScope.launch {
            when (val r = c.actions.approve(id, permanent)) {
                is io.github.taehunkim.malbotdari.chat.actions.ApprovalResult.Launch -> if (start(r.intent)) c.actions.markDone(id) else c.actions.markFailed(id, "no_app")
                is io.github.taehunkim.malbotdari.chat.actions.ApprovalResult.Done -> c.actions.markDone(id, r.note)
                is io.github.taehunkim.malbotdari.chat.actions.ApprovalResult.Failed -> when (r.reason) {
                    io.github.taehunkim.malbotdari.chat.actions.ActionManager.NEEDS_ACCESS -> needsFileAccess = true
                    io.github.taehunkim.malbotdari.chat.actions.ActionManager.NUNSORI_UNAVAILABLE -> { nunsoriUnavailable = true; c.files.refresh() }
                }
            }
        }
    }
    /** Set when a file card was approved but Nunsori lacks "All files access" (the UI then points to Nunsori). */
    var needsFileAccess by mutableStateOf(false)
    /** Set when a file card was approved but Nunsori could not be reached. */
    var nunsoriUnavailable by mutableStateOf(false)
    /** Whether Nunsori (the files) can be used, for the hint above the input. */
    val files = c.files.state
    val localItems = c.db.attachmentDao().observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeImports = io.github.taehunkim.malbotdari.sharing.SharedDraft.imports.map { it[sessionId] ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val localPending = localItems.map { items -> items.filter { !it.sent } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun dismissAction(id: Long) { viewModelScope.launch { c.actions.dismiss(id) } }
    fun pauseTask(id: Long, paused: Boolean) { viewModelScope.launch { c.taskManager.setPaused(id, paused) } }

    val modelReady: StateFlow<Boolean> = c.models.state.map { it.status == io.github.taehunkim.malbotdari.ml.HostState.Status.UNKNOWN || it.ready(LLM) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    /** Why the chat model is unavailable, for the hint. */
    val host = c.models.state
    val supportsImages: StateFlow<Boolean> = c.models.state.map { it.model(LLM)?.vision == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun refreshHost() { viewModelScope.launch { c.models.refresh() } }

    private val sharedDraft = io.github.taehunkim.malbotdari.sharing.SharedDraft.load(c.context, sessionId)
    private var draftState by mutableStateOf(sharedDraft?.text.orEmpty())
    var draft: String
        get() = draftState
        set(value) { draftState = value; persistSharedDraft() }
    /** Attachments waiting for the next send. */
    var pending by mutableStateOf<List<PendingAttachment>>(sharedDraft?.images.orEmpty().map { PendingAttachment.Photo(Uri.fromFile(java.io.File(c.context.filesDir, "chat/$sessionId/$it"))) } + listOfNotNull(sharedDraft?.quote?.takeIf { it.isNotBlank() }?.let { PendingAttachment.Quote(it) }))
        private set
    var noteLabels by mutableStateOf<Map<Long, String>>(emptyMap())
        private set
    var pendingLabels by mutableStateOf<Map<Long, String>>(emptyMap())
        private set

    /** Names of files referenced by the messages and pending attachments. */
    val names: StateFlow<Map<Long, String>> = messages.flatMapLatest { msgs ->
        kotlinx.coroutines.flow.flow {
            val ids = msgs.flatMap { attachmentsOf(it).mapNotNull { a -> a.itemId } }.distinct()
            emit(if (ids.isEmpty()) emptyMap() else runCatching { c.files.items(ids).associate { it.itemId to it.name } }.getOrDefault(emptyMap()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // Files chosen in Nunsori's Search ("Ask in chat") arrive as pending attachments.
        val noteIds = controller.takeQueuedNotes(sessionId)
        pending = pending + noteIds.map { PendingAttachment.Note(it) }
        if (noteIds.isNotEmpty()) viewModelScope.launch {
            noteLabels = runCatching { c.notes.info(noteIds).associate { it.noteId to it.title } }.getOrDefault(emptyMap())
        }
        val queued = controller.takeQueuedAttachments(sessionId)
        if (queued.isNotEmpty()) viewModelScope.launch {
            pending = pending + queued.map { PendingAttachment.Item(it) }
            pendingLabels = runCatching { c.files.items(queued).associate { it.itemId to it.name } }.getOrDefault(emptyMap())
        }
    }

    fun send() {
        val text = draft.trim()
        val sent = pending.toList()
        viewModelScope.launch {
            val imports = io.github.taehunkim.malbotdari.sharing.SharedDraft.imports.value[sessionId] ?: 0
            val items = c.db.attachmentDao().ofSession(sessionId)
            if (!io.github.taehunkim.malbotdari.attachments.AttachmentReadiness.canSend(imports, items.map { it.state }, importFailed) ||
                (io.github.taehunkim.malbotdari.sharing.SharedDraft.imports.value[sessionId] ?: 0) != 0) return@launch
            val local = items.filter { !it.sent }.map { PendingAttachment.Local(it.attachmentId) }
            if (text.isEmpty() && sent.isEmpty() && local.isEmpty()) return@launch
            controller.send(sessionId, text, sent + local) {
                viewModelScope.launch {
                    if (draft.trim() == text && pending == sent) {
                        draft = ""; pending = emptyList()
                        io.github.taehunkim.malbotdari.sharing.SharedDraft.clear(c.context, sessionId)
                    }
                }
            }
        }
    }

    fun stop() = controller.stop()
    fun dismissError() = controller.dismissError()
    fun addPhoto(uri: Uri) { pending = pending + PendingAttachment.Photo(uri) }
    var importFailed by mutableStateOf(sharedDraft?.rejected == true)
        private set
    fun dismissImportError() { importFailed = false; persistSharedDraft(forceRejected = false) }
    fun addFiles(uris: List<Uri>) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
        val shares = io.github.taehunkim.malbotdari.sharing.SharedDraft
        shares.beginImport(sessionId)
        val task = c.appScope.async(kotlinx.coroutines.Dispatchers.IO) {
            val resolver = c.context.contentResolver
            val persisted = uris.filter { uri -> runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess }
            try {
                try {
                    shares.receive(c.context, sessionId, intent, c.db.attachmentDao(), persist = false, onImported = { c.attachments.enqueue(sessionId, it) })
                        .also { if (it.rejected) shares.setRejected(c.context, sessionId, true) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { shares.setRejected(c.context, sessionId, true); io.github.taehunkim.malbotdari.sharing.SharedDraft.Draft("", emptyList(), rejected = true) }
            } finally {
                persisted.forEach { uri -> runCatching { resolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                shares.endImport(sessionId)
            }
        }
        viewModelScope.launch {
            val result = task.await()
            pending = pending + result.images.map { PendingAttachment.Photo(Uri.fromFile(java.io.File(c.context.filesDir, "chat/$sessionId/$it"))) }
            importFailed = importFailed || result.rejected || shares.load(c.context, sessionId)?.rejected == true
            persistSharedDraft(forceRejected = importFailed)
        }
    }
    fun retryLocal(id: Long) { viewModelScope.launch { c.db.attachmentDao().setState(id, "QUEUED"); c.attachments.enqueue(sessionId, id) } }
    fun removeLocal(id: Long) { viewModelScope.launch {
        val dao = c.db.attachmentDao()
        val item = dao.get(id, sessionId) ?: return@launch
        dao.delete(id, sessionId)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(c.context.filesDir, "chat/$sessionId/${item.fileName}").delete() }
    } }
    fun removePending(a: PendingAttachment) { pending = pending - a; persistSharedDraft() }

    private fun persistSharedDraft(forceRejected: Boolean? = null) {
        val images = pending.filterIsInstance<PendingAttachment.Photo>().mapNotNull { photo ->
            photo.uri.path?.let { java.io.File(it) }?.takeIf { photo.uri.scheme == "file" && it.name.startsWith("incoming-") }?.name
        }
        io.github.taehunkim.malbotdari.sharing.SharedDraft.save(c.context, sessionId,
            io.github.taehunkim.malbotdari.sharing.SharedDraft.Draft(draft, images, pending.filterIsInstance<PendingAttachment.Quote>().firstOrNull()?.text.orEmpty(),
                forceRejected ?: (importFailed || io.github.taehunkim.malbotdari.sharing.SharedDraft.load(c.context, sessionId)?.rejected == true)))
    }

    fun toggleTool(name: String) = viewModelScope.launch {
        val s = dao.session(sessionId) ?: return@launch
        val on = s.enabledTools.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!on.add(name)) on.remove(name)
        dao.setTools(sessionId, on.joinToString(","), s.autoSearch)
    }

    fun toggleAutoSearch() = viewModelScope.launch {
        val s = dao.session(sessionId) ?: return@launch
        dao.setTools(sessionId, s.enabledTools, !s.autoSearch)
    }

    fun rename(title: String) = viewModelScope.launch { dao.rename(sessionId, title.trim().ifEmpty { return@launch }) }

    companion object {
        val TOOLS = ChatToolNames.ALL

        fun attachmentsOf(m: ChatMessageEntity): List<AttachmentChip> {
            val json = m.attachments ?: return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    if (o.has("attachmentId")) AttachmentChip(localId = o.getLong("attachmentId"), label = o.optString("name")) else if (o.has("quote")) AttachmentChip(quote = o.getString("quote"), label = "") else if (o.has("noteId")) AttachmentChip(noteId = o.getLong("noteId"), label = o.optString("title")) else if (o.has("itemId")) AttachmentChip(itemId = o.getLong("itemId"), label = "") else AttachmentChip(photoFile = o.getString("photo"), label = "")
                }
            }.getOrDefault(emptyList())
        }
    }
}
