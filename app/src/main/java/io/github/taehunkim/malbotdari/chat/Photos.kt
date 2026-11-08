package io.github.taehunkim.malbotdari.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Photos the user attaches to a message (from the system photo picker). */
object Photos {
    /** The photo at [uri] as a JPEG at most [maxSide] pixels on its long side (rotation applied by the decoder). */
    fun jpeg(context: Context, uri: Uri, maxSide: Int): ByteArray {
        val bmp = ImageDecoder.decodeBitmap(if (uri.scheme == "file") ImageDecoder.createSource(java.io.File(requireNotNull(uri.path))) else ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = maxSide.toFloat() / maxOf(w, h)
            if (scale < 1f) d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } finally { bmp.recycle() }
    }
}
