package io.github.taehunkim.malbotdari

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.files.IndexState
import io.github.taehunkim.malbotdari.files.NunsoriBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Against the Nunsori installed on the device (skipped without it). A debug Nunsori and a debug Malbotdari share the
 * debug key, so Nunsori lets this app in without asking.
 */
@RunWith(AndroidJUnit4::class)
class NunsoriBridgeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun connectsListsToolsAndReadsTheChangeCursor() = runBlocking {
        val bridge = NunsoriBridge(ctx)
        assumeTrue("Nunsori is not installed", bridge.client.isInstalled())
        val s = bridge.refresh()
        assertEquals(IndexState.Status.CONNECTED, s.status)
        val names = bridge.tools().map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("search_index", "read_file", "move_files", "delete_files")))
        assertTrue("move and delete need the user's confirmation", bridge.tools().filter { it.confirm }.map { it.name }.toSet() == setOf("move_files", "delete_files"))
        val start = bridge.changesSince(0)
        assertTrue("cursor 0 hands out no files", start.ids.isEmpty())
        assertTrue("and nothing is newer than the current position", bridge.changesSince(start.cursor.coerceAtLeast(1)).ids.isEmpty() || start.cursor == 0L)
        bridge.client.disconnect()
    }
}
