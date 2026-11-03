package io.github.taehunkim.malbotdari.chat.actions

import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import io.github.taehunkim.malbotdari.data.db.ActionKind
import io.github.taehunkim.malbotdari.data.db.ActionStatus
import io.github.taehunkim.malbotdari.data.db.PendingActionDao
import io.github.taehunkim.malbotdari.data.db.PendingActionEntity
import org.json.JSONObject

/** What approving a card does: hand an intent to a system screen, or (later kinds) do the work itself. */
sealed interface ApprovalResult {
    /** The card's button launches [intent]; the action is then marked done. */
    data class Launch(val intent: Intent) : ApprovalResult
    data class Done(val note: String? = null) : ApprovalResult
    data class Failed(val reason: String) : ApprovalResult
}

/**
 * Proposals the model makes (calendar event, contact, ...) wait here as cards in the chat. Nothing happens until the user
 * approves the card; scheduled runs can only propose.
 */
class ActionManager(
    private val dao: PendingActionDao,
    /** Nunsori, which moves and deletes the files of a card the user approved. */
    private val files: io.github.taehunkim.malbotdari.files.FileIndex? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Stores a proposal for [sessionId] and returns the tool result the model sees. */
    suspend fun propose(sessionId: Long, kind: ActionKind, payload: JSONObject): String {
        val id = dao.insert(PendingActionEntity(sessionId = sessionId, kind = kind, status = ActionStatus.PROPOSED, payload = payload.toString(), createdAt = clock()))
        return JSONObject().put("action_id", id).put("status", "waiting_for_user_approval")
            .put("note", "A card with this was shown to the user, who decides. Tell them to approve it in the chat; do not say it is done.").toString()
    }

    /**
     * Approves a card. Calendar and contact cards return the intent to launch (the caller then calls [markDone]); file cards are
     * carried out here and marked done or failed. [permanent] chooses permanent deletion on a delete card.
     */
    suspend fun approve(actionId: Long, permanent: Boolean = false): ApprovalResult {
        val a = dao.get(actionId) ?: return ApprovalResult.Failed("gone")
        if (a.status != ActionStatus.PROPOSED) return ApprovalResult.Failed("handled")
        val o = runCatching { JSONObject(a.payload) }.getOrNull() ?: return ApprovalResult.Failed("bad payload")
        return when (a.kind) {
            ActionKind.CALENDAR_EVENT -> ApprovalResult.Launch(calendarIntent(CalendarEventDraft.fromJson(o)))
            ActionKind.CONTACT -> ApprovalResult.Launch(contactIntent(ContactDraft.fromJson(o)))
            ActionKind.MOVE_FILES, ActionKind.DELETE_FILES -> runFiles(a, o, permanent)
        }
    }

    private suspend fun runFiles(a: PendingActionEntity, o: JSONObject, permanent: Boolean): ApprovalResult {
        val index = files ?: return ApprovalResult.Failed("unavailable")
        val commit = runCatching { JSONObject(o.optString("commit")) }.getOrNull() ?: return ApprovalResult.Failed("bad payload")
        if (a.kind == ActionKind.DELETE_FILES && permanent) commit.put("permanent", true)
        val tool = o.optString("tool").ifEmpty { if (a.kind == ActionKind.MOVE_FILES) "move_files" else "delete_files" }
        val r = try { index.commit(tool, commit.toString()) } catch (e: Exception) {
            return ApprovalResult.Failed(NUNSORI_UNAVAILABLE) // stays PROPOSED: open Nunsori, then approve again
        }
        if (r.error == io.github.taehunkim.nunsori.sdk.Nunsori.Errors.NEEDS_FILE_ACCESS) return ApprovalResult.Failed(NEEDS_ACCESS)
        return if (r.ok) {
            dao.setStatus(a.actionId, ActionStatus.DONE, r.note); ApprovalResult.Done(r.note)
        } else {
            dao.setStatus(a.actionId, ActionStatus.FAILED, r.note); ApprovalResult.Failed(r.note)
        }
    }

    suspend fun markDone(actionId: Long, note: String? = null) {
        if (dao.get(actionId)?.status == ActionStatus.PROPOSED) dao.setStatus(actionId, ActionStatus.DONE, note)
    }

    suspend fun markFailed(actionId: Long, note: String?) {
        if (dao.get(actionId)?.status == ActionStatus.PROPOSED) dao.setStatus(actionId, ActionStatus.FAILED, note)
    }

    suspend fun dismiss(actionId: Long) {
        if (dao.get(actionId)?.status == ActionStatus.PROPOSED) dao.setStatus(actionId, ActionStatus.DISMISSED, null)
    }

    companion object {
        /** Nunsori needs "All files access" to move or delete: the card stays and the user is sent there. */
        const val NEEDS_ACCESS = "needs_all_files_access"
        /** Nunsori could not be reached: the card stays. */
        const val NUNSORI_UNAVAILABLE = "nunsori_unavailable"

        fun calendarIntent(e: CalendarEventDraft): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, e.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMs)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
            if (e.location.isNotEmpty()) putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
            if (e.description.isNotEmpty()) putExtra(CalendarContract.Events.DESCRIPTION, e.description)
        }

        fun contactIntent(c: ContactDraft): Intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (c.name.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NAME, c.name)
            if (c.phone.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.PHONE, c.phone)
            if (c.email.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.EMAIL, c.email)
            if (c.organization.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.COMPANY, c.organization)
            if (c.note.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NOTES, c.note)
        }
    }
}
