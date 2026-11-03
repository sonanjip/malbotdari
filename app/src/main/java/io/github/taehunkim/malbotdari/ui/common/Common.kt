package io.github.taehunkim.malbotdari.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.rememberContainer
import io.github.taehunkim.nunsori.sdk.Nunsori
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WarningCard(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A Nunsori file's preview, loaded from Nunsori (an icon by kind when it has none or Nunsori is away). */
@Composable
fun RemoteThumb(itemId: Long, kind: String?, modifier: Modifier = Modifier) {
    val c = rememberContainer()
    val bitmap by produceState<ImageBitmap?>(null, itemId) {
        value = withContext(Dispatchers.IO) {
            runCatching { c.files.thumbnail(itemId, THUMB_PX)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    when (kind) { "audio" -> Icons.Filled.GraphicEq; "video" -> Icons.Filled.Movie; "image" -> Icons.Filled.Image; else -> Icons.Filled.Description },
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val THUMB_PX = 256

/** A photo the user attached (stored in this app's files), decoded small. */
@Composable
fun LocalPhoto(file: java.io.File, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.path, o)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) } ?: Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {}
}

/** Opens a Nunsori file in the viewer the user picks (through Nunsori: this app never reads files itself). */
fun openItem(ctx: Context, itemId: Long) {
    try { ctx.startActivity(Nunsori.openItemIntent(itemId)) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.nunsori_needed, Toast.LENGTH_SHORT).show()
    }
}

/** Share plain text (the Markdown source of a chat message). */
fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try { ctx.startActivity(Intent.createChooser(send, null)) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }
}
