package io.github.taehunkim.malbotdari

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
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
    @Test fun incomingShareKeepsExistingUnsentDraft() {
        val app = rule.activity.application as MalbotdariApp
        val container = app.container
        val id = runBlocking {
            container.settings.setOnboardingDone(true)
            container.chat.newSession().also { container.db.chatDao().rename(it, "Draft preservation test") }
        }
        try {
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("Draft preservation test").assertIsDisplayed() }.isSuccess }
            rule.onNodeWithText("Draft preservation test").performClick()
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("chat_input").assertIsDisplayed() }.isSuccess }
            rule.onNodeWithTag("chat_input").performTextInput("Unsent original question")
            rule.runOnIdle {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setClass(rule.activity, MainActivity::class.java)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "Incoming shared text")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val launchIntent = rule.activity.intent
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(rule.activity, intent)
                // ActivityScenario tracks lifecycle by launch intent; onNewIntent changes it.
                rule.activity.intent = launchIntent
            }
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("chat_input").assertTextContains("Incoming shared text") }.isSuccess }
            rule.onNodeWithTag("nav_up").performClick()
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithText("Draft preservation test").assertIsDisplayed() }.isSuccess }
            rule.onNodeWithText("Draft preservation test").performClick()
            rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("chat_input").assertTextContains("Unsent original question") }.isSuccess }
            org.junit.Assert.assertEquals("Unsent original question", io.github.taehunkim.malbotdari.sharing.SharedDraft.load(rule.activity, id)?.text)
        } finally { runBlocking { container.chat.deleteSession(id) } }
    }

}
