package io.github.taehunkim.malbotdari

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.attachments.AttachmentTool
import io.github.taehunkim.malbotdari.data.db.AppDatabase
import io.github.taehunkim.malbotdari.data.db.AttachmentChunkEntity
import io.github.taehunkim.malbotdari.data.db.AttachmentEntity
import io.github.taehunkim.malbotdari.data.db.ChatSessionEntity
import io.github.taehunkim.ondolsaem.sdk.OndolSaemClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentToolsTest {
    @Test fun readRequiresSentAttachmentInTheSameChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val chats = db.chatDao()
            val first = chats.insertSession(ChatSessionEntity(title = "a", enabledTools = "", autoSearch = false, createdAt = 1, updatedAt = 1))
            val second = chats.insertSession(ChatSessionEntity(title = "b", enabledTools = "", autoSearch = false, createdAt = 1, updatedAt = 1))
            val dao = db.attachmentDao()
            val id = dao.insert(AttachmentEntity(sessionId = first, name = "notes.txt", mime = "text/plain", fileName = "original", size = 5, state = "READY"))
            dao.insertChunks(listOf(AttachmentChunkEntity(attachmentId = id, ordinal = 0, text = "hello")))
            val args = JSONObject().put("attachment_id", id)
            val host = OndolSaemClient(context)
            assertTrue(AttachmentTool(dao, host, first, false).execute(args).contains("not been sent"))
            dao.markSent(id, first)
            assertEquals("hello", JSONObject(AttachmentTool(dao, host, first, false).execute(args)).getString("text"))
            assertTrue(AttachmentTool(dao, host, second, false).execute(args).contains("no such attachment"))
            dao.delete(id, first)
            assertTrue(dao.chunks(id).isEmpty())
        } finally { db.close() }
    }
}
