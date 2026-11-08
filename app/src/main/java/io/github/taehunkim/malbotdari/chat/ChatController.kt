package io.github.taehunkim.malbotdari.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.taehunkim.malbotdari.data.db.ChatDao
import io.github.taehunkim.malbotdari.data.db.ChatMessageEntity
import io.github.taehunkim.malbotdari.data.db.ChatRole
import io.github.taehunkim.malbotdari.data.db.ChatSessionEntity
import io.github.taehunkim.malbotdari.data.db.ScheduledTaskEntity
import io.github.taehunkim.malbotdari.chat.actions.ActionManager
import io.github.taehunkim.malbotdari.chat.actions.AddCalendarEventTool
import io.github.taehunkim.malbotdari.chat.actions.AddContactTool
import io.github.taehunkim.malbotdari.chat.schedule.TaskManager
import io.github.taehunkim.malbotdari.data.prefs.SettingsStore
import io.github.taehunkim.malbotdari.ml.llm.ChatEvent
import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import io.github.taehunkim.malbotdari.ml.llm.ChatTurn
import io.github.taehunkim.malbotdari.ml.llm.TurnRole
import io.github.taehunkim.malbotdari.files.FileIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Something the user attached to a message before sending (a Nunsori file or a photo from the picker). */
sealed interface PendingAttachment {
    data class Item(val itemId: Long) : PendingAttachment
    data class Note(val noteId: Long) : PendingAttachment
    data class Photo(val uri: Uri) : PendingAttachment
    data class Quote(val text: String) : PendingAttachment
}

enum class ChatPhase { LOADING, GENERATING, FAILED }

/** What the chat is doing right now (only one answer is generated at a time). */
data class ChatRun(
    val sessionId: Long,
    val phase: ChatPhase,
    /** The answer so far. */
    val partial: String = "",
    val error: String? = null,
    /** Earlier turns were left out of the model's context because they did not fit. */
    val trimmed: Boolean = false,
)

