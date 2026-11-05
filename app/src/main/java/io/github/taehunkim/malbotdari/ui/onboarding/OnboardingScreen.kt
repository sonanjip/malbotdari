package io.github.taehunkim.malbotdari.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.ui.settings.BackgroundAccessCards
import io.github.taehunkim.malbotdari.ui.settings.NunsoriCard
import io.github.taehunkim.malbotdari.ui.settings.JogakboCard
import io.github.taehunkim.malbotdari.ui.settings.OndolSaemCard

/** First run: what the app is, the two companion apps (OndolSaem required, Nunsori optional), background access. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 2
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("onboarding")) {
        Text(stringResource(R.string.onb_step, step + 1, last + 1), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.padding(4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                0 -> {
                    Text(stringResource(R.string.onb_intro_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.onb_intro_body), style = MaterialTheme.typography.bodyMedium)
                }
                1 -> {
                    Text(stringResource(R.string.onb_apps_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.onb_apps_body), style = MaterialTheme.typography.bodyMedium)
                    OndolSaemCard()
                    NunsoriCard()
                    JogakboCard()
                }
                else -> {
                    Text(stringResource(R.string.onb_bg_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.onb_bg_body), style = MaterialTheme.typography.bodyMedium)
                    BackgroundAccessCards()
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { step-- }, enabled = step > 0) { Text(stringResource(R.string.onb_back)) }
            if (step < last) Button(onClick = { step++ }, modifier = Modifier.testTag("onb_next")) { Text(stringResource(R.string.onb_next)) }
            else Button(onClick = onDone, modifier = Modifier.testTag("onb_finish")) { Text(stringResource(R.string.onb_finish)) }
        }
    }
}
