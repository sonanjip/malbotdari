package io.github.taehunkim.malbotdari.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.taehunkim.malbotdari.BuildConfig
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.data.db.AppDatabase
import io.github.taehunkim.malbotdari.data.prefs.SettingsStore
import io.github.taehunkim.malbotdari.chat.schedule.FileMatchMode
import io.github.taehunkim.malbotdari.chat.schedule.UnsureAction
import io.github.taehunkim.malbotdari.ml.HostState
import io.github.taehunkim.ondolsaem.sdk.OndolSaem
import androidx.compose.ui.platform.testTag
import io.github.taehunkim.malbotdari.rememberContainer
import kotlinx.coroutines.launch

/** One scrolling page: connections, the user's instructions, scheduled tasks, language, about. */
@Composable
fun SettingsScreen() {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(R.string.settings_connections, R.string.settings_connections_desc)
        OndolSaemCard()
        NunsoriCard()
        JogakboCard()
        HorizontalDivider()

        val savedPrompt by c.settings.chatCustomPrompt.collectAsStateWithLifecycle("")
        var prompt by remember(savedPrompt) { mutableStateOf(savedPrompt) }
        Section(R.string.chat_prompt_title, R.string.chat_prompt_desc)
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it.take(SettingsStore.MAX_CUSTOM_PROMPT) },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.chat_prompt_hint)) },
            supportingText = { Text("${prompt.length} / ${SettingsStore.MAX_CUSTOM_PROMPT}") },
        )
        OutlinedButton(onClick = { scope.launch { c.settings.setChatCustomPrompt(prompt) } }, enabled = prompt.trim() != savedPrompt) { Text(stringResource(R.string.btn_save)) }
        HorizontalDivider()

        Section(R.string.bg_title, R.string.bg_desc)
        BackgroundAccessCards()
        Text(stringResource(R.string.task_file_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val judgeFirst by c.settings.taskJudgeFirst.collectAsStateWithLifecycle(true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_judge_first_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.settings_judge_first_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = judgeFirst, onCheckedChange = { on -> scope.launch { c.settings.setTaskJudgeFirst(on) } })
        }
        FileMatchSettings()
        HorizontalDivider()

        Section(R.string.settings_language, null)
        LanguagePicker()
        HorizontalDivider()
        About()
    }
}

/** How file tasks match their condition: meaning match or OndolSaem's decision model, and what to do when it is unsure. */
@Composable
private fun FileMatchSettings() {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val mode by c.settings.fileMatchMode.collectAsStateWithLifecycle(FileMatchMode.EMBEDDING)
    val unsure by c.settings.decisionUnsure.collectAsStateWithLifecycle(UnsureAction.ASK_LLM)
    val host by c.models.state.collectAsStateWithLifecycle()
    Text(stringResource(R.string.settings_file_match_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.settings_file_match_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((m, label) in listOf(FileMatchMode.EMBEDDING to R.string.file_match_embedding, FileMatchMode.DECISION to R.string.file_match_decision)) {
            FilterChip(selected = mode == m, onClick = { scope.launch { c.settings.setFileMatchMode(m) } }, label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("file_match_${m.name}"))
        }
    }
    if (mode == FileMatchMode.DECISION) {
        if (host.status == HostState.Status.CONNECTED && !host.ready(OndolSaem.Categories.DECISION)) {
            Text(stringResource(R.string.file_match_no_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.settings_unsure_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_unsure_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((a, label) in listOf(UnsureAction.ASK_LLM to R.string.unsure_ask, UnsureAction.DROP to R.string.unsure_drop, UnsureAction.KEEP to R.string.unsure_keep)) {
                FilterChip(selected = unsure == a, onClick = { scope.launch { c.settings.setDecisionUnsure(a) } }, label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("unsure_${a.name}"))
            }
        }
    }
}

@Composable
private fun About() {
    val ctx = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    Text(stringResource(R.string.app_name_full), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, AppDatabase.VERSION))
    TextButton(onClick = {
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL))) }
    }) { Text(stringResource(R.string.about_github)) }
    TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.about_licenses)) }
    if (showLicenses) {
        val text = remember { runCatching { ctx.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.about_licenses_close)) } },
            title = { Text(stringResource(R.string.about_licenses)) },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        )
    }
}

private const val GITHUB_URL = "https://github.com/TaeHunKim/malbotdari"

@Composable
internal fun Section(title: Int, desc: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        if (desc != null) Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
    val options = listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ko" to R.string.lang_ko)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (tag, label) ->
            FilterChip(selected = current == tag, onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }, label = { Text(stringResource(label)) })
        }
    }
}
