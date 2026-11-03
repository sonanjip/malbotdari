package io.github.taehunkim.malbotdari.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.taehunkim.malbotdari.R
import io.github.taehunkim.malbotdari.rememberContainer
import io.github.taehunkim.malbotdari.ui.chat.ChatListScreen
import io.github.taehunkim.malbotdari.ui.chat.ChatScreen
import io.github.taehunkim.malbotdari.ui.onboarding.OnboardingScreen
import io.github.taehunkim.malbotdari.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun AppScaffold(openChat: Long? = null, onChatOpened: () -> Unit = {}) {
    val c = rememberContainer()
    val done by c.settings.onboardingDone.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    when (done) {
        null -> Box(Modifier.fillMaxSize())
        false -> OnboardingScreen { scope.launch { c.settings.setOnboardingDone(true) } }
        true -> MainScaffold(openChat, onChatOpened)
    }
}

/** Chats (start), one chat and Settings; the two detail screens get an up arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(openChat: Long?, onChatOpened: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    LaunchedEffect(openChat) {
        if (openChat != null) {
            nav.navigate("chat/$openChat") { launchSingleTop = true; popUpTo(ROUTE_CHATS) }
            onChatOpened()
        }
    }
    val home = route == ROUTE_CHATS || route.isEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (route == ROUTE_SETTINGS) R.string.nav_settings else R.string.app_name)) },
                navigationIcon = {
                    if (!home) IconButton(onClick = { if (!nav.popBackStack(ROUTE_CHATS, inclusive = false)) nav.navigate(ROUTE_CHATS) }, modifier = Modifier.testTag("nav_up")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_up))
                    }
                },
                actions = {
                    if (route != ROUTE_SETTINGS) IconButton(onClick = { nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true } }, modifier = Modifier.testTag("nav_settings")) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.nav_settings))
                    }
                },
            )
        },
    ) { padding ->
        NavHost(nav, startDestination = ROUTE_CHATS, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(ROUTE_CHATS) { ChatListScreen(onOpen = { id -> nav.navigate("chat/$id") }) }
            composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ChatScreen(e.arguments?.getLong("id") ?: 0L, onOpenSettings = { nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true } })
            }
            composable(ROUTE_SETTINGS) { SettingsScreen() }
        }
    }
}

const val ROUTE_CHATS = "chats"
const val ROUTE_SETTINGS = "settings"
