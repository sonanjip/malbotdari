package io.github.taehunkim.malbotdari.ui.chat

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.chat.ChatController
import io.github.taehunkim.malbotdari.chat.ChatPhase
import io.github.taehunkim.malbotdari.chat.ChatToolNames
import io.github.taehunkim.malbotdari.chat.ChatPreviews
import io.github.taehunkim.malbotdari.chat.PendingAttachment
import io.github.taehunkim.malbotdari.chat.PreviewHit
import io.github.taehunkim.malbotdari.data.db.ChatMessageEntity
import io.github.taehunkim.malbotdari.data.db.ChatRole
import io.github.taehunkim.malbotdari.rememberContainer
import io.github.taehunkim.malbotdari.ui.common.WarningCard
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun ChatScreen(sessionId: Long, onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: ChatViewModel = viewModel(key = "chat-$sessionId", factory = viewModelFactory { initializer { ChatViewModel(container, sessionId) } })
    val session by vm.session.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val localPending by vm.localPending.collectAsStateWithLifecycle()
    val activeImports by vm.activeImports.collectAsStateWithLifecycle()
    val localItems by vm.localItems.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val images by vm.supportsImages.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val filesState by vm.files.collectAsStateWithLifecycle()
    var selectedQuote by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.addFiles(uris) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.addFiles(listOf(it)) } }

    LaunchedEffect(messages.size, run?.partial?.length) {
        val last = messages.size + (if (run != null) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    // Keep the newest message visible when the keyboard opens (the list shrinks with the composer).
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeOpen) {
        val last = messages.size + (if (run != null) 1 else 0) - 1
        if (imeOpen && last >= 0) listState.scrollToItem(last)
    }
    // Hits of each answer's turn, for the file previews under it.
    val previews = remember(messages) { previewsByMessage(messages) }

    // The Scaffold already pads the system bars (and marks them consumed), so only the keyboard is added here.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                session?.title?.ifEmpty { null } ?: stringResource(R.string.chat_untitled), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { tasksOpen = true }) {
                Icon(Icons.Filled.Schedule, stringResource(R.string.tasks_open), tint = if (tasks.any { it.status == io.github.taehunkim.malbotdari.data.db.TaskStatus.ACTIVE }) MaterialTheme.colorScheme.tertiary else LocalContentColor.current)
            }
            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.chat_rename)) }
        }
        if (!modelReady) WarningCard(stringResource(R.string.chat_model_missing), stringResource(R.string.go_settings), onOpenSettings)
        // the file tools need Nunsori: say why they are missing when the chat has them switched on
        val wantsFiles = session?.enabledTools?.split(',')?.any { it == ChatToolNames.SEARCH || it == ChatToolNames.READ || it == ChatToolNames.MOVE } == true
        if (wantsFiles) when (filesState.status) {
            io.github.taehunkim.malbotdari.files.IndexState.Status.NOT_INSTALLED ->
                WarningCard(stringResource(R.string.files_not_installed), stringResource(R.string.go_settings), onOpenSettings)
            io.github.taehunkim.malbotdari.files.IndexState.Status.NOT_APPROVED ->
                WarningCard(stringResource(R.string.files_not_approved), stringResource(R.string.files_open_nunsori)) {
                    runCatching { ctx.startActivity(io.github.taehunkim.nunsori.sdk.Nunsori.approvalIntent()) }
                }
            io.github.taehunkim.malbotdari.files.IndexState.Status.TOO_OLD ->
                WarningCard(stringResource(R.string.files_too_old), stringResource(R.string.go_settings), onOpenSettings)
            else -> {}
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && run == null) item { Text(stringResource(R.string.chat_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
            items(messages, key = { it.msgId }) { m -> MessageRow(sessionId, m, names, vm, previews[m.msgId].orEmpty(), onOpenItem = { io.github.taehunkim.malbotdari.ui.common.openItem(ctx, it) }) }
            run?.let { r ->
                item(key = "run") {
                    when (r.phase) {
                        ChatPhase.LOADING -> StatusRow(stringResource(R.string.chat_loading_model))
                        ChatPhase.GENERATING -> {
                            if (r.partial.isEmpty()) StatusRow(stringResource(R.string.chat_thinking)) else Bubble(r.partial, mine = false, actions = false)
                            if (r.trimmed) Text(stringResource(R.string.chat_trimmed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ChatPhase.GENERATING_IMAGE -> {
                            if (r.partial.isNotEmpty()) Bubble(r.partial, mine = false, actions = false)
                            val cardGenerating = messages.any { m ->
                                m.toolName == ChatToolNames.GENERATE_IMAGE &&
                                    runCatching { JSONObject(m.text).optString("status") == "generating" }.getOrDefault(false)
                            }
                            if (!cardGenerating) StatusRow(stringResource(R.string.chat_image_generating))
                        }
                        ChatPhase.FAILED -> WarningCard(
                            if (r.error == ChatController.NO_MODEL) stringResource(R.string.chat_model_missing) else stringResource(R.string.chat_error, r.error.orEmpty()),
                            stringResource(R.string.chat_dismiss), vm::dismissError,
                        )
                    }
                }
            }
        }

        // Pending attachments, then the composer.
        if (vm.pending.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.pending.forEach { a ->
                    val label = when (a) {
                        is PendingAttachment.Item -> vm.pendingLabels[a.itemId] ?: "#${a.itemId}"
                        is PendingAttachment.Note -> vm.noteLabels[a.noteId] ?: "Jogakbo #${a.noteId}"
                        is PendingAttachment.Photo -> stringResource(R.string.chat_photo)
                        is PendingAttachment.Quote -> stringResource(R.string.share_selected_text)
                        is PendingAttachment.Local -> "#${a.attachmentId}"
                    }
                    InputChip(
                        selected = false, onClick = { if (a is PendingAttachment.Quote) selectedQuote = a.text }, label = { Text(label, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.chat_remove_attachment), Modifier.size(18.dp).clickable { vm.removePending(a) }) },
                    )
                }
            }
        }
        if (activeImports > 0) Text(stringResource(R.string.attachment_copying), modifier = Modifier.padding(horizontal = 16.dp))
        if (localItems.any { it.state == "FAILED" }) Text(stringResource(R.string.attachment_failed_blocked), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        if (vm.importFailed) WarningCard(stringResource(R.string.share_some_failed), stringResource(R.string.attachment_exclude_failed), vm::dismissImportError)
        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        localItems.forEach { item ->
            val status = stringResource(when (item.state) {
                "AWAITING" -> R.string.attachment_awaiting
                "QUEUED" -> R.string.attachment_queued
                "PROCESSING" -> R.string.attachment_processing
                "READY" -> R.string.attachment_ready
                else -> R.string.attachment_failed
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${item.name}\n${item.mime} · ${android.text.format.Formatter.formatShortFileSize(ctx, item.size)} · $status" + (item.error?.let { ": $it" } ?: ""), Modifier.weight(1f), maxLines = 2)
                if (item.state == "FAILED" || item.state == "READY") androidx.compose.material3.TextButton(onClick = { vm.retryLocal(item.attachmentId) }) {
                    Text(stringResource(if (item.state == "READY") R.string.attachment_reprocess else R.string.attachment_retry))
                }
                IconButton(onClick = { vm.removeLocal(item.attachmentId) }) { Icon(Icons.Filled.Close, stringResource(R.string.chat_remove_attachment)) }
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Which tools the model may use in this session: a checklist that opens upwards from the composer.
            Box {
                var toolsOpen by remember { mutableStateOf(false) }
                val on = session?.enabledTools?.split(',')?.toSet().orEmpty()
                IconButton(onClick = { toolsOpen = true }) {
                    Icon(Icons.Filled.Tune, stringResource(R.string.chat_tools), tint = if (toolsOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                DropdownMenu(toolsOpen, { toolsOpen = false }) {
                    listOf(
                        ChatToolNames.SEARCH to R.string.chat_tool_search, ChatToolNames.READ to R.string.chat_tool_read,
                        ChatToolNames.NOTES to R.string.chat_tool_notes,
                        ChatToolNames.SCHEDULE to R.string.chat_tool_schedule, ChatToolNames.CALENDAR to R.string.chat_tool_actions,
                        ChatToolNames.MOVE to R.string.chat_tool_files,
                        ChatToolNames.GENERATE_IMAGE to R.string.chat_tool_generate_image,
                    ).forEach { (tool, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) }, onClick = { vm.toggleTool(tool) },
                            leadingIcon = { Checkbox(on.contains(tool), null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_auto_search)) }, onClick = { vm.toggleAutoSearch() },
                        leadingIcon = { Checkbox(session?.autoSearch == true, null) },
                    )
                }
            }
            IconButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.Image, stringResource(R.string.chat_attach_photo))
            }
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.AttachFile, stringResource(R.string.attachment_choose_files))
            }
            OutlinedTextField(
                vm.draft, { vm.draft = it }, Modifier.weight(1f).testTag("chat_input"), maxLines = 4,
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            )
            if (busy) {
                IconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, stringResource(R.string.chat_stop)) }
            } else {
                IconButton(onClick = vm::send, enabled = modelReady && io.github.taehunkim.malbotdari.attachments.AttachmentReadiness.canSend(activeImports, localItems.map { it.state }, vm.importFailed) && (vm.draft.isNotBlank() || vm.pending.isNotEmpty() || localPending.isNotEmpty())) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send))
                }
            }
        }
        if ((vm.pending.any { it is PendingAttachment.Photo || it is PendingAttachment.Item } || localPending.any { it.mime.startsWith("image/", ignoreCase = true) }) && !images) {
            Text(stringResource(R.string.chat_no_vision), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    selectedQuote?.let { quote ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selectedQuote = null },
            title = { Text(stringResource(R.string.share_selected_text)) },
            text = { Text(quote, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { selectedQuote = null }) { Text(stringResource(R.string.chat_dismiss)) } },
        )
    }
    if (renaming) RenameDialog(session?.title.orEmpty(), onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    if (tasksOpen) TasksSheet(vm) { tasksOpen = false }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, actions: Boolean = true) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val copied = stringResource(R.string.chat_copied)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) { androidx.compose.foundation.text.selection.SelectionContainer { io.github.taehunkim.malbotdari.ui.common.Markdown(text, Modifier.padding(12.dp)) } }
        if (actions && text.isNotBlank()) {
            // The Markdown source is copied/shared as is: the same syntax the model wrote.
            Row {
                IconButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("chat", text)))
                        // Android 13+ shows its own "copied" confirmation
                        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.size(32.dp)) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.chat_copy), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!mine) IconButton(onClick = { io.github.taehunkim.malbotdari.ui.common.shareText(ctx, text) }, Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Share, stringResource(R.string.chat_share), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** For every answer: the files that the search/read calls of its turn returned (see [ChatPreviews]). */
private fun previewsByMessage(messages: List<ChatMessageEntity>): Map<Long, List<PreviewHit>> {
    val out = HashMap<Long, List<PreviewHit>>()
    val turn = ArrayList<PreviewHit>()
    for (m in messages) {
        when {
            m.role == ChatRole.USER && m.taskId == null -> turn.clear()
            m.role == ChatRole.TOOL && m.actionId == null && m.taskId == null -> turn += hitsOf(m)
            m.role == ChatRole.ASSISTANT && turn.isNotEmpty() -> ChatPreviews.mentioned(m.text, turn).takeIf { it.isNotEmpty() }?.let { out[m.msgId] = it }
        }
    }
    return out
}

private fun hitsOf(m: ChatMessageEntity): List<PreviewHit> = runCatching {
    when (m.toolName) {
        ChatToolNames.SEARCH -> JSONArray(m.text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        ChatToolNames.READ -> listOf(JSONObject(m.text))
        else -> emptyList()
    }.filter { it.has("item_id") }.map { PreviewHit(it.getLong("item_id"), it.optString("name"), it.optString("kind") == "image", it.optString("kind")) }
}.getOrDefault(emptyList())

/** Previews (from Nunsori) of the files an answer is about. Tap opens the file through Nunsori. */
@Composable
private fun PreviewStrip(hits: List<PreviewHit>, onOpen: (Long) -> Unit) {
    if (hits.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hits.forEach { h ->
            Column(
                Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = stringResource(R.string.chat_preview, h.name)) { onOpen(h.itemId) },
            ) {
                io.github.taehunkim.malbotdari.ui.common.RemoteThumb(h.itemId, h.kind, Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
                Text(h.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MessageRow(sessionId: Long, m: ChatMessageEntity, names: Map<Long, String>, vm: ChatViewModel, previews: List<PreviewHit>, onOpenItem: (Long) -> Unit) {
    if (m.role != ChatRole.TOOL && m.taskId != null) {
        // a scheduled run: the instruction (right) and the answer (left), marked with a clock
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(R.string.task_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Bubble(m.text, mine = m.role == ChatRole.USER)
            PreviewStrip(previews, onOpenItem)
        }
        return
    }
    if (m.role == ChatRole.TOOL && m.actionId != null) {
        ActionCard(m.actionId, vm)
        return
    }
    if (m.role == ChatRole.TOOL && (m.toolName == ChatToolNames.SCHEDULE || m.toolName == ChatToolNames.UPDATE_TASK) && m.taskId != null) {
        TaskCard(m.taskId, vm)
        return
    }
    when (m.role) {
        ChatRole.USER -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChatViewModel.attachmentsOf(m).forEach { a ->
                if (a.quote != null) {
                    Bubble(a.quote, mine = true)
                } else if (a.photoFile != null) {
                    io.github.taehunkim.malbotdari.ui.common.LocalPhoto(File(androidx.compose.ui.platform.LocalContext.current.filesDir, "chat/$sessionId/${a.photoFile}"), Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)))
                } else if (a.localId != null) {
                    AssistChip(onClick = {}, label = { Text(a.label, maxLines = 1) })
                } else if (a.noteId != null) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    AssistChip(onClick = { runCatching { context.startActivity(io.github.taehunkim.jogakbo.sdk.Jogakbo.openNoteIntent(a.noteId)) } },
                        label = { Text(a.label.ifBlank { vm.noteLabels[a.noteId] ?: "Jogakbo #${a.noteId}" }) })
                } else if (a.itemId != null) {
                    AssistChip(onClick = { onOpenItem(a.itemId) }, label = { Text(names[a.itemId] ?: "#${a.itemId}", maxLines = 1) })
                }
            }
            if (m.text.isNotEmpty()) Bubble(m.text, mine = true)
        }
        ChatRole.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Bubble(m.text, mine = false)
            PreviewStrip(previews, onOpenItem)
        }
        ChatRole.TOOL -> ToolCard(m, onOpenItem)
    }
}

/** A tool call the model made: what it asked and, for searches, the files it found (tap to open). */
@Composable
private fun ToolCard(m: ChatMessageEntity, onOpenItem: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val args = remember(m.toolArgs) { runCatching { JSONObject(m.toolArgs ?: "{}") }.getOrDefault(JSONObject()) }
    val found = remember(m.text) {
        runCatching { JSONArray(m.text).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }.getOrDefault(emptyList())
    }
    val summary = when (m.toolName) {
        ChatToolNames.SEARCH -> stringResource(R.string.chat_tool_ran_search, args.optString("query"), found.size)
        ChatToolNames.READ -> stringResource(R.string.chat_tool_ran_read, runCatching { JSONObject(m.text).optString("name") }.getOrDefault("").ifEmpty { "#" + args.optLong("item_id") })
        ChatToolNames.NOTES -> stringResource(R.string.chat_tool_ran_notes, args.optString("query"), found.size)
        ChatToolNames.READ_NOTE -> stringResource(R.string.chat_tool_ran_read_note, runCatching { JSONObject(m.text).optString("title") }.getOrDefault("").ifEmpty { "#" + args.optLong("note_id") })
        ChatToolNames.GENERATE_IMAGE -> stringResource(R.string.chat_tool_ran_generate_image, args.optString("prompt"))
        else -> m.toolName.orEmpty()
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isNotes = m.toolName == ChatToolNames.NOTES || m.toolName == ChatToolNames.READ_NOTE
    val openNote: (Long) -> Unit = { id -> runCatching { ctx.startActivity(io.github.taehunkim.jogakbo.sdk.Jogakbo.openNoteIntent(id)) } }
    val noteRead = remember(m.text) { if (m.toolName == ChatToolNames.READ_NOTE) runCatching { JSONObject(m.text) }.getOrNull() else null }
    val genResult = remember(m.text) { if (m.toolName == ChatToolNames.GENERATE_IMAGE) runCatching { JSONObject(m.text) }.getOrNull() else null }
    val genImagePath = genResult?.optString("image_path")?.takeIf { it.isNotEmpty() }
    val genStatus = genResult?.optString("status").orEmpty()
    val isGenerating = m.toolName == ChatToolNames.GENERATE_IMAGE && genImagePath == null && genStatus == "generating"
    val genError = if (m.toolName == ChatToolNames.GENERATE_IMAGE && genImagePath == null && genStatus == "error") {
        genResult?.optString("message").orEmpty()
    } else null
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary, style = MaterialTheme.typography.labelMedium)
            if (isGenerating) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.chat_image_generating), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (genError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        genError == "cancelled" -> stringResource(R.string.chat_image_cancelled)
                        genError.isNotEmpty() -> stringResource(R.string.chat_image_failed_reason, genError)
                        else -> stringResource(R.string.chat_image_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            genImagePath?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    Spacer(Modifier.height(4.dp))
                    io.github.taehunkim.malbotdari.ui.common.LocalPhoto(
                        f,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
                        inSampleSize = 1,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        AssistChip(
                            onClick = {
                                val ok = saveImageToGallery(ctx, f)
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                            label = { Text(stringResource(R.string.chat_image_save)) },
                            leadingIcon = { Icon(Icons.Filled.Download, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            if (open) {
                noteRead?.let { o -> Text(o.optString("title"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { openNote(o.optLong("note_id")) }.padding(vertical = 2.dp)) }
                if (isNotes) found.forEach { o ->
                    Text(
                        o.optString("title") + (o.optString("created").takeIf { it.isNotEmpty() }?.let { " · ${it.take(10)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { openNote(o.optLong("note_id")) }.padding(vertical = 2.dp),
                    )
                } else found.forEach { o ->
                    Text(
                        "${o.optString("name")}${o.optString("page").takeIf { it.isNotEmpty() }?.let { " · p.$it" } ?: ""}${o.optString("time").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenItem(o.optLong("item_id")) }.padding(vertical = 2.dp),
                    )
                }
                if (found.isEmpty() && noteRead == null && m.toolName != ChatToolNames.GENERATE_IMAGE) Text(m.text.take(400), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun saveImageToGallery(context: Context, file: File): Boolean {
    return try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Malbotdari")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) {
        android.util.Log.e("ChatScreen", "Failed to save image to gallery", e)
        false
    }
}
