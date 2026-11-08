package io.github.taehunkim.malbotdari.sharing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.MainActivity
import io.github.taehunkim.malbotdari.MalbotdariApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessTextAliasTest {
    @Test fun aliasLaunchesMainActivityAndCreatesQuotedDraft() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MalbotdariApp
        val container = app.container
        val before = container.db.chatDao().observeSessions().first().map { it.sessionId }.toSet()
        val quote = "selected-${System.nanoTime()}"
        val alias = ComponentName(context, "${context.packageName}.ProcessTextActivity")
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(alias)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, quote).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = context.packageManager.getActivityInfo(alias, 0)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
        val matches = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(context.packageName), 0)
        assertTrue(matches.any { it.activityInfo.name == alias.className })
        var created: Long? = null
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity -> assertEquals(Intent.ACTION_PROCESS_TEXT, activity.intent.action) }
                created = withTimeout(15_000) {
                    while (true) {
                        val ids = container.db.chatDao().observeSessions().first().map { it.sessionId }.filter { it !in before }
                        val found = ids.firstOrNull { SharedDraft.load(context, it)?.quote == quote }
                        if (found != null) return@withTimeout found
                        delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                assertEquals(quote, SharedDraft.load(context, created!!)?.quote)
                assertTrue(container.db.chatDao().messages(created!!).isEmpty())
            }
        } finally { created?.let { container.chat.deleteSession(it) } }
    }
}
