package io.github.taehunkim.malbotdari

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taehunkim.malbotdari.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** First run (three steps) leads to the chat list; Settings shows both companion apps; the up arrow goes back. */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun freshStart() = runBlocking {
        SettingsStore(rule.activity).setOnboardingDone(false)
    }

    @Test fun onboardingThenChatsThenSettings() {
        val res = rule.activity.resources
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("onboarding").assertIsDisplayed() }.isSuccess }
        rule.onNodeWithTag("onb_next").performClick()
        rule.onNodeWithTag("conn_ondolsaem").assertIsDisplayed()
        rule.onNodeWithTag("conn_nunsori").assertIsDisplayed()
        rule.onNodeWithTag("onb_next").performClick()
        rule.onNodeWithTag("onb_finish").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("nav_settings").assertIsDisplayed() }.isSuccess }
        rule.onNodeWithTag("nav_settings").performClick()
        rule.onNodeWithTag("conn_nunsori").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(res.getString(R.string.chat_prompt_title)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("nav_up").performClick()
        rule.onNodeWithTag("nav_settings").assertIsDisplayed()
    }
}
