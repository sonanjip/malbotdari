package io.github.taehunkim.malbotdari.sharing

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.taehunkim.malbotdari.MainActivity
import io.github.taehunkim.malbotdari.Notifications
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.ui.theme.AppTheme

class SharePreparationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SharedDraft.contentUris(intent).isEmpty()) {
            // Text-only shares retain the existing direct-to-chat flow.
            startActivity(Intent(intent).setClass(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            AppTheme {
                val vm: SharePreparationViewModel = viewModel()
                LaunchedEffect(Unit) { vm.start(intent) }
                LaunchedEffect(vm.completeSession) {
                    vm.completeSession?.let { id ->
                        startActivity(Intent(this@SharePreparationActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(Notifications.EXTRA_SESSION, id))
                        finish()
                    }
                }
                BackHandler { if (!vm.copying && vm.completeSession == null) vm.cancel { finish() } }
                SharePreparationScreen(vm, onCancel = { vm.cancel { finish() } })
            }
        }
    }
}

@Composable
private fun SharePreparationScreen(vm: SharePreparationViewModel, onCancel: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_prepare_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.share_prepare_explanation))
            if (vm.text.isNotBlank()) Text(stringResource(R.string.share_prepare_text), style = MaterialTheme.typography.labelLarge)
            if (vm.loading) CircularProgressIndicator()
            else {
                Text(stringResource(R.string.share_prepare_count, vm.selected.size, vm.items.size - vm.selected.size))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(vm.items) { index, item ->
                        val reason = ShareSelection.reason(vm.items, vm.selected, index)
                        val failed = vm.failures[index]
                        val size = item.size?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                            ?: stringResource(R.string.share_size_unknown)
                        Row(Modifier.fillMaxWidth().testTag("share_item_$index"), verticalAlignment = Alignment.Top) {
                            Checkbox(checked = index in vm.selected, onCheckedChange = { vm.toggle(index) },
                                enabled = !vm.copying && index !in vm.copied && (index in vm.selected || reason == null),
                                modifier = Modifier.testTag("share_select_$index"))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text("${item.mime} · $size", style = MaterialTheme.typography.bodySmall)
                                if (index in vm.copied) Text(stringResource(R.string.share_copied))
                                if (reason != null) Text(stringResource(reasonText(reason)), color = MaterialTheme.colorScheme.error)
                                if (failed != null) Text(stringResource(if (failed == "too_large") R.string.share_copy_too_large else R.string.share_copy_failed), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (vm.fatalError) Text(stringResource(R.string.share_prepare_failed), color = MaterialTheme.colorScheme.error)
                if (vm.copying) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.attachment_copying)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !vm.copying && vm.completeSession == null) { Text(stringResource(R.string.action_cancel)) }
                    if (vm.failures.isNotEmpty()) TextButton(onClick = vm::continueWithoutFailed, enabled = !vm.copying && vm.completeSession == null) { Text(stringResource(R.string.share_continue_without_failed)) }
                    Button(onClick = vm::confirm, enabled = !vm.copying && vm.completeSession == null && (vm.selected.isNotEmpty() || vm.text.isNotBlank()), modifier = Modifier.testTag("share_confirm")) {
                        Text(stringResource(if (vm.failures.isEmpty()) R.string.share_confirm else R.string.attachment_retry))
                    }
                }
            }
        }
    }
}

private fun reasonText(reason: ShareSelection.Reason): Int = when (reason) {
    ShareSelection.Reason.INVALID_URI -> R.string.share_invalid_uri
    ShareSelection.Reason.UNSUPPORTED -> R.string.share_unsupported
    ShareSelection.Reason.FILE_TOO_LARGE -> R.string.share_file_too_large
    ShareSelection.Reason.TOO_MANY -> R.string.share_too_many
    ShareSelection.Reason.TOTAL_TOO_LARGE -> R.string.share_total_too_large
}
