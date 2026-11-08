package io.github.taehunkim.malbotdari.sharing

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.MalbotdariApp
import io.github.taehunkim.malbotdari.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharePreparationTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun mixedShareShowsItemsAndDoesNotImportBeforeConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val c = (context as MalbotdariApp).container
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val types = listOf("text/plain", "image/jpeg", "application/zip")
        val uris = types.mapIndexed { i, mime -> context.contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, when (i) { 0 -> "plan.txt"; 1 -> "photo.jpg"; else -> "archive.zip" })
            put(MediaStore.Downloads.MIME_TYPE, mime)
        })!! }
        val caption = "  Shared caption\n" + "x".repeat(SharedDraft.MAX_TEXT + 1) + "  "
        val before = runBlocking { c.db.chatDao().observeSessions().first().map { it.sessionId }.toSet() }
        try {
            uris.forEach { uri -> context.contentResolver.openOutputStream(uri)!!.use { it.write("hello".toByteArray()) } }
            val intent = Intent(context, SharePreparationActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).putExtra(Intent.EXTRA_TEXT, caption)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<SharePreparationActivity>(intent).use {
                rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("share_item_2").assertIsDisplayed() }.isSuccess }
                rule.onNodeWithText("plan.txt").assertIsDisplayed()
                rule.onNodeWithText("text/plain · ${android.text.format.Formatter.formatShortFileSize(context, 5)}").assertIsDisplayed()
                rule.onNodeWithText("photo.jpg").assertIsDisplayed()
                rule.onNodeWithTag("share_select_1").performClick().assertIsOff()
                rule.onNodeWithText("archive.zip").assertIsDisplayed()
                rule.onNodeWithText("application/zip · ${android.text.format.Formatter.formatShortFileSize(context, 5)}").assertIsDisplayed()
                rule.onNodeWithText(context.getString(R.string.share_unsupported)).assertIsDisplayed()
                assertEquals(before, runBlocking { c.db.chatDao().observeSessions().first().map { it.sessionId }.toSet() })
                rule.onNodeWithTag("share_confirm").performClick()
                rule.waitUntil(15_000) { runBlocking { c.db.chatDao().observeSessions().first().any { it.sessionId !in before } } }
                val id = runBlocking { c.db.chatDao().observeSessions().first().first { it.sessionId !in before }.sessionId }
                rule.waitUntil(15_000) { runBlocking { c.db.attachmentDao().ofSession(id).isNotEmpty() } }
                assertEquals(caption, SharedDraft.load(context, id)?.text)
                assertEquals(1, runBlocking { c.db.attachmentDao().ofSession(id).size })
                runBlocking { c.chat.deleteSession(id) }
            }
        } finally { uris.forEach { context.contentResolver.delete(it, null, null) } }
    }
}
