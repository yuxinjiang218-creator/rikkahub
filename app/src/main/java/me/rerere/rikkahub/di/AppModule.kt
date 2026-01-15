package me.rerere.rikkahub.di

// Firebase 已禁用
// import com.google.firebase.Firebase
// import com.google.firebase.analytics.analytics
// import com.google.firebase.crashlytics.crashlytics
// import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.analytics.Analytics
import me.rerere.rikkahub.data.analytics.NoOpAnalytics
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.knowledge.KnowledgeBaseSearchTool
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        LocalTools(get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    // Firebase 已禁用
    // single { Firebase.crashlytics }
    // single { Firebase.remoteConfig }
    // single { Firebase.analytics }

    // 埋点服务（No-Op 实现）
    single<Analytics> { NoOpAnalytics }

    single {
        AILoggingManager()
    }

    // 调试系统（临时）
    single {
        DebugLogger.getInstance(get())
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            knowledgeBaseSearchTool = get(),
            archiveSummaryDao = get(),
            vectorIndexDao = get(),
            verbatimRecallService = get(),
            semanticRecallService = get()
        )
    }
}
