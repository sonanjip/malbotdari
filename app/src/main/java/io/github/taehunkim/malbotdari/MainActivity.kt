package io.github.taehunkim.malbotdari

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.taehunkim.malbotdari.ui.AppScaffold
import io.github.taehunkim.malbotdari.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** The chat to open (from a notification or another app's "Ask in chat"); consumed once the UI navigated there. */
    private val openChat = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { AppTheme { AppScaffold(openChat.collectAsStateWithLifecycle().value) { openChat.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Back from OndolSaem or Nunsori (a model downloaded, this app allowed): check again.
        val c = (application as MalbotdariApp).container
        lifecycleScope.launch { c.models.refresh(); c.files.refresh(); c.notes.refresh() }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(Notifications.EXTRA_SESSION, -1).takeIf { it > 0 }?.let { openChat.value = it; return }
        if (intent.action == ACTION_NEW_CHAT) {
            // Another app (Nunsori's Search) hands over files to ask about: a new chat with them attached.
            val ids = intent.getLongArrayExtra(EXTRA_ITEM_IDS)?.toList().orEmpty().take(MAX_ITEMS)
            val c = (application as MalbotdariApp).container
            lifecycleScope.launch {
                val id = c.chat.newSession()
                if (ids.isNotEmpty()) c.chat.queueAttachments(id, ids)
                openChat.value = id
            }
        }
    }

    companion object {
        /** Opens a new chat; extra [EXTRA_ITEM_IDS] (Nunsori item ids, long[]) attaches those files. */
        const val ACTION_NEW_CHAT = "io.github.taehunkim.malbotdari.action.NEW_CHAT"
        const val EXTRA_ITEM_IDS = "item_ids"
        private const val MAX_ITEMS = 10
    }
}
