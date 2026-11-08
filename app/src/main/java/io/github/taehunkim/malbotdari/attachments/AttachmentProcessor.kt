package io.github.taehunkim.malbotdari.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.taehunkim.malbotdari.attachments.extract.AudioDecoder
import io.github.taehunkim.malbotdari.attachments.extract.OfficeParsers
import io.github.taehunkim.malbotdari.attachments.extract.PdfText
import io.github.taehunkim.malbotdari.attachments.extract.TextDecoder
import io.github.taehunkim.malbotdari.attachments.extract.ZipSource
import io.github.taehunkim.malbotdari.chat.Photos
import io.github.taehunkim.malbotdari.data.db.AttachmentChunkEntity
import io.github.taehunkim.malbotdari.data.db.AttachmentDao
import io.github.taehunkim.malbotdari.data.db.AttachmentEntity
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import io.github.taehunkim.ondolsaem.sdk.OndolSaemClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Resumable per-chat extraction. Originals stay private; processing can be retried after model setup. */
class AttachmentProcessor(private val context: Context, private val dao: AttachmentDao, private val client: OndolSaemClient, private val scope: CoroutineScope) {
    private val lock = Mutex()
    fun enqueue(session: Long, id: Long) { scope.launch { lock.withLock { process(session, id) } } }
    suspend fun resume() { dao.unfinished().forEach { enqueue(it.sessionId, it.attachmentId) } }

    private suspend fun process(session: Long, id: Long) = withContext(Dispatchers.IO) {
        val item = dao.get(id, session) ?: return@withContext
        if (item.state == "READY") return@withContext
        dao.setState(id, "PROCESSING")
        try {
            val file = File(context.filesDir, "chat/$session/${item.fileName}")
            require(file.isFile && file.parentFile?.canonicalFile == File(context.filesDir, "chat/$session").canonicalFile) { "Original file is missing" }
            val extracted = extract(item, file)
            val text = extracted.take(MAX_CHARS)
            require(text.isNotBlank() || item.mime.startsWith("image/")) { "No text was extracted" }
            val parts = text.chunked(CHUNK_CHARS)
            val embeddingLease = try { client.acquire(OndolSaem.Categories.EMBEDDING) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val chunks = try {
                parts.mapIndexed { i, part ->
                    val embedding = if (embeddingLease == null) null else try { client.embedDocument(part, item.name) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    AttachmentChunkEntity(attachmentId = id, ordinal = i, text = part,
                        embedding = embedding?.vector?.let { vector -> ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(vector) }.array() },
                        modelId = embedding?.let { "${it.modelId}:${it.modelVersion}" })
                }
            } finally { embeddingLease?.close() }
            if (dao.get(id, session) == null) return@withContext
            dao.clearChunks(id)
            dao.insertChunks(chunks)
            dao.setState(id, "READY", if (extracted.length > MAX_CHARS) "Extracted text was truncated at $MAX_CHARS characters" else null)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { dao.setState(id, "FAILED", (e.message ?: e.javaClass.simpleName).take(500)) }
    }

    private suspend fun extract(item: AttachmentEntity, file: File): String {
        val ext = item.name.substringAfterLast('.', "").lowercase()
        return when {
            item.mime.startsWith("image/") -> client.acquire(OndolSaem.Categories.OCR).use { client.recognizeText(Photos.jpeg(context, Uri.fromFile(file), 1600)) }
            item.mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "aac") -> audio(file)
            ext == "pdf" || item.mime == "application/pdf" -> pdf(file)
            ext in setOf("docx", "pptx", "xlsx", "odt", "odp", "ods") -> ZipSource.fromFile(file).use { zip ->
                (OfficeParsers.parse(ext, zip) ?: error("Unsupported document")).units.joinToString("\n\n") { unit -> (unit.label?.let { "$it\n" } ?: "") + unit.text }
            }
            ext in setOf("txt", "md", "csv", "tsv") || item.mime.startsWith("text/") -> {
                require(file.length() <= MAX_TEXT_BYTES) { "Text file is too large to extract" }
                TextDecoder.decode(file.readBytes()) ?: error("Not a text file")
            }
            else -> error("Unsupported file type")
        }
    }

    private suspend fun audio(file: File): String {
        val pcm = File.createTempFile("asr-", ".pcm", context.cacheDir)
        try {
            val durationMs = AudioDecoder.decode(context, Uri.fromFile(file), pcm)
            val out = StringBuilder()
            if (durationMs >= AudioDecoder.MAX_MS) out.append("[Audio processed up to the four-hour limit]\n")
            client.acquire(OndolSaem.Categories.ASR).use {
                client.transcribe(pcm, 0) { start, _, text -> if (out.length < MAX_CHARS) out.append("[").append(start / 1000).append("s] ").append(text).append('\n') }
            }
            return out.toString()
        } finally { pcm.delete() }
    }

    private suspend fun pdf(file: File): String {
        PDFBoxResourceLoader.init(context)
        val out = StringBuilder()
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val stripper = PDFTextStripper()
                    if (renderer.pageCount > 500) out.append("[Only the first 500 pages were processed]\n")
                    for (i in 0 until minOf(renderer.pageCount, 500)) {
                        if (out.length >= MAX_CHARS) break
                        stripper.startPage = i + 1; stripper.endPage = i + 1
                        var text = runCatching { stripper.getText(doc) }.getOrDefault("")
                        if (!PdfText.usable(text)) {
                            val jpeg = renderer.openPage(i).use { page ->
                                val height = (1200f * page.height / page.width).toInt().coerceIn(1, 2400)
                                val bitmap = Bitmap.createBitmap(1200, height, Bitmap.Config.ARGB_8888)
                                try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                                } finally { bitmap.recycle() }
                            }
                            text = client.acquire(OndolSaem.Categories.OCR).use { client.recognizeText(jpeg) }
                        }
                        out.append("[Page ").append(i + 1).append("]\n").append(text).append("\n\n")
                    }
                }
            }
        }
        return out.toString()
    }

    companion object { const val MAX_CHARS = 1_000_000; const val CHUNK_CHARS = 1200; const val MAX_TEXT_BYTES = 25L * 1024 * 1024 }
}
