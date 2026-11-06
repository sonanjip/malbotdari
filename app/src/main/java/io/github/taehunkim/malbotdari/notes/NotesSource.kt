package io.github.taehunkim.malbotdari.notes

import android.content.Context
import android.util.Log
import io.github.taehunkim.jogakbo.sdk.JogakboClient
import io.github.taehunkim.jogakbo.sdk.JogakboTool
import io.github.taehunkim.jogakbo.sdk.JogakboUnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's notes as the chat sees them: Jogakbo's search and read tools. An interface so tests can use a fake. */
interface NotesSource {
    /** Tools Jogakbo offers to the model (empty when Jogakbo is missing or has not allowed this app). */
    suspend fun tools(): List<JogakboTool>
    suspend fun runTool(name: String, argsJson: String): String
}

/** Whether Jogakbo can be used right now. */
data class NotesState(val status: Status, val version: String? = null) {
    enum class Status { UNKNOWN, NOT_INSTALLED, NOT_APPROVED, TOO_OLD, UNREACHABLE, CONNECTED }
}

/** [NotesSource] through Jogakbo's service. [state] follows what the last call saw. */
class JogakboBridge(context: Context, val client: JogakboClient = JogakboClient(context)) : NotesSource {
    private val _state = MutableStateFlow(NotesState(NotesState.Status.UNKNOWN))
    val state: StateFlow<NotesState> = _state.asStateFlow()
    @Volatile private var cachedTools: List<JogakboTool>? = null

    /** Connects and records the state. Never throws. */
    suspend fun refresh(): NotesState {
        val next = try {
            NotesState(NotesState.Status.CONNECTED, client.info().appVersion)
        } catch (e: JogakboUnavailableException) {
            Log.i(TAG, "Jogakbo unavailable: ${e.reason} ${e.message.orEmpty()}")
            NotesState(statusFor(e.reason))
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed", e)
            NotesState(NotesState.Status.UNREACHABLE)
        }
        if (next.status != NotesState.Status.CONNECTED) cachedTools = null
        _state.value = next
        return next
    }

    private suspend fun <T> tracked(block: suspend () -> T): T = try {
        block().also { if (_state.value.status != NotesState.Status.CONNECTED) refresh() }
    } catch (e: JogakboUnavailableException) {
        _state.value = NotesState(statusFor(e.reason)); cachedTools = null
        throw e
    }

    override suspend fun tools(): List<JogakboTool> {
        cachedTools?.let { return it }
        return try { tracked { client.tools() }.also { cachedTools = it } } catch (e: Exception) { Log.i(TAG, "no tools: ${e.message}"); emptyList() }
    }

    override suspend fun runTool(name: String, argsJson: String): String = tracked { client.runTool(name, argsJson) }

    companion object {
        private const val TAG = "Malbot.Jogakbo"
        const val INSTALL_URL = "https://github.com/sonanjip/jogakbo/releases"

        fun statusFor(reason: JogakboUnavailableException.Reason): NotesState.Status = when (reason) {
            JogakboUnavailableException.Reason.NOT_INSTALLED -> NotesState.Status.NOT_INSTALLED
            JogakboUnavailableException.Reason.NOT_APPROVED -> NotesState.Status.NOT_APPROVED
            JogakboUnavailableException.Reason.NOT_ALLOWED, JogakboUnavailableException.Reason.HOST_TOO_OLD,
            JogakboUnavailableException.Reason.CLIENT_TOO_OLD -> NotesState.Status.TOO_OLD
            JogakboUnavailableException.Reason.NO_ANSWER -> NotesState.Status.UNREACHABLE
        }
    }
}
