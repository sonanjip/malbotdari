package io.github.taehunkim.malbotdari.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Chats, their scheduled tasks and action cards. Files are Nunsori's: messages and tasks refer to them by Nunsori's
 * item ids only (no foreign keys), so a file deleted there simply stops resolving.
 */
@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, ScheduledTaskEntity::class, PendingActionEntity::class, AttachmentEntity::class, AttachmentChunkEntity::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): ScheduledTaskDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun actionDao(): PendingActionDao

    companion object {
        /** Bump together with a new migration in Migrations.kt (see docs/development.md). */
        const val VERSION = 2
    }
}
