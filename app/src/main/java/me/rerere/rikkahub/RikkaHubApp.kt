package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
// Firebase 已禁用
// import com.google.firebase.remoteconfig.FirebaseRemoteConfig
// import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化调试系统（必须在 Koin 之前）
        initializeDebugSystem()

        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // delete temp files
        deleteTempFiles()

        // Firebase RemoteConfig 已禁用
        // Init remote config
        // get<FirebaseRemoteConfig>().apply {
        //     setConfigSettingsAsync(remoteConfigSettings {
        //         minimumFetchIntervalInSeconds = 1800
        //     })
        //     setDefaultsAsync(R.xml.remote_config_defaults)
        //     fetchAndActivate()
        // }

        // https://issuetracker.google.com/issues/469669851
        ComposeFoundationFlags.isPausableCompositionInPrefetchEnabled = false
    }

    /**
     * 初始化调试系统
     */
    private fun initializeDebugSystem() {
        // 1. 安装全局异常处理器
        me.rerere.rikkahub.debug.CrashHandler.install(this)

        // 2. 初始化 IntentRouter（需要 context）
        me.rerere.rikkahub.service.IntentRouter.init(this)

        // 3. 检查是否有待处理的崩溃
        checkPendingCrashes()
    }

    /**
     * 检查待处理的崩溃
     */
    private fun checkPendingCrashes() {
        val crashHandler = me.rerere.rikkahub.debug.CrashHandler(
            this,
            Thread.getDefaultUncaughtExceptionHandler()
        )
        val pendingCrashes = crashHandler.checkPendingCrashes()
        if (pendingCrashes.isNotEmpty()) {
            val prefs = getSharedPreferences("debug_prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("has_pending_crash", true).apply()
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
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
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
