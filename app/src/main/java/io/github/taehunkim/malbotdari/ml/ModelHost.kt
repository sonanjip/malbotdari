package io.github.taehunkim.malbotdari.ml

import android.content.Context
import android.util.Log
import io.github.taehunkim.malbotdari.data.prefs.SettingsStore
import io.github.taehunkim.malbotdari.ml.llm.ChatEvent
import io.github.taehunkim.malbotdari.ml.llm.ChatModel
import io.github.taehunkim.malbotdari.ml.llm.ChatTool
import io.github.taehunkim.malbotdari.ml.llm.ChatTurn
import io.github.taehunkim.malbotdari.ml.llm.TurnRole
import io.github.taehunkim.ondolsaem.ModelInfo
import io.github.taehunkim.ondolsaem.sdk.ChatStreamEvent
import io.github.taehunkim.ondolsaem.sdk.HistoryTurn
import io.github.taehunkim.ondolsaem.sdk.HostCallException
import io.github.taehunkim.ondolsaem.sdk.HostLease
import io.github.taehunkim.ondolsaem.sdk.HostUnavailableException
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories
import io.github.taehunkim.ondolsaem.sdk.OndolSaemClient
import io.github.taehunkim.ondolsaem.sdk.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A borrowed resource. Closing it returns the resource; the resource itself is NOT closed. */
class Lease<T>(val value: T, private val onClose: () -> Unit) : AutoCloseable {
    private var closed = false
    override fun close() { synchronized(this) { if (closed) return; closed = true }; onClose() }
}

/** What the OndolSaem model host offers right now. */
data class HostState(
    val status: Status,
    val models: Map<String, ModelInfo> = emptyMap(),
    val hostVersion: String? = null,
    val hostApi: Int = 0,
) {
    enum class Status {
        UNKNOWN, NOT_INSTALLED, NOT_ALLOWED, UNREACHABLE, CONNECTED,
        /** Signed with a key OndolSaem does not know: waiting for (or denied) the user's approval in OndolSaem. */
        NOT_APPROVED,
        HOST_TOO_OLD,
        CLIENT_TOO_OLD,
    }

    fun ready(category: String) = models[category]?.ready == true
    fun model(category: String): ModelInfo? = models[category]?.takeIf { it.id.isNotEmpty() }
}

/**
 * The chat model, which runs in the OndolSaem app (download, loading, memory). Hands out [Lease]s on a remote
 * [ChatModel]; the chat model's id is mirrored into [SettingsStore] for display.
 */
