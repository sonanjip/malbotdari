package io.github.taehunkim.malbotdari.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedDraftTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun processTextIsStoredAsEditableDraft() {
        val id = System.nanoTime()
        val result = SharedDraft.receive(context, id, Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "Selected text"))
        assertEquals("", result.text)
        assertEquals("Selected text", result.quote)
        assertEquals(result, SharedDraft.load(context, id))
        SharedDraft.clear(context, id)
        assertNull(SharedDraft.load(context, id))
    }

    @Test fun oversizedTextIsClippedAndReported() {
        val id = System.nanoTime()
        val result = SharedDraft.receive(context, id, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "x".repeat(SharedDraft.MAX_TEXT + 1)))
        assertEquals(SharedDraft.MAX_TEXT, result.text.length)
        assertTrue(result.rejected)
        SharedDraft.clear(context, id)
    }

    @Test fun fileUrisAreNeverImported() {
        val id = System.nanoTime()
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///etc/passwd"))
        val result = SharedDraft.receive(context, id, intent)
        assertTrue(result.rejected)
        assertTrue(result.images.isEmpty())
        SharedDraft.clear(context, id)
    }
}
