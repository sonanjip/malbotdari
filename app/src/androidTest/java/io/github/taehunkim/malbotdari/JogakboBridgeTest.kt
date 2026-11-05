package io.github.taehunkim.malbotdari

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.notes.JogakboBridge
import io.github.taehunkim.malbotdari.notes.NotesState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Against the Jogakbo installed on the device (skipped without it). Debug builds share the debug key, so Jogakbo
 * lets this app in without asking.
 */
@RunWith(AndroidJUnit4::class)
class JogakboBridgeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun connectsListsToolsAndSearches() = runBlocking {
        val bridge = JogakboBridge(ctx)
        assumeTrue("Jogakbo is not installed", bridge.client.isInstalled())
        assertEquals(NotesState.Status.CONNECTED, bridge.refresh().status)
        assertEquals(setOf("search_notes", "read_note"), bridge.tools().map { it.name }.toSet())
        val out = bridge.runTool("search_notes", """{"query":"zzzz-nothing-matches-this"}""")
        assertTrue(out, out == "No matching notes." || out.startsWith("["))
        val start = bridge.client.changesSince(0)
        assertTrue(start.ids.isEmpty())
        bridge.client.disconnect()
    }
}
