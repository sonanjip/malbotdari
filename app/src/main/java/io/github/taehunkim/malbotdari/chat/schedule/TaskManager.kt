package io.github.taehunkim.malbotdari.chat.schedule

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import io.github.taehunkim.malbotdari.Notifications
import io.github.taehunkim.malbotdari.chat.ChatController
import io.github.taehunkim.malbotdari.data.db.ChatDao
import io.github.taehunkim.malbotdari.data.db.ChatMessageEntity
import io.github.taehunkim.malbotdari.data.db.ChatRole
import io.github.taehunkim.malbotdari.data.db.ScheduledTaskDao
import io.github.taehunkim.malbotdari.data.db.ScheduledTaskEntity
import io.github.taehunkim.malbotdari.data.db.TaskRepeat
import io.github.taehunkim.malbotdari.data.db.TaskResult
import io.github.taehunkim.malbotdari.data.db.TaskStatus
import io.github.taehunkim.malbotdari.data.db.TaskTrigger
import io.github.taehunkim.malbotdari.files.FileIndex
import io.github.taehunkim.ondolsaem.sdk.Decisions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Owns scheduled chat tasks: proposing (from the model), confirming (by the user), arming alarms, reacting to files
 * Nunsori newly indexed ([onIndexChanged]) and running a task in its chat. See docs/chat.md, "Scheduled tasks".
 */
