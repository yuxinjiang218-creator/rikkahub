package me.rerere.rikkahub.data.repository

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.CompressionEventDAO
import me.rerere.rikkahub.data.db.dao.CompressionEventPayloadDAO
import me.rerere.rikkahub.data.db.dao.ConversationCompressionPayloadDAO
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.ConversationRecord
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.CompressionEventEntity
import me.rerere.rikkahub.data.db.entity.CompressionEventPayloadEntity
import me.rerere.rikkahub.data.db.entity.ConversationCompressionPayloadEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.index.IndexMigrationManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.CompressionEventPayload
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompressionPayload
import me.rerere.rikkahub.data.model.ConversationCompressionState
import me.rerere.rikkahub.data.model.ConversationMemoryIndexState
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.withCompressionPayload
import me.rerere.rikkahub.data.model.withPayload
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val compressionEventDAO: CompressionEventDAO,
    private val conversationCompressionPayloadDAO: ConversationCompressionPayloadDAO,
    private val compressionEventPayloadDAO: CompressionEventPayloadDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val context: Context,
    private val messageFtsManager: MessageFtsManager,
    private val indexMigrationManager: IndexMigrationManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationRecordsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
        ).map { record ->
            conversationRecordToConversation(
                record = record,
                messageNodes = loadMessageNodes(record.id),
            )
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity -> conversationSummaryToConversation(entity) }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false,
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map(::conversationSummaryToConversation),
                    nextOffset = result.nextKey,
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword,
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false,
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map(::conversationSummaryToConversation),
                    nextOffset = result.nextKey,
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map(::conversationSummaryToConversation)
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(
        assistantId: Uuid,
        titleKeyword: String,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            conversationDAO.searchConversationsOfAssistantPaging(
                assistantId.toString(),
                titleKeyword,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map(::conversationSummaryToConversation)
    }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val record = conversationDAO.getConversationRecordById(uuid.toString()) ?: return null
        return conversationRecordToConversation(
            record = record,
            messageNodes = loadMessageNodes(record.id),
        )
    }

    suspend fun getConversationWithCompressionPayload(uuid: Uuid): Conversation? {
        val conversation = getConversationById(uuid) ?: return null
        return conversation.withCompressionPayload(getCompressionPayload(uuid))
    }

    suspend fun getCompressionPayload(conversationId: Uuid): ConversationCompressionPayload? {
        return conversationCompressionPayloadDAO.getPayloadOfConversation(conversationId.toString())
            ?.toModel()
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(conversationToConversationEntity(conversation))
            upsertCompressionPayloadLocked(conversation)
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        val syncResult = database.withTransaction {
            updateConversationMetadataLocked(conversation)
            syncMessageNodesLocked(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.syncConversation(
            conversation = conversation,
            changedNodes = syncResult.changedNodes,
            deletedNodeIds = syncResult.deletedNodeIds,
        )
    }

    suspend fun updateConversationMetadata(conversation: Conversation) {
        database.withTransaction {
            updateConversationMetadataLocked(conversation)
        }
    }

    suspend fun updateCompressionPayload(
        conversationId: Uuid,
        payload: ConversationCompressionPayload,
    ) {
        database.withTransaction {
            conversationCompressionPayloadDAO.insert(
                ConversationCompressionPayloadEntity(
                    conversationId = conversationId.toString(),
                    dialogueSummaryText = payload.dialogueSummaryText,
                    rollingSummaryJson = payload.rollingSummaryJson,
                )
            )
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            conversationDAO.delete(conversationToConversationEntity(conversation))
            compressionEventDAO.deleteByConversation(conversation.id.toString())
        }
        indexMigrationManager.deleteConversationScopedData(conversation.id.toString())
        filesManager.deleteChatFiles(fullConversation.files)
        SandboxEngine.deleteSandbox(context, fullConversation.id.toString())
    }

    suspend fun searchMessages(keyword: String) = messageFtsManager.search(keyword)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val record = conversationDAO.getConversationRecordById(id) ?: return@forEachIndexed
            val conversation = conversationRecordToConversation(
                record = record,
                messageNodes = loadMessageNodes(record.id),
            )
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            workflowState = conversation.workflowState?.let { JsonInstant.encodeToString(it) } ?: "",
            dialogueSummaryText = "",
            dialogueSummaryTokenEstimate = conversation.compressionState.dialogueSummaryTokenEstimate,
            dialogueSummaryUpdatedAt = conversation.compressionState.dialogueSummaryUpdatedAt.toEpochMilli(),
            rollingSummaryJson = "",
            rollingSummaryTokenEstimate = conversation.compressionState.rollingSummaryTokenEstimate,
            memoryLedgerStatus = conversation.compressionState.memoryLedgerStatus,
            memoryLedgerError = conversation.compressionState.memoryLedgerError,
            lastCompressedMessageIndex = conversation.compressionState.lastCompressedMessageIndex,
            lastCompressedAt = conversation.compressionState.updatedAt.toEpochMilli(),
            lastIndexStatus = conversation.memoryIndexState.lastIndexStatus,
            lastIndexedAt = conversation.memoryIndexState.lastIndexedAt.toEpochMilli(),
            lastIndexError = conversation.memoryIndexState.lastIndexError,
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>,
        compressionEvents: List<CompressionEvent> = emptyList(),
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            workflowState = conversationEntity.workflowState.takeIf { it.isNotBlank() }?.let {
                JsonInstant.decodeFromString(it)
            },
            compressionState = ConversationCompressionState(
                dialogueSummaryText = "",
                dialogueSummaryTokenEstimate = conversationEntity.dialogueSummaryTokenEstimate,
                dialogueSummaryUpdatedAt = Instant.ofEpochMilli(conversationEntity.dialogueSummaryUpdatedAt),
                rollingSummaryJson = "",
                rollingSummaryTokenEstimate = conversationEntity.rollingSummaryTokenEstimate,
                memoryLedgerStatus = conversationEntity.memoryLedgerStatus,
                memoryLedgerError = conversationEntity.memoryLedgerError,
                lastCompressedMessageIndex = conversationEntity.lastCompressedMessageIndex,
                updatedAt = Instant.ofEpochMilli(conversationEntity.lastCompressedAt),
            ),
            memoryIndexState = ConversationMemoryIndexState(
                lastIndexStatus = conversationEntity.lastIndexStatus,
                lastIndexedAt = Instant.ofEpochMilli(conversationEntity.lastIndexedAt),
                lastIndexError = conversationEntity.lastIndexError,
            ),
            compressionEvents = compressionEvents,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        val record = conversationDAO.getConversationRecordById(conversationId.toString())
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(record?.isPinned ?: false),
        )
    }

    suspend fun addCompressionEvent(
        conversationId: Uuid,
        boundaryIndex: Int,
        dialogueSummaryText: String,
        dialogueSummaryPreview: String,
        ledgerSnapshot: String,
        summarySnapshot: String,
        compressStartIndex: Int,
        compressEndIndex: Int,
        keepRecentMessages: Int,
        trigger: String,
        additionalPrompt: String,
        baseDialogueSummaryText: String,
        baseLedgerJson: String,
        baseSummaryJson: String,
        createdAt: Instant = Instant.now(),
    ): CompressionEvent {
        val lightEntity = CompressionEventEntity(
            conversationId = conversationId.toString(),
            boundaryIndex = boundaryIndex,
            dialogueSummaryText = "",
            dialogueSummaryPreview = dialogueSummaryPreview,
            ledgerSnapshot = "",
            summarySnapshot = "",
            compressStartIndex = compressStartIndex,
            compressEndIndex = compressEndIndex,
            keepRecentMessages = keepRecentMessages,
            trigger = trigger,
            additionalPrompt = additionalPrompt,
            baseDialogueSummaryText = "",
            baseLedgerJson = "",
            baseSummaryJson = "",
            createdAt = createdAt.toEpochMilli(),
        )
        val payload = CompressionEventPayloadEntity(
            eventId = 0L,
            dialogueSummaryText = dialogueSummaryText,
            ledgerSnapshot = ledgerSnapshot,
            summarySnapshot = summarySnapshot,
            baseDialogueSummaryText = baseDialogueSummaryText,
            baseLedgerJson = baseLedgerJson,
            baseSummaryJson = baseSummaryJson,
        )
        val id = database.withTransaction {
            val eventId = compressionEventDAO.insert(lightEntity)
            compressionEventPayloadDAO.insert(payload.copy(eventId = eventId))
            eventId
        }
        return CompressionEvent(
            id = id,
            boundaryIndex = boundaryIndex,
            dialogueSummaryPreview = dialogueSummaryPreview,
            compressStartIndex = compressStartIndex,
            compressEndIndex = compressEndIndex,
            keepRecentMessages = keepRecentMessages,
            trigger = trigger,
            additionalPrompt = additionalPrompt,
            createdAt = createdAt,
        ).withPayload(payload.toModel())
    }

    suspend fun updateCompressionEvent(event: CompressionEvent, conversationId: Uuid) {
        database.withTransaction {
            compressionEventDAO.update(
                CompressionEventEntity(
                    id = event.id,
                    conversationId = conversationId.toString(),
                    boundaryIndex = event.boundaryIndex,
                    dialogueSummaryText = "",
                    dialogueSummaryPreview = event.dialogueSummaryPreview,
                    ledgerSnapshot = "",
                    summarySnapshot = "",
                    compressStartIndex = event.compressStartIndex,
                    compressEndIndex = event.compressEndIndex,
                    keepRecentMessages = event.keepRecentMessages,
                    trigger = event.trigger,
                    additionalPrompt = event.additionalPrompt,
                    baseDialogueSummaryText = "",
                    baseLedgerJson = "",
                    baseSummaryJson = "",
                    createdAt = event.createdAt.toEpochMilli(),
                )
            )
            compressionEventPayloadDAO.insert(
                CompressionEventPayloadEntity(
                    eventId = event.id,
                    dialogueSummaryText = event.dialogueSummaryText,
                    ledgerSnapshot = event.ledgerSnapshot,
                    summarySnapshot = event.summarySnapshot,
                    baseDialogueSummaryText = event.baseDialogueSummaryText,
                    baseLedgerJson = event.baseLedgerJson,
                    baseSummaryJson = event.baseSummaryJson,
                )
            )
        }
    }

    suspend fun deleteCompressionEvent(conversationId: Uuid, eventId: Long) {
        compressionEventDAO.deleteByConversationAndId(conversationId.toString(), eventId)
    }

    suspend fun getCompressionEvents(conversationId: Uuid): List<CompressionEvent> {
        return loadCompressionEvents(conversationId.toString())
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            workflowState = entity.workflowState?.takeIf { it.isNotBlank() }?.let {
                JsonInstant.decodeFromString(it)
            },
        )
    }

    private fun conversationRecordToConversation(
        record: ConversationRecord,
        messageNodes: List<MessageNode>,
    ): Conversation {
        return Conversation(
            id = Uuid.parse(record.id),
            assistantId = Uuid.parse(record.assistantId),
            title = record.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            chatSuggestions = JsonInstant.decodeFromString(record.chatSuggestions),
            isPinned = record.isPinned,
            workflowState = record.workflowState.takeIf { it.isNotBlank() }?.let {
                JsonInstant.decodeFromString(it)
            },
            compressionState = ConversationCompressionState(
                dialogueSummaryText = "",
                dialogueSummaryTokenEstimate = record.dialogueSummaryTokenEstimate,
                dialogueSummaryUpdatedAt = Instant.ofEpochMilli(record.dialogueSummaryUpdatedAt),
                rollingSummaryJson = "",
                rollingSummaryTokenEstimate = record.rollingSummaryTokenEstimate,
                memoryLedgerStatus = record.memoryLedgerStatus,
                memoryLedgerError = record.memoryLedgerError,
                lastCompressedMessageIndex = record.lastCompressedMessageIndex,
                updatedAt = Instant.ofEpochMilli(record.lastCompressedAt),
            ),
            memoryIndexState = ConversationMemoryIndexState(
                lastIndexStatus = record.lastIndexStatus,
                lastIndexedAt = Instant.ofEpochMilli(record.lastIndexedAt),
                lastIndexError = record.lastIndexError,
            ),
            createAt = Instant.ofEpochMilli(record.createAt),
            updateAt = Instant.ofEpochMilli(record.updateAt),
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (_: SQLiteBlobTooBigException) {
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId),
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun loadCompressionEvents(conversationId: String): List<CompressionEvent> {
        val entities = compressionEventDAO.getEventsOfConversation(conversationId)
        if (entities.isEmpty()) return emptyList()
        val payloadsByEventId = compressionEventPayloadDAO.getPayloads(entities.map { it.id })
            .associateBy { it.eventId }
        return entities.map { entity ->
            entity.toModel().withPayload(payloadsByEventId[entity.id]?.toModel())
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            messageNodeEntity(conversationId, index, node)
        }
        if (entities.isNotEmpty()) {
            messageNodeDAO.insertAll(entities)
        }
    }

    private suspend fun updateConversationMetadataLocked(conversation: Conversation) {
        conversationDAO.update(conversationToConversationEntity(conversation))
        upsertCompressionPayloadLocked(conversation)
    }

    private suspend fun upsertCompressionPayloadLocked(conversation: Conversation) {
        conversationCompressionPayloadDAO.insert(
            ConversationCompressionPayloadEntity(
                conversationId = conversation.id.toString(),
                dialogueSummaryText = conversation.compressionState.dialogueSummaryText,
                rollingSummaryJson = conversation.compressionState.rollingSummaryJson,
            )
        )
    }

    private suspend fun syncMessageNodesLocked(
        conversationId: String,
        nodes: List<MessageNode>,
    ): MessageNodeSyncResult {
        val existing = messageNodeDAO.getNodesOfConversation(conversationId)
        val existingById = existing.associateBy { it.id }
        val nextEntities = nodes.mapIndexed { index, node ->
            messageNodeEntity(conversationId, index, node)
        }
        val changedEntities = nextEntities.filter { next ->
            val current = existingById[next.id] ?: return@filter true
            current.nodeIndex != next.nodeIndex ||
                current.messages != next.messages ||
                current.selectIndex != next.selectIndex
        }
        val deletedNodeIds = existingById.keys.subtract(nextEntities.mapTo(hashSetOf()) { it.id }).toList()

        if (deletedNodeIds.isNotEmpty()) {
            messageNodeDAO.deleteByIds(deletedNodeIds)
        }
        if (changedEntities.isNotEmpty()) {
            messageNodeDAO.insertAll(changedEntities)
        }

        val changedNodesById = nodes.associateBy { it.id.toString() }
        return MessageNodeSyncResult(
            changedNodes = changedEntities.mapNotNull { changedNodesById[it.id] },
            deletedNodeIds = deletedNodeIds,
        )
    }

    private fun messageNodeEntity(
        conversationId: String,
        index: Int,
        node: MessageNode,
    ): MessageNodeEntity {
        return MessageNodeEntity(
            id = node.id.toString(),
            conversationId = conversationId,
            nodeIndex = index,
            messages = JsonInstant.encodeToString(node.messages),
            selectIndex = node.selectIndex,
        )
    }

    private fun ConversationCompressionPayloadEntity.toModel(): ConversationCompressionPayload {
        return ConversationCompressionPayload(
            dialogueSummaryText = dialogueSummaryText,
            rollingSummaryJson = rollingSummaryJson,
        )
    }

    private fun CompressionEventEntity.toModel(): CompressionEvent {
        return CompressionEvent(
            id = id,
            boundaryIndex = boundaryIndex,
            dialogueSummaryPreview = dialogueSummaryPreview,
            compressStartIndex = compressStartIndex,
            compressEndIndex = compressEndIndex,
            keepRecentMessages = keepRecentMessages,
            trigger = trigger,
            additionalPrompt = additionalPrompt,
            createdAt = Instant.ofEpochMilli(createdAt),
        )
    }

    private fun CompressionEventPayloadEntity.toModel(): CompressionEventPayload {
        return CompressionEventPayload(
            dialogueSummaryText = dialogueSummaryText,
            ledgerSnapshot = ledgerSnapshot,
            summarySnapshot = summarySnapshot,
            baseDialogueSummaryText = baseDialogueSummaryText,
            baseLedgerJson = baseLedgerJson,
            baseSummaryJson = baseSummaryJson,
        )
    }
}

private data class MessageNodeSyncResult(
    val changedNodes: List<MessageNode>,
    val deletedNodeIds: List<String>,
)

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val workflowState: String? = null,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
