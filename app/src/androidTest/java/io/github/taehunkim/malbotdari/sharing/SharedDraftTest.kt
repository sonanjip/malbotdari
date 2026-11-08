package io.github.taehunkim.malbotdari.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedDraftTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun processTextIsStoredAsEditableDraft() = runBlocking {
        val id = System.nanoTime()
        val result = SharedDraft.receive(context, id, Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "Selected text"))
        assertEquals("", result.text)
        assertEquals("Selected text", result.quote)
        assertEquals(result, SharedDraft.load(context, id))
        SharedDraft.clear(context, id)
        assertNull(SharedDraft.load(context, id))
    }

    @Test fun oversizedTextIsClippedAndReported() = runBlocking {
        val id = System.nanoTime()
        val result = SharedDraft.receive(context, id, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "x".repeat(SharedDraft.MAX_TEXT + 1)))
        assertEquals(SharedDraft.MAX_TEXT, result.text.length)
        assertTrue(result.rejected)
        assertTrue(SharedDraft.load(context, id)!!.rejected)
        SharedDraft.clear(context, id)
    }

    @Test fun preservesSharedTextAndClipTextWithoutDuplicatingIt() = runBlocking {
        val id = System.nanoTime()
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Question")
        intent.clipData = android.content.ClipData.newPlainText("caption", "Question")
        assertEquals("Question", SharedDraft.receive(context, id, intent).text)
        intent.clipData = android.content.ClipData.newPlainText("caption", "More context")
        assertEquals("Question\nMore context", SharedDraft.receive(context, id, intent).text)
        SharedDraft.clear(context, id)
    }

    @Test fun importFailureFlagIsDurableWithoutDiscardingDraftText() {
        val id = System.nanoTime()
        try {
            SharedDraft.save(context, id, SharedDraft.Draft("Keep this question", emptyList()))
            SharedDraft.setRejected(context, id, true)
            assertEquals("Keep this question", SharedDraft.load(context, id)!!.text)
            assertTrue(SharedDraft.load(context, id)!!.rejected)
            SharedDraft.savePreservingRejection(context, id, SharedDraft.Draft("New text", emptyList()))
            assertTrue(SharedDraft.load(context, id)!!.rejected)
            assertEquals("New text", SharedDraft.load(context, id)!!.text)
            SharedDraft.setRejected(context, id, false)
            assertFalse(SharedDraft.load(context, id)!!.rejected)
        } finally { SharedDraft.clear(context, id) }
    }

    @Test fun ordinaryDraftReferencesSurviveReloadAndChangedDraftIsNotCleared() {
        val id = System.nanoTime()
        try {
            val before = SharedDraft.Draft("Unsent question", emptyList(), itemIds = listOf(12, 34), noteIds = listOf(56))
            SharedDraft.save(context, id, before)
            assertEquals(before, SharedDraft.load(context, id))
            val edited = before.copy(text = "Newer question")
            SharedDraft.save(context, id, edited)
            assertFalse(SharedDraft.clearIfUnchanged(context, id, before))
            assertEquals(edited, SharedDraft.load(context, id))
            assertTrue(SharedDraft.clearIfUnchanged(context, id, edited))
            assertNull(SharedDraft.load(context, id))
        } finally { SharedDraft.clear(context, id) }
    }

    @Test fun tracksOverlappingCopiesPerChat() {
        val id = System.nanoTime()
        SharedDraft.beginImport(id); SharedDraft.beginImport(id)
        try {
            assertEquals(2, SharedDraft.imports.value[id])
            SharedDraft.endImport(id)
            assertEquals(1, SharedDraft.imports.value[id])
        } finally { SharedDraft.endImport(id) }
        assertNull(SharedDraft.imports.value[id])
    }

    @Test fun sharedImageAndCaptionQueueAnAttachmentTogether() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, io.github.taehunkim.malbotdari.data.db.AppDatabase::class.java).build()
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "sharing-test.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg") })!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3)) }
            val session = db.chatDao().insertSession(io.github.taehunkim.malbotdari.data.db.ChatSessionEntity(title = "", enabledTools = "", autoSearch = false, createdAt = 1, updatedAt = 1))
            val queued = mutableListOf<Long>()
            val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, "Read this receipt")
            val result = SharedDraft.receive(context, session, intent, db.attachmentDao(), onImported = { queued += it })
            val item = db.attachmentDao().ofSession(session).single()
            assertEquals("Read this receipt", result.text)
            assertEquals("QUEUED", item.state)
            assertEquals(listOf(item.attachmentId), queued)
            assertEquals(3L, item.size)
            java.io.File(context.filesDir, "chat/$session").deleteRecursively()
            SharedDraft.clear(context, session)
        } finally { context.contentResolver.delete(uri, null, null); db.close() }
    }

    @Test fun mixedShareKeepsNamesTypesAndSizesAndAllowsExclusion() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, io.github.taehunkim.malbotdari.data.db.AppDatabase::class.java).build()
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val types = listOf("image/jpeg", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        val uris = types.mapIndexed { index, mime ->
            context.contentResolver.insert(collection, android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, if (index == 0) "image.jpg" else "document")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            })!!
        }
        try {
            uris.forEachIndexed { index, uri -> context.contentResolver.openOutputStream(uri)!!.use { it.write(ByteArray(index + 3)) } }
            val session = db.chatDao().insertSession(io.github.taehunkim.malbotdari.data.db.ChatSessionEntity(title = "", enabledTools = "", autoSearch = false, createdAt = 1, updatedAt = 1))
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            val result = SharedDraft.receive(context, session, intent, db.attachmentDao())
            assertFalse(result.rejected)
            val items = db.attachmentDao().ofSession(session)
            assertEquals(2, items.size)
            assertEquals(types, items.map { it.mime })
            assertEquals(listOf(3L, 4L), items.map { it.size })
            assertTrue(items.all { it.name.isNotBlank() })
            db.attachmentDao().delete(items[0].attachmentId, session)
            assertEquals(listOf(items[1].attachmentId), db.attachmentDao().ofSession(session).map { it.attachmentId })
            java.io.File(context.filesDir, "chat/$session").deleteRecursively()
            SharedDraft.clear(context, session)
        } finally { uris.forEach { context.contentResolver.delete(it, null, null) }; db.close() }
    }

    @Test fun fileUrisAreNeverImported() = runBlocking {
        val id = System.nanoTime()
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///etc/passwd"))
        val result = SharedDraft.receive(context, id, intent)
        assertTrue(result.rejected)
        assertTrue(result.images.isEmpty())
        SharedDraft.clear(context, id)
    }
}
