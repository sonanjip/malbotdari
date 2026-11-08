package io.github.taehunkim.malbotdari.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.taehunkim.malbotdari.attachments.BoundedImport
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A private, durable draft created from an Android share. Originals are copied while the URI grant is valid. */
object SharedDraft {
    const val MAX_TEXT = 30000
    private const val MAX_IMAGES = 2
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    data class Draft(val text: String, val images: List<String>, val quote: String = "", val rejected: Boolean = false)

    fun receive(context: Context, sessionId: Long, intent: Intent): Draft {
        val raw = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }?.toString().orEmpty()
        var rejected = raw.length > MAX_TEXT
        val uris = streams(intent)
        val dir = File(context.filesDir, "chat/$sessionId").also { it.mkdirs() }
        val images = mutableListOf<String>()
        for (uri in uris) {
            if (images.size >= MAX_IMAGES || (runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: intent.type)?.startsWith("image/") != true) {
                rejected = true
                continue
            }
            val file = File(dir, "incoming-${UUID.randomUUID()}")
            val ok = runCatching {
                require(uri.scheme == "content")
                context.contentResolver.openInputStream(uri)!!.use { BoundedImport.copy(it, file, MAX_IMAGE_BYTES) }
            }.isSuccess
            if (ok) images += file.name else rejected = true
        }
        return Draft(if (intent.action == Intent.ACTION_PROCESS_TEXT) "" else raw.take(MAX_TEXT), images, if (intent.action == Intent.ACTION_PROCESS_TEXT) raw.take(MAX_TEXT) else "", rejected).also { save(context, sessionId, it) }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val extra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty() else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> emptyList()
        }
        val clip = intent.clipData
        return (extra + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }).distinct()
    }

    fun save(context: Context, id: Long, draft: Draft) {
        val value = JSONObject().put("text", draft.text).put("images", JSONArray(draft.images)).put("quote", draft.quote).toString()
        context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).edit().putString(id.toString(), value).apply()
    }

    fun load(context: Context, id: Long): Draft? = runCatching {
        val raw = context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).getString(id.toString(), null) ?: return null
        val o = JSONObject(raw)
        val a = o.getJSONArray("images")
        Draft(o.optString("text"), (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("incoming-") && !it.contains('/') }, o.optString("quote"))
    }.getOrNull()

    fun clear(context: Context, id: Long) {
        context.getSharedPreferences("shared_drafts", Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
    }
}
