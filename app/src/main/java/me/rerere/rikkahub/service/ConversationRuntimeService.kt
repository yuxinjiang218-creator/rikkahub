package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntimeSvc"

class ConversationRuntimeService(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
) {
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val sessionsVersion = MutableStateFlow(0L)

    fun cleanup() {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        sessionsVersion.value++
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getCurrentConversation(conversationId: Uuid): Conversation {
        return getConversationFlow(conversationId).value
    }

    fun getCurrentConversationOrNull(conversationId: Uuid): Conversation? {
        return sessions[conversationId]?.state?.value
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { session ->
                    session.generationJob.map { job -> session.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    fun getConversationJobsSnapshot(): Map<Uuid, Job?> {
        return sessions.values
            .mapNotNull { session ->
                session.getJob()?.let { session.id to it }
            }
            .toMap()
    }

    fun getSessionCount(): Int = sessions.size

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit,
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    fun cancelGenerationJob(conversationId: Uuid) {
        getOrCreateSession(conversationId).getJob()?.cancel()
    }

    fun setGenerationJob(conversationId: Uuid, job: Job?) {
        getOrCreateSession(conversationId).setJob(job)
    }

    fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(newConversation = conversation, oldConversation = session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        updateConversation(conversationId, update(getConversationFlow(conversationId).value))
    }

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) {
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = conversationId,
                initial = Conversation.ofId(
                    id = conversationId,
                    assistantId = settings.getCurrentAssistant().id,
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
            ).also {
                sessionsVersion.value++
                Log.i(TAG, "createSession: $conversationId (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        sessions.remove(conversationId)?.cleanup()
        sessionsVersion.value++
        Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val deletedFiles = oldConversation.files.filter { oldFile ->
            newConversation.files.none { it == oldFile }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }
}
