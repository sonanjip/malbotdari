package io.github.taehunkim.malbotdari.chat

import io.github.taehunkim.malbotdari.chat.actions.ActionManager
import io.github.taehunkim.malbotdari.data.db.ActionKind
import io.github.taehunkim.malbotdari.files.FileIndex
import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import io.github.taehunkim.nunsori.sdk.Confirmation
import io.github.taehunkim.nunsori.sdk.NunsoriTool
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

object ChatToolNames {
    /** Nunsori's tools (names as Nunsori serves them). */
    const val SEARCH = "search_index"
    const val READ = "read_file"
    /** The tool switch that covers schedule_task, list_scheduled_tasks and cancel_scheduled_task. */
    const val SCHEDULE = "schedule_task"
    const val LIST_TASKS = "list_scheduled_tasks"
    const val CANCEL_TASK = "cancel_scheduled_task"
    const val UPDATE_TASK = "update_scheduled_task"
    /** add_calendar_event is the switch for the calendar and contact tools (action cards the user approves). */
    const val CALENDAR = "add_calendar_event"
    const val CONTACT = "add_contact"
    /** move_files is the switch for the move and delete tools (Nunsori prepares them, the user approves a card). */
    const val MOVE = "move_files"
    const val DELETE = "delete_files"
    val ALL = listOf(SEARCH, READ, SCHEDULE, CALENDAR, MOVE)
    /** Tools whose result carries an `action_id` (a card in the chat). */
    val ACTION_TOOLS = setOf(CALENDAR, CONTACT, MOVE, DELETE)

    /** The chip that switches a Nunsori tool on or off. */
    fun switchOf(tool: String): String = if (tool == DELETE) MOVE else tool
}

/**
 * One of Nunsori's tools, run in Nunsori. A tool that needs confirmation (move, delete) comes back as a
 * [Confirmation]: it becomes an action card in [sessionId]; Nunsori acts only after the user approves it.
 */
class NunsoriChatTool(
    private val spec: NunsoriTool,
    private val index: FileIndex,
    private val actions: ActionManager?,
    private val sessionId: Long,
) : ChatTool {
    override val name = spec.name
    override val description = spec.description
    override val parameters = spec.parameters

    override fun execute(args: JSONObject): String = runBlocking {
        val out = try { index.runTool(name, args.toString()) } catch (e: Exception) {
            return@runBlocking "Error: the file index (Nunsori) is not available: ${e.message ?: e.javaClass.simpleName}"
        }
        when (out) {
            is Confirmation -> {
                val a = actions ?: return@runBlocking "Error: actions are not available."
                val kind = if (name == ChatToolNames.DELETE) ActionKind.DELETE_FILES else ActionKind.MOVE_FILES
                a.propose(sessionId, kind, cardPayload(name, out))
            }
            else -> out.toString()
        }
    }

    companion object {
        /** What the card shows (files, destination) plus what Nunsori needs back ([Confirmation.payload]). */
        fun cardPayload(tool: String, c: Confirmation): JSONObject {
            val p = runCatching { JSONObject(c.payload) }.getOrDefault(JSONObject())
            val files = JSONArray(c.files.map { JSONObject().put("id", it.itemId).put("name", it.name).put("folder", it.folder) })
            return JSONObject().put("tool", tool).put("summary", c.summary).put("files", files).put("commit", c.payload)
                .put("destination", p.optString("destination")).put("permanent", p.optBoolean("permanent"))
        }
    }
}
