package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.db.index.IndexMigrationManager
import me.rerere.rikkahub.data.db.index.VectorBackendVerifier
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.KnowledgeBaseIndexForegroundService
import me.rerere.rikkahub.service.KnowledgeBaseService
import me.rerere.rikkahub.service.ScheduledPromptManager
import me.rerere.rikkahub.service.ScheduledTaskKeepAliveService
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID = "scheduled_task"
const val SCHEDULED_TASK_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID = "scheduled_task_keep_alive"
const val KNOWLEDGE_BASE_INDEX_NOTIFICATION_CHANNEL_ID = "knowledge_base_index"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // delete temp files
        deleteTempFiles()

        // sync upload files to DB
        syncManagedFiles()

        // restore container state if runtime has been initialized before
        restoreContainerState()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()
        startScheduledPromptManager()
        startScheduledTaskKeepAliveIfEnabled()
        startIndexMigrationIfNeeded()
        resumeKnowledgeBaseIndexingIfNeeded()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun restoreContainerState() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val prootManager = get<PRootManager>()
                if (prootManager.checkInitializationStatus()) {
                    prootManager.restoreState()
                }
            }.onFailure {
                Log.e(TAG, "restoreContainerState failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val scheduledTaskChannel = NotificationChannelCompat
            .Builder(SCHEDULED_TASK_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("定时任务")
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(scheduledTaskChannel)

        val scheduledTaskKeepAliveChannel = NotificationChannelCompat
            .Builder(SCHEDULED_TASK_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("定时任务保活")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(scheduledTaskKeepAliveChannel)

        val knowledgeBaseIndexChannel = NotificationChannelCompat
            .Builder(KNOWLEDGE_BASE_INDEX_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("知识库索引")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(knowledgeBaseIndexChannel)
    }

    private fun startScheduledPromptManager() {
        get<ScheduledPromptManager>().start()
    }

    private fun startScheduledTaskKeepAliveIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(900)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (!settings.scheduledTaskKeepAliveEnabled) return@launch
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@RikkaHubApp,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "startScheduledTaskKeepAliveIfEnabled: notification permission not granted, skipping")
                    return@launch
                }
                val intent = Intent(this@RikkaHubApp, ScheduledTaskKeepAliveService::class.java).apply {
                    action = ScheduledTaskKeepAliveService.ACTION_START
                }
                startForegroundService(intent)
            }.onFailure {
                Log.e(TAG, "startScheduledTaskKeepAliveIfEnabled failed", it)
            }
        }
    }

    private fun startIndexMigrationIfNeeded() {
        get<AppScope>().launch(Dispatchers.IO) {
            val migrationManager = get<IndexMigrationManager>()
            try {
                migrationManager.migrateIfNeeded()
                check(migrationManager.shouldUseIndexBackend()) {
                    "Index migration did not cut over to the sqlite-vector backend"
                }
                runCatching {
                    get<VectorBackendVerifier>().verifyBackendHealth(force = true)
                }.onFailure { error ->
                    Log.e(TAG, "sqlite-vector startup health check failed", error)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "startIndexMigrationIfNeeded failed", error)
                crashProcessOnIndexMigrationFailure(error)
            }
        }
    }

    private fun crashProcessOnIndexMigrationFailure(error: Throwable): Nothing {
        val fatal = IllegalStateException("Index migration failed", error)
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), fatal)
        Process.killProcess(Process.myPid())
        throw fatal
    }

    private fun resumeKnowledgeBaseIndexingIfNeeded() {
        get<AppScope>().launch {
            runCatching {
                delay(1_200)
                get<KnowledgeBaseService>().resumePendingWorkIfNeeded()
            }.onFailure {
                Log.e(TAG, "resumeKnowledgeBaseIndexingIfNeeded failed", it)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
        stopService(Intent(this, KnowledgeBaseIndexForegroundService::class.java))
        stopService(Intent(this, ScheduledTaskKeepAliveService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
