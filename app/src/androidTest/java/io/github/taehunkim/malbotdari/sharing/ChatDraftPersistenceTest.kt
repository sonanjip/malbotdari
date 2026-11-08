package io.github.taehunkim.malbotdari.sharing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.MalbotdariApp
import io.github.taehunkim.malbotdari.chat.PendingAttachment
import io.github.taehunkim.malbotdari.ui.chat.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDraftPersistenceTest {
    @Test fun ordinaryChatRestoresTextAndPendingFileAndNoteReferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val c = (context as MalbotdariApp).container
        val id = c.chat.newSession()
        try {
            c.chat.queueAttachments(id, listOf(12, 34))
            c.chat.queueNotes(id, listOf(56))
            val original = ChatViewModel(c, id)
            original.draft = "Unsent ordinary chat"
            val restored = ChatViewModel(c, id)
            assertEquals("Unsent ordinary chat", restored.draft)
            assertEquals(listOf(12L, 34L), restored.pending.filterIsInstance<PendingAttachment.Item>().map { it.itemId })
            assertEquals(listOf(56L), restored.pending.filterIsInstance<PendingAttachment.Note>().map { it.noteId })
        } finally { c.chat.deleteSession(id) }
    }
}
