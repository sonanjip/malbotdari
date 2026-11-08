package io.github.taehunkim.malbotdari

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taehunkim.malbotdari.data.db.AppDatabase
import io.github.taehunkim.malbotdari.data.db.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Test fun migrationPreservesChatsAndCascadesAttachments() {
        helper.createDatabase("attachments-migration", 1).apply {
            execSQL("INSERT INTO chat_sessions (sessionId,title,enabledTools,autoSearch,createdAt,updatedAt,pinned) VALUES (1,'existing','',0,1,1,0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("attachments-migration", 2, true, MIGRATION_1_2)
        db.query("SELECT title FROM chat_sessions WHERE sessionId=1").use { it.moveToFirst(); assertEquals("existing", it.getString(0)) }
        db.execSQL("INSERT INTO attachments (attachmentId,sessionId,name,mime,fileName,size,state,sent) VALUES (1,1,'a','text/plain','a',1,'QUEUED',0)")
        db.execSQL("INSERT INTO attachment_chunks (attachmentId,ordinal,text) VALUES (1,0,'content')")
        db.execSQL("DELETE FROM attachments WHERE attachmentId=1")
        db.query("SELECT count(*) FROM attachment_chunks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }
}