class ModelHost(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    val client: OndolSaemClient = OndolSaemClient(context),
) {
    private val _state = MutableStateFlow(HostState(HostState.Status.UNKNOWN))
    val state: StateFlow<HostState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    /** For the resource indicator: "OndolSaem" while bound, else null. */
    val chatBackend: Flow<String?> = client.connected.map { if (it) BACKEND else null }

    /** Test hooks: when set, used instead of the host (instrumented tests run without OndolSaem). */
    var chatOverride: (suspend () -> ChatModel?)? = null
    var contextTokensOverride: Int? = null

    /** Context window of the chat model as set in OndolSaem (prompts are trimmed to it). */
    fun chatContextTokens(): Int = contextTokensOverride
        ?: _state.value.model(Categories.LLM)?.contextTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS

    init {
        scope.launch { client.modelChanges.collect { refresh() } }
    }

    /** Asks the host for its models and mirrors the chat model's id into the settings. Never throws. */
    suspend fun refresh(): HostState = refreshLock.withLock {
        val next = try {
            val models = client.categories().associateBy { it.category }
            val info = client.hostInfo()
            HostState(HostState.Status.CONNECTED, models, info.appVersion, info.apiVersion)
        } catch (e: HostUnavailableException) {
            Log.i(TAG, "OndolSaem unavailable: ${e.reason} ${e.message.orEmpty()}")
            HostState(statusFor(e.reason), _state.value.models)
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            HostState(HostState.Status.UNREACHABLE, _state.value.models)
        }
        if (next.status == HostState.Status.CONNECTED) {
            next.models[Categories.LLM]?.let { if (settings.chatModelId.first() != it.id) settings.setChatModelId(it.id) }
        }
        _state.value = next
        next
    }

    private suspend fun known(category: String): ModelInfo? {
        val s = _state.value
        return if (s.status == HostState.Status.CONNECTED) s.model(category) else refresh().model(category)
    }

    /**
     * OndolSaem's decision model: P(yes) per hypothesis for [premise], or null when it cannot be used (host older
     * than API 3, model not downloaded, host unreachable).
     */
    suspend fun entail(premise: String, hypotheses: List<String>): FloatArray? {
        decideOverride?.let { return it(premise, hypotheses) }
        return try {
            if (!client.supports(OndolSaem.Api.V3)) null else client.entail(premise, hypotheses, OndolSaem.PRIORITY_BACKGROUND)
        } catch (e: HostCallException) {
            Log.i(TAG, "decision failed: ${e.code} ${e.message}"); null
        } catch (e: HostUnavailableException) {
            Log.i(TAG, "decision unavailable: ${e.reason}"); null
        }
    }

    /** Test hook for [entail]. */
    var decideOverride: (suspend (String, List<String>) -> FloatArray?)? = null

    private suspend fun hostLease(category: String): HostLease? = try {
        client.acquire(category)
    } catch (e: HostCallException) {
        if (e.code == OndolSaem.Errors.MODEL_NOT_READY) { refresh(); null } else throw e
    } catch (e: HostUnavailableException) {
        refresh(); null
    }

    /** Null when the host or its chat model is missing. Loading the model can take a while (the call waits). */
    suspend fun chat(): Lease<ChatModel>? {
        chatOverride?.let { f -> return f()?.let { m -> Lease(m) { } } }
        val lease = hostLease(Categories.LLM) ?: return null
        val info = known(Categories.LLM)
        if (info == null || !info.ready) { lease.close(); return null }
        return Lease(RemoteChatModel(client, info, scope)) { lease.close() }
    }

    /** The chat history was edited: drop the host's cached conversation. */
    suspend fun closeChat() { runCatching { client.resetChat() } }

    companion object {
        private const val TAG = "Malbot.ModelHost"
        const val BACKEND = "OndolSaem"
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val INSTALL_URL = "https://github.com/TaeHunKim/ondolsaem/releases"
        const val APP_RELEASES_URL = "https://github.com/TaeHunKim/malbotdari/releases"

        fun statusFor(reason: HostUnavailableException.Reason): HostState.Status = when (reason) {
            HostUnavailableException.Reason.NOT_INSTALLED -> HostState.Status.NOT_INSTALLED
            HostUnavailableException.Reason.NOT_ALLOWED -> HostState.Status.NOT_ALLOWED
            HostUnavailableException.Reason.NOT_APPROVED -> HostState.Status.NOT_APPROVED
            HostUnavailableException.Reason.NO_ANSWER -> HostState.Status.UNREACHABLE
            HostUnavailableException.Reason.HOST_TOO_OLD -> HostState.Status.HOST_TOO_OLD
            HostUnavailableException.Reason.CLIENT_TOO_OLD -> HostState.Status.CLIENT_TOO_OLD
        }
    }
}

private class RemoteChatModel(private val client: OndolSaemClient, info: ModelInfo, private val scope: CoroutineScope) : ChatModel {
    override val supportsImages: Boolean = info.vision
    override val backend: String = ModelHost.BACKEND

    override fun reply(conversationKey: String, system: String, history: List<ChatTurn>, user: ChatTurn, tools: List<ChatTool>): Flow<ChatEvent> {
        val byName = tools.associateBy { it.name }
        return client.chat(
            conversationKey, system,
            history.map { HistoryTurn(it.role == TurnRole.USER, it.text) },
            user.text, if (supportsImages) user.images else emptyList(),
            tools.map { ToolSpec(it.name, it.description, it.parameters) },
        ) { name, args ->
            val tool = byName[name] ?: return@chat "Error: unknown tool $name"
            tool.execute(runCatching { JSONObject(args) }.getOrElse { JSONObject() })
        }.map { ev ->
            when (ev) {
                is ChatStreamEvent.Token -> ChatEvent.Token(ev.text)
                is ChatStreamEvent.ToolRan -> ChatEvent.ToolRan(ev.name, ev.argsJson, ev.result)
            }
        }
    }

    override fun reset() { scope.launch { runCatching { client.resetChat() } } }
    override fun close() {}
}
