package io.github.taehunkim.malbotdari

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import io.github.taehunkim.malbotdari.chat.ChatController
import io.github.taehunkim.malbotdari.chat.actions.ActionManager
import io.github.taehunkim.malbotdari.chat.schedule.TaskAlarms
import io.github.taehunkim.malbotdari.chat.schedule.TaskManager
import io.github.taehunkim.malbotdari.data.db.ALL_MIGRATIONS
import io.github.taehunkim.malbotdari.data.db.AppDatabase
import io.github.taehunkim.malbotdari.data.prefs.SettingsStore
import io.github.taehunkim.malbotdari.files.IndexChangedWorker
import io.github.taehunkim.malbotdari.files.NunsoriBridge
import io.github.taehunkim.malbotdari.ml.ModelHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(val context: Context) {
    /** Lives as long as the process (schedule updates, Nunsori hints). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { Room.databaseBuilder(context, AppDatabase::class.java, "malbotdari.db").addMigrations(*ALL_MIGRATIONS).build() }
    val settings = SettingsStore(context)
    /** The chat model (OndolSaem). */
    val models = ModelHost(context, settings, appScope)
    /** The user's files (Nunsori). */
    val files = NunsoriBridge(context)
    val taskAlarms by lazy { TaskAlarms(context) }
    val taskManager: TaskManager by lazy {
        TaskManager(
            context, db.taskDao(), db.chatDao(), files, taskAlarms, { chat },
            judgeFirst = { settings.taskJudgeFirst.first() }, actionDao = db.actionDao(),
            cursor = { settings.indexCursor.first() }, setCursor = settings::setIndexCursor,
        )
    }
    val actions: ActionManager by lazy { ActionManager(db.actionDao(), files) }
    val chat: ChatController by lazy { ChatController(context, db.chatDao(), files, models, settings, appScope, taskManager = taskManager, actions = actions) }
}

class MalbotdariApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        container.appScope.launch { runCatching { container.taskManager.rearmAll() } }
        container.appScope.launch {
            container.models.refresh()
            container.files.refresh()
            // catch up on files Nunsori indexed while this app was not told (or not running)
            IndexChangedWorker.enqueue(this@MalbotdariApp)
        }
        IndexChangedWorker.schedulePeriodic(this)
        // OndolSaem announces model changes by broadcast (also when this app is not running): re-check.
        io.github.taehunkim.ondolsaem.sdk.ModelChangedReceiver.handler = { container.appScope.launch { container.models.refresh() } }
        // Nunsori says its index changed: look for files that trigger scheduled tasks.
        io.github.taehunkim.nunsori.sdk.IndexChangedReceiver.handler = { IndexChangedWorker.enqueue(this) }
    }
}

@Composable
fun rememberContainer(): AppContainer {
    val ctx = LocalContext.current.applicationContext
    return remember { (ctx as MalbotdariApp).container }
}
