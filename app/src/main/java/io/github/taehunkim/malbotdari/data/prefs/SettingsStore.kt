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