/** Runs chat turns: stores messages, builds the model's context, calls the model and records tool calls. */
class ChatController(
    private val context: Context,
    private val chat: ChatDao,
    /** Nunsori's index (search, read, move/delete); its tools are offered when it is reachable. */
    private val files: FileIndex,
    private val residency: io.github.taehunkim.malbotdari.ml.ModelHost,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val taskManager: TaskManager? = null,
    private val actions: ActionManager? = null,
    /** Jogakbo's notes (search, read); offered when it is reachable. */
    private val notes: io.github.taehunkim.malbotdari.notes.NotesSource? = null,
) {
    private val _run = MutableStateFlow<ChatRun?>(null)
    val run: StateFlow<ChatRun?> = _run.asStateFlow()
    private var job: Job? = null

    /** One answer at a time: user turns and scheduled runs take turns on this lock. */
    private val turnLock = Mutex()
    @Volatile private var scheduledRunning = false

    val busy: Boolean get() = job?.isActive == true || scheduledRunning

    private val queued = HashMap<Long, List<Long>>()
    private val queuedNotes = HashMap<Long, List<Long>>()
    fun queueNotes(sessionId: Long, ids: List<Long>) { synchronized(queuedNotes) { queuedNotes[sessionId] = ids } }
    fun takeQueuedNotes(sessionId: Long): List<Long> = synchronized(queuedNotes) { queuedNotes.remove(sessionId).orEmpty() }

    /** Files chosen elsewhere (Search) to be attached to the next message of [sessionId]. */
    fun queueAttachments(sessionId: Long, itemIds: List<Long>) { synchronized(queued) { queued[sessionId] = itemIds } }

    fun takeQueuedAttachments(sessionId: Long): List<Long> = synchronized(queued) { queued.remove(sessionId) ?: emptyList() }

    suspend fun newSession(tools: Set<String> = ChatToolNames.ALL.toSet()): Long {
        val now = clock()
        return chat.insertSession(ChatSessionEntity(title = "", enabledTools = tools.joinToString(","), autoSearch = false, createdAt = now, updatedAt = now))
    }

    suspend fun deleteSession(id: Long) {
        if (_run.value?.sessionId == id) stop()
        chat.deleteSession(id)
        io.github.taehunkim.malbotdari.sharing.SharedDraft.clear(context, id)
        File(context.filesDir, "chat/$id").deleteRecursively()
    }

    fun send(sessionId: Long, text: String, attachments: List<PendingAttachment> = emptyList(), onStored: () -> Unit = {}) {
        if (busy || (text.isBlank() && attachments.isEmpty())) return
        job = scope.launch { turnLock.withLock { runTurn(sessionId, text.trim(), attachments, onStored = onStored) } }
    }

    fun stop() { job?.cancel() }

    fun dismissError() { if (_run.value?.phase == ChatPhase.FAILED) _run.value = null }

    /** The outcome of one turn: the answer text and the error, if any. */
    data class TurnResult(val answer: String, val error: String? = null)

    /**
     * Runs one scheduled task in its chat and returns the answer. Waits for a running answer to finish first.
     * [extraContext] carries what triggered it (for file tasks: the new files).
     */
    suspend fun runScheduled(task: ScheduledTaskEntity, extraContext: String = ""): TurnResult = turnLock.withLock {
        scheduledRunning = true
        try {
            runTurn(task.sessionId, task.instruction, emptyList(), task, extraContext)
        } finally { scheduledRunning = false }
    }

    /**
     * Asks the chat model which of [files] (lines `- item_id=N name: excerpt`) satisfy [condition]; returns the raw reply,
     * or null when no model could be loaded. Used to check scheduled "new file" tasks before anything is posted; the chat
     * itself is untouched.
     */
    suspend fun judgeFiles(condition: String, files: String): String? = turnLock.withLock {
        scheduledRunning = true
        try {
            val lease = residency.chat() ?: return@withLock null
            lease.use { l ->
                val sb = StringBuilder()
                val prompt = (if (" / " in condition) "Condition: files about or related to any of these: $condition." else "Condition: files about or related to \"$condition\".") + "\n\nFiles:\n$files"
                l.value.reply("judge|${clock()}", JUDGE_SYSTEM, emptyList(), ChatTurn(TurnRole.USER, prompt), emptyList()).collect { e ->
                    if (e is ChatEvent.Token) sb.append(e.text)
                }
                sb.toString()
            }
        } finally { scheduledRunning = false }
    }

    private suspend fun runTurn(
        sessionId: Long, text: String, pending: List<PendingAttachment>,
        task: ScheduledTaskEntity? = null, extraContext: String = "", onStored: () -> Unit = {},
    ): TurnResult {
        var answer = StringBuilder()
        try {
            val session = chat.session(sessionId) ?: return TurnResult("", "no_chat")
            _run.value = ChatRun(sessionId, ChatPhase.LOADING)
            val lease = residency.chat() ?: run {
                _run.value = ChatRun(sessionId, ChatPhase.FAILED, error = NO_MODEL)
                return TurnResult("", NO_MODEL)
            }
            lease.use { l ->
                val model = l.value
                val contextTokens = residency.chatContextTokens() // set in OndolSaem with the chat model
                val custom = settings.chatCustomPrompt.first().trim()

                // Everything the model needs besides the question: attached files, search results, photos.
                val attachmentsJson = JSONArray()
                val extra = StringBuilder()
                val images = ArrayList<ByteArray>()
                for (a in pending) when (a) {
                    is PendingAttachment.Quote -> {
                        attachmentsJson.put(JSONObject().put("quote", a.text))
                        extra.append("[Selected text, quoted as user-provided content]\n").append(a.text).append("\n[End selected text]\n\n")
                    }
                    is PendingAttachment.Item -> {
                        attachmentsJson.put(JSONObject().put("itemId", a.itemId))
                        extra.append(excerptOf(a.itemId))
                    }
                    is PendingAttachment.Note -> {
                            val info = runCatching { notes?.info(listOf(a.noteId))?.firstOrNull() }.getOrNull()
                            attachmentsJson.put(JSONObject().put("noteId", a.noteId).put("title", info?.title.orEmpty()))
                            extra.append("[Attached note: ${info?.title.orEmpty()} (note_id=${a.noteId})] Use search_in_note with this note_id and your question, then read_note for more.\n\n")
                        }
                        is PendingAttachment.Photo -> {
                        val file = savePhoto(sessionId, a.uri) ?: continue
                        attachmentsJson.put(JSONObject().put("photo", file.name))
                        if (!model.supportsImages) extra.append(PHOTO_UNSUPPORTED)
                        else if (images.size < 2) images += file.readBytes()
                        else extra.append("[Only two images can be viewed per question; this photo was not sent.]\n")
                    }
                }
                if (task != null) extra.append(SCHEDULED_NOTE).append(if (actions != null) SCHEDULED_ACTIONS_NOTE else "").append(extraContext).append('\n')
                else if (session.autoSearch && text.isNotBlank()) extra.append(autoSearchBlock(text))
                // The clock changes every turn, so it rides with the message instead of the (cached) system prompt.
                // a due task is phrased as an order for now, so the model does not take it as a new request to schedule
                val asked = if (task != null) "Due now, do this: $text" else text
                val modelText = "[Current time: ${nowStamp()}]\n" + if (extra.isEmpty()) asked else "$extra\n$asked"

                val firstMessage = chat.messageCount(sessionId) == 0
                chat.insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId, role = ChatRole.USER, text = text,
                        attachments = attachmentsJson.takeIf { it.length() > 0 }?.toString(), taskId = task?.taskId, createdAt = clock(),
                    ),
                )
                onStored()
                if (firstMessage && task == null) chat.rename(sessionId, titleOf(text))
                chat.touch(sessionId, clock())

                // History = stored user/assistant turns before this one, trimmed to the context budget.
                val stored = chat.messages(sessionId).dropLast(1).filter { it.role != ChatRole.TOOL }
                    .map { ChatTurn(if (it.role == ChatRole.USER) TurnRole.USER else TurnRole.ASSISTANT, it.text + (it.attachments?.let { a -> "\n[Attachment references: $a]" }.orEmpty())) }
                val imageTool = ViewImageTool(files, model.supportsImages, (2 - images.size).coerceAtLeast(0))
                val baseTools = if (task != null) scheduledTools(session) else enabledTools(session)
                val recentAttachments = chat.messages(sessionId).takeLast(8).mapNotNull { it.attachments }.joinToString()
                val hasReferences = pending.any { it is PendingAttachment.Item || it is PendingAttachment.Note } ||
                    recentAttachments.contains("itemId") || recentAttachments.contains("noteId")
                val offered = baseTools + if (ChatToolNames.READ in session.enabledTools.split(',')) listOf(imageTool) else emptyList()
                // Keep enabled actions available, but omit verbose argument documentation to leave room for passages.
                val tools = if (hasReferences) offered.map { CompactChatTool(it) } else offered
                // named people (Nunsori's People screen) go into the system prompt when the model can search by person
                val names = if (tools.any { it.name == ChatToolNames.SEARCH }) runCatching { files.people() }.getOrNull().orEmpty().take(MAX_NAMES) else emptyList()
                val budget = (contextTokens * ContextBudget.HISTORY_SHARE).toInt() - ContextBudget.estimateTokens(modelText) - SYSTEM_TOKENS -
                    ContextBudget.estimateTokens(custom) - ContextBudget.estimateTokens(names.joinToString())
                val history = ContextBudget.trim(stored, budget.coerceAtLeast(0))

                // the custom prompt is part of the key: a changed prompt starts a fresh conversation state
                // and so is the date: the system prompt names today, so a new day must not reuse yesterday's state
                // ... and so are the known names
                val key = "$sessionId|${tools.joinToString { it.name }}|${custom.hashCode()}|${todayString()}|${names.hashCode()}"
                _run.value = ChatRun(sessionId, ChatPhase.GENERATING, trimmed = history.dropped > 0)
                var toolCalls = 0
                val toolContext = StringBuilder()
                model.reply(key, systemPrompt(custom, tools.any { it.name == ChatToolNames.CALENDAR }, tools.any { it.name == ChatToolNames.MOVE }, names, tools.any { it.name == ChatToolNames.NOTES }), history.turns, ChatTurn(TurnRole.USER, modelText, images), tools).collect { e ->
                    when (e) {
                        is ChatEvent.Token -> {
                            answer.append(e.text)
                            _run.value = ChatRun(sessionId, ChatPhase.GENERATING, answer.toString(), trimmed = history.dropped > 0)
                        }
                        is ChatEvent.ToolRan -> {
                            toolContext.append(e.name).append(": ").append(ContextBudget.clip(e.result, 1500)).append("\n")
                            chat.insertMessage(
                                ChatMessageEntity(
                                    sessionId = sessionId, role = ChatRole.TOOL, text = ContextBudget.clip(e.result, MAX_STORED_RESULT),
                                    toolName = e.name, toolArgs = e.args, taskId = task?.taskId ?: scheduledIdOf(e.name, e.result), actionId = actionIdOf(e.name, e.result), createdAt = clock(),
                                ),
                            )
                            if (++toolCalls > MAX_TOOL_CALLS) throw TooManyToolCalls()
                        }
                    }
                }
                if (imageTool.loaded.isNotEmpty()) {
                    // A new conversation key forces the host to replay the bounded context with the requested images.
                    // Previous images are retrieved again by view_image on later questions, rather than relying on a cache.
                    answer = StringBuilder()
                    val request = modelText + "\n\nImages requested via view_image: " + imageTool.loaded.joinToString { it.first } +
                        "\n" + ContextBudget.clip(toolContext.toString(), 2500) + "\nAnswer the original question using these images."
                    model.reply("$key|vision|${UUID.randomUUID()}", systemPrompt(custom, false, false, names, false),
                        ContextBudget.trim(stored, ((contextTokens * ContextBudget.HISTORY_SHARE).toInt() - ContextBudget.estimateTokens(request) - SYSTEM_TOKENS - ContextBudget.estimateTokens(custom)).coerceAtLeast(0)).turns, ChatTurn(TurnRole.USER, request, images + imageTool.loaded.map { it.second }), emptyList()).collect { e ->
                        if (e is ChatEvent.Token) {
                            answer.append(e.text)
                            _run.value = ChatRun(sessionId, ChatPhase.GENERATING, answer.toString(), trimmed = history.dropped > 0)
                        }
                    }
                }
            }
            saveAnswer(sessionId, answer.toString(), task?.taskId)
            _run.value = null
            return TurnResult(answer.toString().trim())
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { saveAnswer(sessionId, answer.toString(), task?.taskId); _run.value = null }
            throw e
        } catch (e: TooManyToolCalls) {
            val text = answer.toString().ifBlank { TOO_MANY_CALLS }
            saveAnswer(sessionId, text, task?.taskId)
            _run.value = null
            return TurnResult(text.trim())
        } catch (e: Exception) {
            Log.w(TAG, "chat turn failed", e)
            saveAnswer(sessionId, answer.toString(), task?.taskId)
            val msg = e.message ?: e.javaClass.simpleName
            // a scheduled run has nobody to dismiss the error; the failure is recorded on the task instead
            _run.value = if (task != null) null else ChatRun(sessionId, ChatPhase.FAILED, error = msg)
            return TurnResult(answer.toString().trim(), msg)
        }
    }

    /** The `task_id` a schedule_task call returned, so its card can be found from the stored tool message. */
    private fun scheduledIdOf(tool: String, result: String): Long? =
        if (tool != ChatToolNames.SCHEDULE && tool != ChatToolNames.UPDATE_TASK) null else runCatching { JSONObject(result).optLong("task_id", -1) }.getOrNull()?.takeIf { it > 0 }

    /** The `action_id` of a proposal (calendar event, contact), so its card can be found from the stored tool message. */
    private fun actionIdOf(tool: String, result: String): Long? =
        if (tool !in ChatToolNames.ACTION_TOOLS) null else runCatching { JSONObject(result).optLong("action_id", -1) }.getOrNull()?.takeIf { it > 0 }

    /** Nunsori's tools that the chat has switched on (none when Nunsori is missing or has not allowed this app). */
    private suspend fun fileTools(on: Set<String>, sessionId: Long): List<ChatTool> =
        runCatching { files.tools() }.getOrDefault(emptyList())
            .filter { ChatToolNames.switchOf(it.name) in on && (!it.confirm || actions != null) }
            .map { NunsoriChatTool(it, files, actions, sessionId) }

    /** Jogakbo's tools when the chat has notes switched on (none when Jogakbo is missing or has not allowed this app). */
    private suspend fun noteTools(on: Set<String>): List<ChatTool> {
        val n = notes ?: return emptyList()
        if (ChatToolNames.NOTES !in on) return emptyList()
        return runCatching { n.tools() }.getOrDefault(emptyList()).map { JogakboChatTool(it, n) }
    }

    /** Search and read, plus the calendar/contact/file tools when the chat has them on: they only leave cards for the user to approve. */
    private suspend fun scheduledTools(session: ChatSessionEntity): List<ChatTool> {
        val on = session.enabledTools.split(',').toSet()
        return buildList {
            addAll(fileTools(on + ChatToolNames.SEARCH + ChatToolNames.READ, session.sessionId))
            addAll(noteTools(on))
            if (actions != null && ChatToolNames.CALENDAR in on) { add(AddCalendarEventTool(actions, session.sessionId)); add(AddContactTool(actions, session.sessionId)) }
        }
    }

    private class TooManyToolCalls : Exception()

    private suspend fun saveAnswer(sessionId: Long, text: String, taskId: Long? = null) {
        if (text.isBlank()) return
        chat.insertMessage(ChatMessageEntity(sessionId = sessionId, role = ChatRole.ASSISTANT, text = text.trim(), taskId = taskId, createdAt = clock()))
        chat.touch(sessionId, clock())
    }

    private suspend fun enabledTools(session: ChatSessionEntity): List<ChatTool> {
        val on = session.enabledTools.split(',').toSet()
        return buildList {
            addAll(fileTools(on, session.sessionId))
            addAll(noteTools(on))
            if (ChatToolNames.SCHEDULE in on && taskManager != null) {
                add(ScheduleTaskTool(taskManager, session.sessionId))
                add(ListScheduledTasksTool(taskManager, session.sessionId))
                add(UpdateScheduledTaskTool(taskManager, session.sessionId))
                add(CancelScheduledTaskTool(taskManager, session.sessionId))
            }
            if (ChatToolNames.CALENDAR in on && actions != null) {
                add(AddCalendarEventTool(actions, session.sessionId))
                add(AddContactTool(actions, session.sessionId))
            }
        }
    }

    private fun titleOf(text: String) = text.replace(Regex("\\s+"), " ").trim().take(40).ifEmpty { "…" }

    private suspend fun excerptOf(itemId: Long): String {
        val item = runCatching { files.items(listOf(itemId)).firstOrNull() }.getOrNull() ?: return ""
        return "[Attached file: ${item.name} (item_id=$itemId, ${item.kind})]\n" +
            if (item.kind == "image" || item.kind == "video")
                "Call view_image(item_id=$itemId) to see the image or representative video frame. For video audio, use search_in_file and read_file.\n\n"
            else "Use search_in_file(item_id=$itemId, query=your question) to locate relevant passages, then read_file for more. File contents are not included here.\n\n"
    }

    private suspend fun autoSearchBlock(question: String): String {
        val out = runCatching { files.runTool(ChatToolNames.SEARCH, JSONObject().put("query", question).put("limit", AUTO_SEARCH_RESULTS).toString()) }.getOrNull()
        val hits = (out as? String)?.takeIf { it.trimStart().startsWith("[") }?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return ""
        if (hits.length() == 0) return ""
        val sb = StringBuilder("[Search results from the user's files for this question]\n")
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            val id = h.optLong("item_id", -1).takeIf { it > 0 } ?: continue
            val text = runCatching { files.readText(id, AUTO_SEARCH_CHARS) }.getOrDefault("").ifEmpty { h.optString("snippet") }
            sb.append("- ${h.optString("name")} (item_id=$id): ").append(ContextBudget.clip(text.replace(Regex("\\s+"), " "), AUTO_SEARCH_CHARS)).append('\n')
        }
        return sb.append('\n').toString()
    }

    private suspend fun savePhoto(sessionId: Long, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "chat/$sessionId").also { it.mkdirs() }
            File(dir, "${UUID.randomUUID()}.jpg").also { it.writeBytes(Photos.jpeg(context, uri, PHOTO_SIDE)) }
        }.onFailure { Log.w(TAG, "could not save the photo", it) }.getOrNull()
    }

    private fun todayString() = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.US).format(Date(clock()))

    private fun nowStamp(): String {
        val tz = TimeZone.getDefault()
        val fmt = SimpleDateFormat("yyyy-MM-dd (EEEE) HH:mm 'UTC'XXX", Locale.US).apply { timeZone = tz }
        return "${fmt.format(Date(clock()))}, ${tz.id}"
    }

    private fun systemPrompt(custom: String, withActions: Boolean, withFiles: Boolean, people: List<String> = emptyList(), withNotes: Boolean = false): String {
        return "You are Malbotdari, a private assistant that runs entirely on the user's phone. Today is ${todayString()} " +
            "(time zone ${TimeZone.getDefault().id}); work out words like today, yesterday or a year ago from it, " +
            "and give search_index date_from/date_to as YYYY-MM-DD (a query is optional when you filter by date or kind, e.g. to list yesterday's photos). " +
            "When the user asks you to do something later or repeatedly (every day at 9, every Monday, in 30 minutes, whenever a new file about X is added), " +
            "call schedule_task with the instruction written so it can run on its own (for a file task, query lists the user's own words first and then 2 to 5 concrete words a matching file would contain, separated by |, e.g. \"schedule|wedding invitation|invitation|appointment\"; never the whole instruction); it only takes effect after the user confirms the card shown in the chat, so tell them to confirm it. To change an existing task the user asks about, use list_scheduled_tasks and update_scheduled_task instead of creating a new one. " +
            (if (withActions) "When a file shows an event or a person worth saving (an invitation, ticket, appointment, business card) and the user wants it in their calendar or contacts, call add_calendar_event or add_contact with the details; the user approves a card first, so never say it is already added. " else "") +
            (if (withFiles) "To move or delete files the user asks about, first find them with search_index and then call move_files or delete_files with their item_ids (never guess ids); the user approves a card first, deleting goes to the trash unless they ask for permanent deletion, and you must not say the files are already moved or deleted. " else "") +
            (if (people.isEmpty()) "" else "People the user named in their photos: ${people.joinToString()}. When the user mentions one of them, pass that name as person to search_index" +
                (if (withFiles) ", move_files or delete_files (person instead of item_ids moves or deletes every photo of that person)" else "") + ". ") +
            "Answer from the user's own files when the question is about them: use the search_index tool to find files and read_file to read them, " +
            "if those tools are available. Attachments contain references only. For images call view_image before describing them; for attached documents or notes call search_in_file or search_in_note to find relevant passages. Never claim to have read unseen content. Name the files (and page or time) your answer is based on. " +
            (if (withNotes) "The user's notes (memos, saved web pages, YouTube videos and recordings, each with a summary) are in Jogakbo: " +
                "use search_notes to find notes and read_note to read one when the question is about something they wrote down, saved, watched or recorded; name the notes you used. " else "") +
            "If nothing relevant is found, say so instead of guessing. Keep answers concise and reply in the language the user writes in." +
            (if (custom.isEmpty()) "" else "\n\nThe user's own instructions (follow them unless they conflict with the rules above):\n$custom")
    }

    companion object {
        private const val TAG = "Malbot.Chat"
        const val NO_MODEL = "no_model"
        const val MAX_TOOL_CALLS = 8
        /** Named people listed in the system prompt at most (the 4K default window). */
        private const val MAX_NAMES = 30
        private const val MAX_STORED_RESULT = 4000
        private const val ATTACHMENT_CHARS = 3000
        private const val AUTO_SEARCH_RESULTS = 3
        private const val AUTO_SEARCH_CHARS = 800
        private const val SYSTEM_TOKENS = 350
        private const val PHOTO_SIDE = 1024
        private const val PHOTO_UNSUPPORTED = "[A photo is attached, but this chat model cannot see images.]\n\n"
        private const val JUDGE_SYSTEM = "You check files against a condition. Reply with only the item_id numbers of the files that clearly match, " +
            "separated by commas, or the word NONE. No other words."
        private const val SCHEDULED_NOTE = "[This is a scheduled task the user set up earlier, and it is due now; the user is not present. Do not schedule anything: " +
            "carry out the instruction below right now and write the result as a message to the user (for a reminder, write the reminder itself, e.g. \"Time to stretch!\").]\n"
        private const val SCHEDULED_ACTIONS_NOTE = "[Tools that add events or contacts or move or delete files only create cards the user approves later; never say they are done.]\n"
        private const val TOO_MANY_CALLS = "I stopped because I called tools too many times without an answer."
    }
}
