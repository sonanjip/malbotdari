package io.github.taehunkim.malbotdari.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.files.IndexState
import io.github.taehunkim.malbotdari.files.NunsoriBridge
import io.github.taehunkim.malbotdari.ml.HostState
import io.github.taehunkim.malbotdari.ml.ModelHost
import io.github.taehunkim.malbotdari.rememberContainer
import io.github.taehunkim.nunsori.sdk.Nunsori
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import kotlinx.coroutines.launch

/** OndolSaem: runs the chat model. Shows whether it is reachable and which chat model it has. */
@Composable
fun OndolSaemCard() {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.models.state.collectAsStateWithLifecycle()
    val chat = s.model(OndolSaem.Categories.LLM)
    val status = when (s.status) {
        HostState.Status.CONNECTED -> if (chat != null && s.ready(OndolSaem.Categories.LLM)) stringResource(R.string.conn_ondolsaem_ready, chat.displayName) else stringResource(R.string.conn_ondolsaem_no_model)
        HostState.Status.NOT_INSTALLED -> stringResource(R.string.conn_not_installed)
        HostState.Status.NOT_APPROVED -> stringResource(R.string.conn_not_approved)
        HostState.Status.HOST_TOO_OLD, HostState.Status.CLIENT_TOO_OLD, HostState.Status.NOT_ALLOWED -> stringResource(R.string.conn_too_old)
        HostState.Status.UNKNOWN -> stringResource(R.string.conn_checking)
        HostState.Status.UNREACHABLE -> stringResource(R.string.conn_unreachable)
    }
    ConnectionCard(
        "conn_ondolsaem", stringResource(R.string.conn_ondolsaem_title), stringResource(R.string.conn_ondolsaem_desc), status,
        ok = s.status == HostState.Status.CONNECTED && s.ready(OndolSaem.Categories.LLM),
        action = when (s.status) {
            HostState.Status.NOT_INSTALLED -> stringResource(R.string.conn_get) to { open(ctx, ModelHost.INSTALL_URL) }
            HostState.Status.NOT_APPROVED -> stringResource(R.string.conn_open_ondolsaem) to { runCatching { ctx.startActivity(OndolSaem.approvalIntent()) }; Unit }
            else -> stringResource(R.string.conn_open_ondolsaem) to { c.models.client.launchIntent()?.let { ctx.startActivity(it) }; Unit }
        },
        onCheck = { scope.launch { c.models.refresh() } },
    )
}

/** Nunsori: holds the user's files and their index (search, read, move, delete). Optional. */
@Composable
fun NunsoriCard() {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.files.state.collectAsStateWithLifecycle()
    val status = when (s.status) {
        IndexState.Status.CONNECTED -> stringResource(R.string.conn_nunsori_ready, s.version.orEmpty())
        IndexState.Status.NOT_INSTALLED -> stringResource(R.string.conn_nunsori_not_installed)
        IndexState.Status.NOT_APPROVED -> stringResource(R.string.conn_not_approved_nunsori)
        IndexState.Status.TOO_OLD -> stringResource(R.string.conn_nunsori_too_old)
        IndexState.Status.UNKNOWN -> stringResource(R.string.conn_checking)
        IndexState.Status.UNREACHABLE -> stringResource(R.string.conn_unreachable)
    }
    ConnectionCard(
        "conn_nunsori", stringResource(R.string.conn_nunsori_title), stringResource(R.string.conn_nunsori_desc), status,
        ok = s.status == IndexState.Status.CONNECTED,
        action = when (s.status) {
            IndexState.Status.NOT_INSTALLED, IndexState.Status.TOO_OLD -> stringResource(R.string.conn_get) to { open(ctx, NunsoriBridge.INSTALL_URL) }
            IndexState.Status.NOT_APPROVED -> stringResource(R.string.conn_open_nunsori) to { runCatching { ctx.startActivity(Nunsori.approvalIntent()) }; Unit }
            else -> stringResource(R.string.conn_open_nunsori) to { c.files.client.launchIntent()?.let { ctx.startActivity(it) }; Unit }
        },
        onCheck = { scope.launch { c.files.refresh() } },
    )
}

@Composable
private fun ConnectionCard(tag: String, title: String, desc: String, status: String, ok: Boolean, action: Pair<String, () -> Unit>, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("${tag}_status"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = action.second) { Text(action.first) }
                TextButton(onClick = onCheck) { Text(stringResource(R.string.conn_check)) }
            }
        }
    }
}

private fun open(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
