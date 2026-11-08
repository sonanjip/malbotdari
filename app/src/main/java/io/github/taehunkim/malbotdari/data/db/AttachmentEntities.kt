package io.github.taehunkim.malbotdari.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Private original and durable processing state, owned by exactly one chat. */
@Entity(tableName = "attachments", foreignKeys = [ForeignKey(ChatSessionEntity::class, ["sessionId"], ["sessionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("sessionId")])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val attachmentId: Long = 0,
    val sessionId: Long,
    val name: String,
    val mime: String,
    val fileName: String,
    val size: Long,
    val state: String = "QUEUED",
    val error: String? = null,
    val sent: Boolean = false,
)

@Entity(tableName = "attachment_chunks", foreignKeys = [ForeignKey(AttachmentEntity::class, ["attachmentId"], ["attachmentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("attachmentId")])
data class AttachmentChunkEntity(
    @PrimaryKey(autoGenerate = true) val chunkId: Long = 0,
    val attachmentId: Long,
    val ordinal: Int,
    val text: String,
    val embedding: ByteArray? = null,
    val modelId: String? = null,
)

@Dao
interface AttachmentDao {
    @Insert suspend fun insert(item: AttachmentEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChunks(chunks: List<AttachmentChunkEntity>)
    @Query("SELECT * FROM attachments WHERE sessionId=:sessionId ORDER BY attachmentId") fun observeSession(sessionId: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE sessionId=:sessionId ORDER BY attachmentId") suspend fun ofSession(sessionId: Long): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE attachmentId=:id AND sessionId=:sessionId") suspend fun get(id: Long, sessionId: Long): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE state IN ('QUEUED','PROCESSING')") suspend fun unfinished(): List<AttachmentEntity>
    @Query("UPDATE attachments SET state=:state, error=:error WHERE attachmentId=:id") suspend fun setState(id: Long, state: String, error: String? = null)
    @Query("UPDATE attachments SET sent=1 WHERE attachmentId=:id AND sessionId=:sessionId") suspend fun markSent(id: Long, sessionId: Long)
    @Query("DELETE FROM attachments WHERE attachmentId=:id AND sessionId=:sessionId") suspend fun delete(id: Long, sessionId: Long)
    @Query("DELETE FROM attachment_chunks WHERE attachmentId=:id") suspend fun clearChunks(id: Long)
    @Query("SELECT * FROM attachment_chunks WHERE attachmentId=:id ORDER BY ordinal") suspend fun chunks(id: Long): List<AttachmentChunkEntity>
}