class TaskManager(
    private val context: Context,
    private val tasks: ScheduledTaskDao,
    private val chat: ChatDao,
    /** Nunsori's index: new files, matching, their text. */
    private val files: FileIndex,
    private val alarms: TaskAlarms,
    private val chatController: () -> ChatController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batteryLow: () -> Boolean = { isBatteryLow(context) },
    private val notifier: (ScheduledTaskEntity, String, Boolean) -> Unit = { t, text, ok -> notifyResult(context, t, text, ok) },
    private val enqueue: (Long) -> Unit = { TaskRunWorker.enqueue(context, it) },
    /** Settings: let the chat model judge every new file when there are few (see [onIndexChanged]). */
    private val judgeFirst: suspend () -> Boolean = { true },
    /** Where Nunsori's "new files" stream was read up to (settings); 0 = not started. */
    private val cursor: suspend () -> Long = { 0L },
    private val setCursor: suspend (Long) -> Unit = {},
    /** Action cards a run left waiting for the user are counted in its notification. */
    private val actionDao: io.github.taehunkim.malbotdari.data.db.PendingActionDao? = null,
    /** Settings: how conditions are matched ([FileMatchMode]) and what happens to unsure decisions. */
    private val matchMode: suspend () -> FileMatchMode = { FileMatchMode.EMBEDDING },
    private val unsureAction: suspend () -> UnsureAction = { UnsureAction.ASK_LLM },
    /** OndolSaem's decision model: P(yes) per hypothesis, or null when it cannot be used. */
    private val decide: suspend (premise: String, hypotheses: List<String>) -> FloatArray? = { _, _ -> null },
) {
    // ---- proposing ----

    /** Handles a schedule_task call. Returns the JSON (or "Error: …") the model gets back. */
    suspend fun propose(sessionId: Long, args: JSONObject): String {
        val instruction = args.optString("instruction").trim()
        if (instruction.isEmpty()) return "Error: instruction is required."
        if (tasks.liveCount() >= Schedule.MAX_TASKS) return "Error: too many scheduled tasks (${Schedule.MAX_TASKS}); ask the user to delete some."
        if (tasks.liveCountOfSession(sessionId) >= Schedule.MAX_TASKS_PER_CHAT) return "Error: this chat already has ${Schedule.MAX_TASKS_PER_CHAT} scheduled tasks."
        val now = clock()
        val base = ScheduledTaskEntity(
            sessionId = sessionId, trigger = TaskTrigger.TIME, status = TaskStatus.PENDING_CONFIRM,
            instruction = instruction.take(MAX_INSTRUCTION), createdAt = now,
        )
        val task = when (args.optString("trigger").trim().lowercase()) {
            "time" -> timeTask(base, args, now)
            "file" -> fileTask(base, args)
            else -> "Error: trigger must be time or file."
        }
        if (task is String) return task
        val saved = (task as ScheduledTaskEntity).let { it.copy(taskId = tasks.insert(it)) }
        return JSONObject().put("task_id", saved.taskId).put("status", "waiting_for_user_confirmation")
            .put("summary", describe(saved)).put("note", "Tell the user to confirm the task card in the chat.").toString()
    }

    private fun timeTask(base: ScheduledTaskEntity, args: JSONObject, now: Long): Any {
        val zone = ZoneId.systemDefault()
        val repeat = when (args.optString("repeat").trim().lowercase()) {
            "once", "" -> TaskRepeat.ONCE
            "every_minutes", "minutes" -> TaskRepeat.EVERY_MINUTES
            "daily" -> TaskRepeat.DAILY
            "weekly" -> TaskRepeat.WEEKLY
            else -> return "Error: repeat must be once, every_minutes, daily or weekly."
        }
        var t = base.copy(repeat = repeat)
        val at = args.optString("at").trim().takeIf { it.isNotEmpty() }?.let { Schedule.parseTime(it) ?: return "Error: at must be HH:mm." }
        when (repeat) {
            TaskRepeat.ONCE -> {
                val inMinutes = args.optLong("in_minutes", 0)
                val whenMs = if (inMinutes > 0) {
                    now + inMinutes * 60_000L
                } else {
                    val minute = at ?: return "Error: give at (HH:mm) or in_minutes."
                    val date = args.optString("date").trim().takeIf { it.isNotEmpty() }?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull() ?: return "Error: date must be YYYY-MM-DD."
                    }
                    fun atDay(d: LocalDate) = d.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
                    val today = LocalDate.now(zone)
                    when {
                        date != null -> atDay(date)
                        atDay(today) > now -> atDay(today)
                        else -> atDay(today.plusDays(1))
                    }
                }
                if (whenMs <= now) return "Error: that time is in the past."
                t = t.copy(onceAtMs = whenMs)
            }
            TaskRepeat.EVERY_MINUTES -> {
                val every = args.optInt("every_minutes", 0)
                if (every < Schedule.MIN_INTERVAL_MINUTES) return "Error: every_minutes must be at least ${Schedule.MIN_INTERVAL_MINUTES}."
                t = t.copy(intervalMinutes = every)
            }
            TaskRepeat.DAILY -> t = t.copy(atMinuteOfDay = at ?: return "Error: at (HH:mm) is required.")
            TaskRepeat.WEEKLY -> {
                val mask = Schedule.parseWeekdays(args.optString("weekdays")) ?: return "Error: weekdays is required (mon,tue,…)."
                t = t.copy(atMinuteOfDay = at ?: return "Error: at (HH:mm) is required.", weekdayMask = mask)
            }
        }
        return t.copy(nextRunAt = Schedule.nextRun(t, now, zone) ?: return "Error: could not work out the next run time.")
    }

    private fun fileTask(base: ScheduledTaskEntity, args: JSONObject): Any {
        val kinds = parseKinds(args.optString("kinds"))
        val query = args.optString("query").trim().take(MAX_QUERY)
        if (query.isEmpty() && !args.optBoolean("any_file", false)) return NEEDS_QUERY
        return base.copy(
            trigger = TaskTrigger.FILE, repeat = TaskRepeat.ONCE,
            fileQuery = query, fileKinds = kinds.joinToString(","),
        )
    }

    /** Handles update_scheduled_task: changes only the given fields and applies at once (re-arming the alarm). */
    suspend fun update(sessionId: Long, args: JSONObject): String {
        val old = tasks.get(args.optLong("task_id", -1)) ?: return "Error: no scheduled task with that task_id."
        if (old.sessionId != sessionId) return "Error: no scheduled task #${old.taskId} in this chat."
        if (old.status == TaskStatus.DONE) return "Error: that task has already finished; schedule a new one."
        val now = clock()
        val base = old.copy(instruction = args.optString("instruction").trim().ifEmpty { old.instruction }.take(MAX_INSTRUCTION))
        val updated: ScheduledTaskEntity = if (old.trigger == TaskTrigger.FILE) {
            val newQuery = if (args.has("query")) args.optString("query").trim().take(MAX_QUERY) else old.fileQuery
            if (newQuery.isEmpty() && !args.optBoolean("any_file", false)) return NEEDS_QUERY
            base.copy(
                fileQuery = newQuery,
                fileKinds = if (args.has("kinds")) parseKinds(args.optString("kinds")).joinToString(",") else old.fileKinds,
            )
        } else if (SCHEDULE_KEYS.none { args.has(it) }) {
            base // only the instruction changed
        } else {
            val a = JSONObject(args.toString())
            if (!a.has("repeat")) a.put("repeat", old.repeat.name.lowercase())
            if (!a.has("at") && old.repeat != TaskRepeat.ONCE) a.put("at", "%02d:%02d".format(old.atMinuteOfDay / 60, old.atMinuteOfDay % 60))
            if (!a.has("weekdays") && old.weekdayMask != 0) {
                a.put("weekdays", java.time.DayOfWeek.entries.filter { old.weekdayMask and (1 shl (it.value - 1)) != 0 }.joinToString(",") { it.name.take(3).lowercase() })
            }
            if (!a.has("every_minutes") && old.intervalMinutes > 0) a.put("every_minutes", old.intervalMinutes)
            when (val r = timeTask(base, a, now)) {
                is String -> return r
                else -> (r as ScheduledTaskEntity).copy(nextRunAt = if (old.status == TaskStatus.ACTIVE || old.status == TaskStatus.PENDING_CONFIRM) r.nextRunAt else null)
            }
        }
        tasks.update(updated)
        alarms.cancel(updated.taskId)
        if (updated.status == TaskStatus.ACTIVE && updated.trigger == TaskTrigger.TIME) updated.nextRunAt?.let { alarms.arm(updated.taskId, it) }
        return JSONObject().put("task_id", updated.taskId).put("status", updated.status.name).put("summary", describe(updated)).toString()
    }

    // ---- user actions ----

    /** The user pressed Confirm on a card. Returns false when the task is gone or was already confirmed. */
    suspend fun confirm(taskId: Long): Boolean {
        val t = tasks.get(taskId) ?: return false
        if (t.status != TaskStatus.PENDING_CONFIRM) return false
        val now = clock()
        val next = if (t.trigger == TaskTrigger.TIME) t.onceAtMs?.takeIf { t.repeat == TaskRepeat.ONCE && it > now } ?: Schedule.nextRun(t, now) else null
        if (t.trigger == TaskTrigger.TIME && next == null) return false
        tasks.setStatus(taskId, TaskStatus.ACTIVE, now, next)
        if (next != null) alarms.arm(taskId, next)
        if (t.trigger == TaskTrigger.FILE) startWatching()
        return true
    }

    /** The first file task starts reading Nunsori's new files from now on (older files never trigger it). */
    private suspend fun startWatching() = changes.withLock {
        if (cursor() > 0) return@withLock
        runCatching { setCursor(files.changesSince(0).cursor.coerceAtLeast(1)) }.onFailure { Log.i(TAG, "Nunsori not reachable yet: ${it.message}") }
    }

    /** The user dismissed a card, or a task is deleted. */
    suspend fun delete(taskId: Long) {
        alarms.cancel(taskId)
        tasks.delete(taskId)
    }

    suspend fun setPaused(taskId: Long, paused: Boolean) {
        val t = tasks.get(taskId) ?: return
        if (paused) {
            if (t.status != TaskStatus.ACTIVE) return
            alarms.cancel(taskId)
            tasks.setStatus(taskId, TaskStatus.PAUSED, t.activatedAt, null)
        } else {
            if (t.status != TaskStatus.PAUSED) return
            val now = clock()
            val next = if (t.trigger == TaskTrigger.TIME) Schedule.nextRun(t, now) ?: t.onceAtMs?.takeIf { it > now } else null
            // a FILE task only sees files indexed after it was resumed
            tasks.setStatus(taskId, TaskStatus.ACTIVE, if (t.trigger == TaskTrigger.FILE) now else t.activatedAt, next)
            if (next != null) alarms.arm(taskId, next)
        }
    }

    suspend fun listText(sessionId: Long): String {
        val all = tasks.ofSession(sessionId)
        if (all.isEmpty()) return "No scheduled tasks in this chat."
        return all.joinToString("\n") { "#${it.taskId} [${it.status}] ${describe(it)}" + (it.lastResult?.let { r -> " (last run: $r)" } ?: "") }
    }

    suspend fun cancelText(sessionId: Long, taskId: Long): String {
        val t = tasks.get(taskId)
        if (t == null || t.sessionId != sessionId) return "Error: no scheduled task #$taskId in this chat."
        delete(taskId)
        return "Deleted task #$taskId."
    }

    // ---- alarms and boot ----

    /** Re-arms every active TIME task (app start, boot, time zone change, permission change); late runs catch up once. */
    suspend fun rearmAll() {
        val now = clock()
        for (t in tasks.active()) {
            if (t.trigger != TaskTrigger.TIME) continue
            val next = t.nextRunAt
            when {
                next == null -> rearmFresh(t, now)
                next > now -> alarms.arm(t.taskId, next)
                now - next <= Schedule.CATCH_UP_MS -> enqueue(t.taskId)
                else -> rearmFresh(t, now, skipped = true)
            }
        }
    }

    private suspend fun rearmFresh(t: ScheduledTaskEntity, now: Long, skipped: Boolean = false) {
        val next = Schedule.nextRun(t, now)
        if (skipped) tasks.setLastRun(t.taskId, now, TaskResult.SKIPPED, "missed")
        if (next == null) { tasks.setStatus(t.taskId, TaskStatus.DONE, t.activatedAt, null); return }
        tasks.setNextRun(t.taskId, next)
        alarms.arm(t.taskId, next)
    }

    // ---- file triggers ----

    private val changes = Mutex()

    /**
     * Nunsori said its index changed (or the app started): reads the files indexed since the stored cursor and queues
     * them on every FILE task they match. The cursor moves only after the tasks saw the files, so a crash re-reads them.
     */
    suspend fun onIndexChanged() = changes.withLock {
        val from = cursor()
        val active = tasks.activeFileTasks()
        if (from <= 0) {
            if (active.isNotEmpty()) runCatching { setCursor(files.changesSince(0).cursor.coerceAtLeast(1)) }
            return@withLock
        }
        var next = from
        val fresh = LinkedHashSet<Long>()
        for (page in 0 until MAX_PAGES) {
            val c = runCatching { files.changesSince(next, PAGE) }.getOrElse { Log.i(TAG, "Nunsori not reachable: ${it.message}"); return@withLock }
            fresh += c.ids; next = c.cursor
            if (c.ids.size < PAGE) break
        }
        if (fresh.isNotEmpty() && active.isNotEmpty()) {
            val kindOf = runCatching { files.items(fresh.toList()).associate { it.itemId to it.kind } }.getOrDefault(emptyMap())
            for (t in active) {
                runCatching {
                    val kinds = parseKinds(t.fileKinds)
                    val mine = fresh.filter { id -> kindOf[id]?.let { kinds.isEmpty() || it in kinds } == true }.toSet()
                    if (mine.isEmpty()) return@runCatching
                    // decision mode: every new file is a candidate; run() asks the decision model (or, without text, the meaning match)
                    val decision = t.fileQuery.isNotBlank() && matchMode() == FileMatchMode.DECISION
                    val strong = if (t.fileQuery.isBlank() || decision) mine else files.matchItems(t.fileQuery, mine, kinds).toSet()
                    // Abstract conditions ("anything with a schedule") do not embed well: with few new files, let the model
                    // judge all that have text; files without text still need their own meaning/word match.
                    val matched = if (!decision && t.fileQuery.isNotBlank() && mine.size <= JUDGE_FIRST_MAX && judgeFirst()) {
                        strong + mine.filter { files.readText(it, 1).isNotBlank() }
                    } else strong
                    if (matched.isEmpty()) return@runCatching
                    val merged = (parseIds(tasks.get(t.taskId)?.pendingItemIds.orEmpty()) + matched).distinct().takeLast(MAX_PENDING)
                    tasks.setPending(t.taskId, merged.joinToString(","))
                    enqueue(t.taskId)
                }.onFailure { Log.w(TAG, "file task ${t.taskId} failed to match", it) }
            }
        }
        if (next != from) setCursor(next)
    }

    // ---- running ----

    /** Runs one task now (from its alarm, a catch-up or a file match). */
    suspend fun run(taskId: Long) {
        val t = tasks.get(taskId) ?: return
        if (t.status != TaskStatus.ACTIVE) return
        val now = clock()
        val ids = if (t.trigger == TaskTrigger.FILE) parseIds(t.pendingItemIds) else emptyList()
        if (t.trigger == TaskTrigger.FILE && ids.isEmpty()) return

        // Plan the next fire before the (long) run so it cannot be delayed by it.
        var nextOnce: Long? = null
        if (t.trigger == TaskTrigger.TIME) {
            if (t.repeat == TaskRepeat.ONCE) {
                if (batteryLow()) {
                    nextOnce = now + RETRY_MS
                    tasks.setNextRun(taskId, nextOnce)
                    alarms.arm(taskId, nextOnce)
                    tasks.setLastRun(taskId, now, TaskResult.SKIPPED, "battery")
                    return
                }
            } else {
                val next = Schedule.nextRun(t, now)
                tasks.setNextRun(taskId, next)
                if (next != null) alarms.arm(taskId, next)
            }
        }
        if (batteryLow()) {
            tasks.setLastRun(taskId, now, TaskResult.SKIPPED, "battery")
            addNote(t, SKIPPED_BATTERY)
            return
        }

        // File tasks with a condition: let the chat model check the candidates; nothing is posted when none really matches.
        val confirmed = if (t.trigger == TaskTrigger.FILE && t.fileQuery.isNotBlank()) confirmFiles(t, ids) else ids
        if (t.trigger == TaskTrigger.FILE) {
            tasks.setPending(taskId, "")
            if (confirmed.isEmpty()) { Log.i(TAG, "task $taskId: ${ids.size} candidate(s), none matched the condition"); return }
        }
        val extra = if (t.trigger == TaskTrigger.FILE) filesBlock(confirmed) else ""
        val result = runCatching { chatController().runScheduled(t, extra) }.getOrElse {
            ChatController.TurnResult("", it.message ?: it.javaClass.simpleName)
        }
        val ok = result.error == null
        val waiting = runCatching { actionDao?.proposedSince(t.sessionId, now) ?: 0 }.getOrDefault(0)
        if (!ok && t.trigger == TaskTrigger.FILE) tasks.setPending(taskId, confirmed.joinToString(",")) // try these files again next time
        tasks.setLastRun(taskId, clock(), if (ok) TaskResult.OK else TaskResult.FAILED, result.error)
        if (t.trigger == TaskTrigger.TIME && t.repeat == TaskRepeat.ONCE) tasks.setStatus(taskId, TaskStatus.DONE, t.activatedAt, null)
        val text = result.answer.ifBlank { result.error ?: "" }
        notifier(t, if (waiting > 0) context.getString(io.github.taehunkim.malbotdari.R.string.notif_actions_waiting, waiting) + " " + text else text, ok)
    }

    /** The candidates of a file task that match its condition, by the match method in Settings. */
    private suspend fun confirmFiles(t: ScheduledTaskEntity, ids: List<Long>): List<Long> {
        val condition = alternatives(t.fileQuery).joinToString(" / ")
        val few = ids.size <= JUDGE_FIRST_MAX && judgeFirst()
        if (matchMode() != FileMatchMode.DECISION) return verify(condition, ids)
        val d = decideFiles(t, ids)
        if (d == null) {
            // no decision model: the candidates were not filtered yet, so do what the embedding mode does
            Log.i(TAG, "task ${t.taskId}: decision model not available, using the meaning match")
            val strong = runCatching { files.matchItems(t.fileQuery, ids.toSet(), parseKinds(t.fileKinds)).toSet() }.getOrDefault(ids.toSet())
            val cand = if (few) ids.filter { it in strong || runCatching { files.readText(it, 1) }.getOrDefault("").isNotBlank() } else ids.filter { it in strong }
            return if (cand.isEmpty()) cand else verify(condition, cand)
        }
        val unsure = unsureAction()
        // with few files and "let the assistant judge" on, the chat model checks every answer that was not a clear yes
        val ask = if (few) d.unsure + d.no else if (unsure == UnsureAction.ASK_LLM) d.unsure else emptyList()
        val kept = if (!few && unsure == UnsureAction.KEEP) d.unsure.toSet() else emptySet()
        val byLlm = if (ask.isEmpty()) emptySet() else verify(condition, ask).toSet()
        Log.i(TAG, "task ${t.taskId}: decisions yes ${d.yes.size}, unsure ${d.unsure.size}, no ${d.no.size}, no text ${d.noText} (meaning match ${d.byMeaning.size}), assistant ${byLlm.size}/${ask.size}")
        return ids.filter { it in d.yes || it in d.byMeaning || it in kept || it in byLlm }
    }

    private class Decided(val yes: Set<Long>, val unsure: List<Long>, val no: List<Long>, val byMeaning: Set<Long>, val noText: Int)

    /**
     * Asks the decision model about every candidate with text (file name + the start of its text, against one statement
     * per alternative of the query; the best one counts). Files without text go to the meaning match. Null when the
     * decision model cannot be used.
     */
    private suspend fun decideFiles(t: ScheduledTaskEntity, ids: List<Long>): Decided? {
        val hypotheses = alternatives(t.fileQuery).map { Decisions.hypothesis(it) }
        val names = runCatching { files.items(ids).associate { it.itemId to it.name } }.getOrDefault(emptyMap())
        val yes = HashSet<Long>(); val unsure = ArrayList<Long>(); val no = ArrayList<Long>(); val noText = ArrayList<Long>()
        for (id in ids) {
            val text = runCatching { files.readText(id, DECISION_EXCERPT) }.getOrDefault("").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) { noText += id; continue }
            val p = decide(names[id]?.let { "$it\n$text" } ?: text, hypotheses) ?: return null
            when (Decisions.verdict(p.maxOrNull() ?: 0f)) {
                Decisions.Verdict.YES -> yes += id
                Decisions.Verdict.UNSURE -> unsure += id
                Decisions.Verdict.NO -> no += id
            }
        }
        val byMeaning = if (noText.isEmpty()) emptySet() else
            runCatching { files.matchItems(t.fileQuery, noText.toSet(), parseKinds(t.fileKinds)).toSet() }.getOrDefault(emptySet())
        return Decided(yes, unsure, no, byMeaning, noText.size)
    }

    /**
     * The candidate files the chat model confirms for [condition]. Files without any indexed text (e.g. photos with no
     * recognized text) cannot be judged by words, so they pass on their semantic match alone. If the model cannot be
     * asked at all, every candidate is kept and the run itself reports the problem.
     */
    private suspend fun verify(condition: String, ids: List<Long>): List<Long> {
        val judged = ArrayList<Long>()
        val passThrough = ArrayList<Long>()
        val lines = StringBuilder()
        val names = runCatching { files.items(ids.take(MAX_JUDGED)).associate { it.itemId to it.name } }.getOrDefault(emptyMap())
        for (id in ids.take(MAX_JUDGED)) {
            val name = names[id] ?: continue
            val text = runCatching { files.readText(id, FILE_EXCERPT * 2) }.getOrDefault("").replace(Regex("\\s+"), " ").take(FILE_EXCERPT)
            if (text.isEmpty()) { passThrough += id; continue }
            judged += id
            lines.append("- item_id=$id $name: $text\n")
        }
        passThrough += ids.drop(MAX_JUDGED) // beyond the judged batch: keep, the run lists them as "more"
        if (judged.isEmpty()) return passThrough
        val reply = runCatching { chatController().judgeFiles(condition, lines.toString()) }.getOrNull() ?: return ids
        val said = Regex("\\d+").findAll(reply).mapNotNull { it.value.toLongOrNull() }.toSet()
        return judged.filter { it in said } + passThrough
    }

    private suspend fun addNote(t: ScheduledTaskEntity, text: String) {
        chat.insertMessage(ChatMessageEntity(sessionId = t.sessionId, role = ChatRole.ASSISTANT, text = text, taskId = t.taskId, createdAt = clock()))
    }

    private suspend fun filesBlock(ids: List<Long>): String {
        val sb = StringBuilder("[New files that triggered this task]\n")
        val infos = runCatching { files.items(ids.take(MAX_FILES_LISTED)) }.getOrDefault(emptyList())
        for (item in infos) {
            val text = runCatching { files.readText(item.itemId, FILE_EXCERPT * 2) }.getOrDefault("").replace(Regex("\\s+"), " ").take(FILE_EXCERPT)
            sb.append("- ${item.name} (item_id=${item.itemId}, ${item.kind}" + (item.date?.let { ", $it" } ?: "") + ")").append(if (text.isEmpty()) "" else ": $text").append('\n')
        }
        if (ids.size > MAX_FILES_LISTED) sb.append("(and ${ids.size - MAX_FILES_LISTED} more; use search_index to find them)\n")
        return sb.toString()
    }

    /** A one-line, English description for the model's tool result and for logs (the UI formats its own text). */
    fun describe(t: ScheduledTaskEntity): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val whenText = when (t.trigger) {
            TaskTrigger.FILE -> "whenever a new " + (t.fileKinds.lowercase().let { if (it.isEmpty()) "" else "$it " }) +
                (if (t.fileQuery.isEmpty()) "file is indexed" else "file matching \"${t.fileQuery}\" is indexed")
            TaskTrigger.TIME -> when (t.repeat) {
                TaskRepeat.ONCE -> "once at " + fmt.format(Date(t.onceAtMs ?: 0))
                TaskRepeat.EVERY_MINUTES -> "every ${t.intervalMinutes} minutes"
                TaskRepeat.DAILY -> "daily at %02d:%02d".format(t.atMinuteOfDay / 60, t.atMinuteOfDay % 60)
                TaskRepeat.WEEKLY -> "weekly (mask ${t.weekdayMask}) at %02d:%02d".format(t.atMinuteOfDay / 60, t.atMinuteOfDay % 60)
            }
        }
        return "$whenText: ${t.instruction}"
    }

    companion object {
        private const val TAG = "Malbot.Tasks"
        private const val MAX_INSTRUCTION = 500
        private const val NEEDS_QUERY = "Error: a file task needs a query with the key words of the files that should trigger it (for example \"receipt\"). " +
            "Only if the user explicitly wants every new file, set any_file=true."
        private val SCHEDULE_KEYS = listOf("repeat", "at", "weekdays", "every_minutes", "date", "in_minutes")
        private const val MAX_QUERY = 200
        private const val MAX_JUDGED = 20
        private const val JUDGE_FIRST_MAX = 20
        private const val MAX_PENDING = 200
        /** changesSince page size, and pages read per hint at most (the rest waits for the next hint). */
        private const val PAGE = 200
        private const val MAX_PAGES = 10
        private const val MAX_FILES_LISTED = 10
        private const val FILE_EXCERPT = 300
        /** Text the decision model reads per file (its window is a few hundred tokens). */
        private const val DECISION_EXCERPT = 1200
        private const val RETRY_MS = 30 * 60_000L
        private const val LOW_BATTERY_PERCENT = 15
        private const val SKIPPED_BATTERY = "Skipped this run because the battery is low."

        fun parseIds(s: String): List<Long> = s.split(',').mapNotNull { it.trim().toLongOrNull() }

        /** "a|b|c": the user's words and concrete synonyms the model listed (the same split Nunsori's matcher uses). */
        fun alternatives(query: String): List<String> = query.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)

        /** Kind names as Nunsori uses them (document, image, audio, video); synonyms are accepted. */
        fun parseKinds(s: String): Set<String> = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.mapNotNull { k ->
            when (k) {
                "document", "documents", "text", "doc", "docs" -> "document"
                "image", "images", "photo", "photos" -> "image"
                "audio" -> "audio"
                "video", "videos" -> "video"
                else -> null
            }
        }.toSet()

        fun isBatteryLow(context: Context): Boolean {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            return !charging && level >= 0 && level * 100 / scale < LOW_BATTERY_PERCENT
        }

        private fun notifyResult(context: Context, t: ScheduledTaskEntity, text: String, ok: Boolean) {
            runCatching { Notifications.taskResult(context, t.taskId, t.sessionId, text, ok) }
        }
    }
}
