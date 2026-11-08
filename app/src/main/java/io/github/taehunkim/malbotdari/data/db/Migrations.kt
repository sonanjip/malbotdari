package io.github.taehunkim.malbotdari.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`attachmentId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `fileName` TEXT NOT NULL, `size` INTEGER NOT NULL, `state` TEXT NOT NULL, `error` TEXT, `sent` INTEGER NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_sessionId` ON `attachments` (`sessionId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `attachment_chunks` (`chunkId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attachmentId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `text` TEXT NOT NULL, `embedding` BLOB, `modelId` TEXT, FOREIGN KEY(`attachmentId`) REFERENCES `attachments`(`attachmentId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachment_chunks_attachmentId` ON `attachment_chunks` (`attachmentId`)")
    }
}
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
