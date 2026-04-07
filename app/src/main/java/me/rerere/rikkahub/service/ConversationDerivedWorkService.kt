package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.pages.debug.PerformanceDiagnosticsRecorder
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class ConversationDerivedWorkService(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val conversationRepo: ConversationRepository,
    private val runtimeService: ConversationRuntimeService,
    private val persistenceService: ConversationPersistenceService,
    private val artifactService: ConversationArtifactService,
    private val diagnosticsRecorder: PerformanceDiagnosticsRecorder,
) {
    private val titleJobs = ConcurrentHashMap<Uuid, Job>()
    private val suggestionJobs = ConcurrentHashMap<Uuid, Job>()

    fun trackTitleJob(conversationId: Uuid, job: Job) {
        titleJobs[conversationId]?.cancel()
        titleJobs[conversationId] = job
        job.invokeOnCompletion {
            titleJobs.remove(conversationId, job)
        }
    }

    fun trackSuggestionJob(conversationId: Uuid, job: Job) {
        suggestionJobs[conversationId]?.cancel()
        suggestionJobs[conversationId] = job
        job.invokeOnCompletion {
            suggestionJobs.remove(conversationId, job)
        }
    }

    fun hasTitleJob(conversationId: Uuid): Boolean = titleJobs[conversationId]?.isActive == true

    fun hasSuggestionJob(conversationId: Uuid): Boolean = suggestionJobs[conversationId]?.isActive == true

    fun getTrackedJobsCount(): Int {
        return titleJobs.values.count { it.isActive } + suggestionJobs.values.count { it.isActive }
    }

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) {
        diagnosticsRecorder.record(
            category = "title-start",
            detail = "force=$force",
            conversationId = conversationId,
        )
        val shouldGenerate = force || conversation.title.isBlank()
        if (!shouldGenerate) return

        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.titleModelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val providerHandler = providerManager.getProviderByType(provider)
        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    prompt = settings.titlePrompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to conversation.currentMessages.takeLast(4).joinToString("\n\n") { it.summaryAsText() },
                    ),
                ),
            ),
            params = TextGenerationParams(
                model = model,
                thinkingBudget = 0,
            ),
        )
        val title = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        if (title.isBlank()) return

        val latestConversation = resolveLatestConversation(conversationId, conversation) ?: return
        persistenceService.saveConversationMetadata(
            conversationId = conversationId,
            conversation = latestConversation.copy(title = title),
        )
        diagnosticsRecorder.record(
            category = "title-finish",
            detail = "updated=true",
            conversationId = conversationId,
        )
    }

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        diagnosticsRecorder.record(
            category = "suggestion-start",
            detail = "messages=${conversation.currentMessages.size}",
            conversationId = conversationId,
        )
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.suggestionModelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return

        runtimeService.getCurrentConversationOrNull(conversationId)?.let { current ->
            runtimeService.updateConversation(
                conversationId,
                current.copy(chatSuggestions = emptyList()),
            )
        }

        val providerHandler = providerManager.getProviderByType(provider)
        val result = providerHandler.generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    settings.suggestionPrompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to conversation.currentMessages.takeLast(8).joinToString("\n\n") { it.summaryAsText() },
                    ),
                ),
            ),
            params = TextGenerationParams(
                model = model,
                thinkingBudget = 0,
            ),
        )
        val suggestions = result.choices.firstOrNull()?.message?.toText()
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.take(10)
            .orEmpty()

        val latestConversation = resolveLatestConversation(conversationId, conversation) ?: conversation
        persistenceService.saveConversationMetadata(
            conversationId = conversationId,
            conversation = latestConversation.copy(chatSuggestions = suggestions),
        )
        diagnosticsRecorder.record(
            category = "suggestion-finish",
            detail = "count=${suggestions.size}",
            conversationId = conversationId,
        )
    }

    fun titleErrorTitle(): String = context.getString(R.string.error_title_generate_title)

    private suspend fun resolveLatestConversation(
        conversationId: Uuid,
        fallback: Conversation,
    ): Conversation? {
        val sessionConversation = runtimeService.getCurrentConversationOrNull(conversationId)
        val persistedConversation = conversationRepo.getConversationById(conversationId)
        return when {
            persistedConversation != null -> artifactService.mergeMissingCompressionArtifacts(
                base = persistedConversation,
                fallback = sessionConversation ?: persistedConversation,
            )

            sessionConversation != null -> sessionConversation
            fallback.id == conversationId -> fallback
            else -> null
        }
    }
}
