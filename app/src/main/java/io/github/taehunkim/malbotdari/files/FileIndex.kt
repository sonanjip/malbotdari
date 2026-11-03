package io.github.taehunkim.malbotdari.files

import android.content.Context
import android.util.Log
import io.github.taehunkim.nunsori.sdk.Changes
import io.github.taehunkim.nunsori.sdk.CommitResult
import io.github.taehunkim.nunsori.sdk.ItemInfo
import io.github.taehunkim.nunsori.sdk.NunsoriClient
import io.github.taehunkim.nunsori.sdk.NunsoriTool
import io.github.taehunkim.nunsori.sdk.NunsoriUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's files as the chat sees them: Nunsori's index (search, read, move/delete with confirmation, new files).
 * An interface so tests can use a fake index without Nunsori installed.
 */
interface FileIndex {
    /** Tools Nunsori offers to the model (empty when Nunsori is missing or has not allowed this app). */
    suspend fun tools(): List<NunsoriTool>

    /** The tool's text result, or a [io.github.taehunkim.nunsori.sdk.Confirmation] for a move/delete. */
    suspend fun runTool(name: String, argsJson: String): Any
    suspend fun commit(name: String, payload: String): CommitResult
    suspend fun items(ids: List<Long>): List<ItemInfo>
    suspend fun thumbnail(id: Long, maxPx: Int): ByteArray?
    suspend fun readText(id: Long, maxChars: Int): String
    suspend fun changesSince(cursor: Long, limit: Int = 200): Changes
    suspend fun matchItems(query: String, ids: Collection<Long>, kinds: Collection<String>): List<Long>
    suspend fun people(): List<String>
}

/** Whether Nunsori can be used right now. */
data class IndexState(val status: Status, val version: String? = null) {
    enum class Status {
        UNKNOWN, NOT_INSTALLED,
        /** Nunsori is waiting for (or denied) the user's approval of this app (only for apps not signed like Nunsori). */
        NOT_APPROVED,
        /** Nunsori is older than this app needs (no index service): update Nunsori. */
        TOO_OLD,
        UNREACHABLE, CONNECTED,
    }
}

/** [FileIndex] through Nunsori's service. Calls fail with the SDK's exceptions; [state] follows what the last call saw. */
class NunsoriBridge(context: Context, val client: NunsoriClient = NunsoriClient(context)) : FileIndex {
    private val _state = MutableStateFlow(IndexState(IndexState.Status.UNKNOWN))
    val state: StateFlow<IndexState> = _state.asStateFlow()
    @Volatile private var cachedTools: List<NunsoriTool>? = null

    /** Connects and records the state. Never throws. */
    suspend fun refresh(): IndexState {
        val next = try {
            IndexState(IndexState.Status.CONNECTED, client.info().appVersion)
        } catch (e: NunsoriUnavailableException) {
            Log.i(TAG, "Nunsori unavailable: ${e.reason} ${e.message.orEmpty()}")
            IndexState(statusFor(e.reason))
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            IndexState(IndexState.Status.UNREACHABLE)
        }
        if (next.status != IndexState.Status.CONNECTED) cachedTools = null
        _state.value = next
        return next
    }

    private suspend fun <T> tracked(block: suspend () -> T): T = try {
        block().also { if (_state.value.status != IndexState.Status.CONNECTED) refresh() }
    } catch (e: NunsoriUnavailableException) {
        _state.value = IndexState(statusFor(e.reason)); cachedTools = null
        throw e
    }

    override suspend fun tools(): List<NunsoriTool> {
        cachedTools?.let { return it }
        return try { tracked { client.tools() }.also { cachedTools = it } } catch (e: Exception) { Log.i(TAG, "no tools: ${e.message}"); emptyList() }
    }

    override suspend fun runTool(name: String, argsJson: String): Any = tracked { client.runTool(name, argsJson) }
    override suspend fun commit(name: String, payload: String) = tracked { client.commit(name, payload) }
    override suspend fun items(ids: List<Long>) = if (ids.isEmpty()) emptyList() else tracked { client.items(ids) }
    override suspend fun thumbnail(id: Long, maxPx: Int) = tracked { client.thumbnail(id, maxPx) }
    override suspend fun readText(id: Long, maxChars: Int) = tracked { client.readText(id, maxChars) }
    override suspend fun changesSince(cursor: Long, limit: Int) = tracked { client.changesSince(cursor, emptyList(), limit) }
    override suspend fun matchItems(query: String, ids: Collection<Long>, kinds: Collection<String>) = tracked { client.matchItems(query, ids, kinds) }
    override suspend fun people() = tracked { client.people() }

    companion object {
        private const val TAG = "Malbot.Nunsori"
        const val INSTALL_URL = "https://github.com/TaeHunKim/Nunsori/releases"

        fun statusFor(reason: NunsoriUnavailableException.Reason): IndexState.Status = when (reason) {
            NunsoriUnavailableException.Reason.NOT_INSTALLED -> IndexState.Status.NOT_INSTALLED
            NunsoriUnavailableException.Reason.NOT_APPROVED -> IndexState.Status.NOT_APPROVED
            NunsoriUnavailableException.Reason.NOT_ALLOWED, NunsoriUnavailableException.Reason.HOST_TOO_OLD -> IndexState.Status.TOO_OLD
            NunsoriUnavailableException.Reason.CLIENT_TOO_OLD -> IndexState.Status.TOO_OLD
            NunsoriUnavailableException.Reason.NO_ANSWER -> IndexState.Status.UNREACHABLE
        }
    }
}
