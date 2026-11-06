package io.github.taehunkim.malbotdari.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val kChat = stringPreferencesKey("chat_model_id")
    private val kChatPrompt = stringPreferencesKey("chat_custom_prompt")
    private val kTaskJudgeFirst = booleanPreferencesKey("task_judge_first")
    private val kFileMatch = stringPreferencesKey("task_file_match")
    private val kUnsure = stringPreferencesKey("task_decision_unsure")
    private val kOnboarding = booleanPreferencesKey("onboarding_done")
    private val kIndexCursor = longPreferencesKey("nunsori_cursor")

    /** The chat model's id in OndolSaem (mirrored by ModelHost.refresh, for display). */
    val chatModelId: Flow<String> = context.dataStore.data.map { it[kChat] ?: DEFAULT_CHAT }
    suspend fun setChatModelId(id: String) { context.dataStore.edit { it[kChat] = id } }

    /** The user's own instructions, appended to the chat system prompt. */
    val chatCustomPrompt: Flow<String> = context.dataStore.data.map { it[kChatPrompt] ?: "" }
    suspend fun setChatCustomPrompt(s: String) { context.dataStore.edit { it[kChatPrompt] = s.trim().take(MAX_CUSTOM_PROMPT) } }

    /** Scheduled file tasks: let the chat model judge every new file (when there are few) instead of relying on the meaning match alone. */
    val taskJudgeFirst: Flow<Boolean> = context.dataStore.data.map { it[kTaskJudgeFirst] ?: true }
    suspend fun setTaskJudgeFirst(on: Boolean) { context.dataStore.edit { it[kTaskJudgeFirst] = on } }

    /** Scheduled file tasks: meaning match (embeddings) or OndolSaem's decision model. */
    val fileMatchMode: Flow<io.github.taehunkim.malbotdari.chat.schedule.FileMatchMode> = context.dataStore.data.map { p ->
        io.github.taehunkim.malbotdari.chat.schedule.FileMatchMode.entries.firstOrNull { it.name == p[kFileMatch] }
            ?: io.github.taehunkim.malbotdari.chat.schedule.FileMatchMode.EMBEDDING
    }
    suspend fun setFileMatchMode(m: io.github.taehunkim.malbotdari.chat.schedule.FileMatchMode) { context.dataStore.edit { it[kFileMatch] = m.name } }

    /** Decision mode: what happens to files the decision model is unsure about. */
    val decisionUnsure: Flow<io.github.taehunkim.malbotdari.chat.schedule.UnsureAction> = context.dataStore.data.map { p ->
        io.github.taehunkim.malbotdari.chat.schedule.UnsureAction.entries.firstOrNull { it.name == p[kUnsure] }
            ?: io.github.taehunkim.malbotdari.chat.schedule.UnsureAction.ASK_LLM
    }
    suspend fun setDecisionUnsure(a: io.github.taehunkim.malbotdari.chat.schedule.UnsureAction) { context.dataStore.edit { it[kUnsure] = a.name } }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[kOnboarding] ?: false }
    suspend fun setOnboardingDone(done: Boolean) { context.dataStore.edit { it[kOnboarding] = done } }

    /** Where Nunsori's "new files" stream was read up to (NunsoriClient.changesSince); 0 = not started. */
    val indexCursor: Flow<Long> = context.dataStore.data.map { it[kIndexCursor] ?: 0L }
    suspend fun setIndexCursor(c: Long) { context.dataStore.edit { it[kIndexCursor] = c } }

    companion object {
        const val DEFAULT_CHAT = "chat-gemma4-e2b"
        const val MAX_CUSTOM_PROMPT = 2000
    }
}
