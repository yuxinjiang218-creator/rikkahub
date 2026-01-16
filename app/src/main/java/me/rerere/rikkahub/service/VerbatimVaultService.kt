package me.rerere.rikkahub.service

import android.content.Context
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VerbatimArtifactDao
import me.rerere.rikkahub.data.db.entity.MessageNodeTextEntity
import me.rerere.rikkahub.data.db.entity.VerbatimArtifactEntity
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.LogLevel
import me.rerere.rikkahub.util.normalizeForSearch
import java.security.MessageDigest
import java.util.UUID

/**
 * Verbatim Vault 服务（P 层构建服务）
 *
 * 职责：
 * 1. 每次消息写入时同步构建 message_node_text
 * 2. 同步构建倒排索引（message_token_index）兜底
 * 3. 扫描并生成 verbatim_artifact
 * 4. 判定 artifact 类型（写死规则）
 */
class VerbatimVaultService(
    private val context: Context,
    private val messageNodeTextDao: MessageNodeTextDao,
    private val verbatimArtifactDao: VerbatimArtifactDao,
    private val database: AppDatabase
) {
    /**
     * 构建消息节点文本（同步 message_node_text + 倒排索引）
     *
     * @param nodeId 消息节点 ID
     * @param conversationId 会话 ID
     * @param nodeIndex 消息索引
     * @param messages 消息列表
     */
    suspend fun buildMessageNodeText(
        nodeId: String,
        conversationId: String,
        nodeIndex: Int,
        messages: List<UIMessage>
    ) {
        val debugLogger = DebugLogger.getInstance(context)

        android.util.Log.i("VerbatimVault", "=== buildMessageNodeText START ===")
        android.util.Log.i("VerbatimVault", "nodeId: $nodeId")
        android.util.Log.i("VerbatimVault", "conversationId: $conversationId")
        android.util.Log.i("VerbatimVault", "nodeIndex: $nodeIndex")
        android.util.Log.i("VerbatimVault", "messages count: ${messages.size}")

        if (messages.isEmpty()) {
            android.util.Log.w("VerbatimVault", "messages.isEmpty(), returning")
            return
        }

        val firstMessage = messages.first()
        val roleInt = firstMessage.role.ordinal
        android.util.Log.i("VerbatimVault", "=== Storing message: nodeIndex=$nodeIndex ===")
        android.util.Log.i("VerbatimVault", "firstMessage role: ${firstMessage.role.name} (ordinal=$roleInt)")
        android.util.Log.i("VerbatimVault", "firstMessage parts count: ${firstMessage.parts.size}")

        // 提取文本和工具调用（从 parts 数组）
        val textBuilder = StringBuilder()
        for (part in firstMessage.parts) {
            when (part) {
                is UIMessagePart.Text -> {
                    textBuilder.append(part.text)
                }
                is UIMessagePart.ToolCall -> {
                    // 工具调用：记录工具名称和参数
                    textBuilder.append("[调用工具: ${part.toolName}")
                    if (part.arguments.isNotEmpty()) {
                        val argsPreview = part.arguments.take(100)  // 限制参数长度
                        textBuilder.append(" 参数: $argsPreview")
                    }
                }
                is UIMessagePart.ToolResult -> {
                    // 工具结果：记录工具名称和结果摘要
                    textBuilder.append("[工具结果: ${part.toolName}")
                    val contentStr = part.content.toString()
                    if (contentStr.isNotEmpty()) {
                        val preview = contentStr.take(200)  // 限制结果长度
                        textBuilder.append(" 结果: $preview")
                    }
                }
                else -> {
                    // 其他类型（如Image、Document等）不加入P层
                }
            }
        }

        val rawText = textBuilder.toString()
        android.util.Log.i("VerbatimVault", "rawText length: ${rawText.length}")
        android.util.Log.i("VerbatimVault", "rawText preview (first 200): ${rawText.take(200)}")

        if (rawText.isNotEmpty()) {
            // 归一化搜索文本
            val searchText = normalizeForSearch(rawText)

            // 写入 message_node_text（触发器自动同步 FTS）
            val entity = MessageNodeTextEntity(
                nodeId = nodeId,
                conversationId = conversationId,
                nodeIndex = nodeIndex,
                role = roleInt,
                rawText = rawText,
                searchText = searchText
            )
            android.util.Log.i("VerbatimVault", "Inserting MessageNodeTextEntity: nodeIndex=$nodeIndex, length=${rawText.length}")
            messageNodeTextDao.insertOrReplace(entity)
            android.util.Log.i("VerbatimVault", "Insertion successful")

            // 手动同步到FTS4表（绕过 INSERT OR REPLACE 与触发器的冲突）
            // 问题：INSERT OR REPLACE 会先 DELETE 再 INSERT，但 FTS4 触发器只监听 INSERT
            // 导致更新记录时，FTS4 索引可能不同步
            try {
                val db = database.openHelper.writableDatabase

                // 查询 message_node_text 的 rowid（作为 FTS4 的 docid）
                val cursor = db.query(
                    "SELECT rowid FROM message_node_text WHERE node_id = ?",
                    arrayOf(nodeId)
                )
                var rowId: Long? = null
                if (cursor.moveToFirst()) {
                    rowId = cursor.getLong(0)
                }
                cursor.close()

                if (rowId != null) {
                    // 先删除 FTS4 中所有相同 node_id 的记录（清理可能的孤儿记录）
                    // 使用 node_id 而不是 docid，因为 INSERT OR REPLACE 可能导致 rowid 变化
                    db.execSQL(
                        "DELETE FROM message_node_fts WHERE node_id = ?",
                        arrayOf(nodeId)
                    )

                    // 再插入新记录到 FTS4（使用 rowid 作为 docid）
                    db.execSQL(
                        """INSERT INTO message_node_fts(docid, search_text, node_id, conversation_id, node_index, role)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                        arrayOf(rowId, searchText, nodeId, conversationId, nodeIndex, roleInt)
                    )
                    android.util.Log.i("VerbatimVault", "FTS4 manual sync successful for node $nodeIndex (rowid=$rowId, role=$roleInt, roleName=${firstMessage.role.name})")
                } else {
                    android.util.Log.w("VerbatimVault", "Failed to get rowid for node $nodeIndex, FTS4 sync skipped")
                }
            } catch (e: Exception) {
                android.util.Log.e("VerbatimVault", "FTS4 manual sync failed for node $nodeIndex", e)
            }

            // 写入倒排索引（兜底方案）
            buildInvertedIndex(
                nodeId = nodeId,
                conversationId = conversationId,
                nodeIndex = nodeIndex,
                searchText = searchText
            )

            android.util.Log.i("VerbatimVault", "=== buildMessageNodeText SUCCESS (nodeIndex=$nodeIndex, length=${rawText.length}) ===")

            debugLogger.log(
                LogLevel.DEBUG,
                "VerbatimVault",
                "P-layer node text built",
                mapOf(
                    "nodeIndex" to nodeIndex,
                    "charCount" to rawText.length,
                    "role" to firstMessage.role.name
                )
            )

            // 如果是用户消息，尝试生成 verbatim_artifact
            if (firstMessage.role.name == "USER") {
                buildVerbatimArtifact(
                    nodeId = nodeId,
                    conversationId = conversationId,
                    nodeIndex = nodeIndex,
                    rawText = rawText
                )
            }
        }
    }

    /**
     * 构建倒排索引（写入 message_token_index）
     *
     * @param nodeId 消息节点 ID
     * @param conversationId 会话 ID
     * @param nodeIndex 消息索引
     * @param searchText 归一化后的搜索文本
     */
    private suspend fun buildInvertedIndex(
        nodeId: String,
        conversationId: String,
        nodeIndex: Int,
        searchText: String
    ) {
        // 分词：最多取 64 个 token
        val tokens = searchText.split(" ")
            .filter { it.isNotBlank() }
            .distinct()
            .take(64)

        if (tokens.isEmpty()) return

        // 批量插入倒排索引（异步执行，无需显式事务）
        // 由于 buildMessageNodeText 现在是异步执行的，不会有外层事务冲突
        val db = database.openHelper.writableDatabase
        for (token in tokens) {
            db.execSQL(
                """INSERT OR REPLACE INTO message_token_index
                (token, conversation_id, node_index, node_id)
                VALUES (?, ?, ?, ?)""",
                arrayOf(token, conversationId, nodeIndex, nodeId)
            )
        }
    }

    /**
     * 构建逐字素材（生成 verbatim_artifact）
     *
     * @param nodeId 消息节点 ID
     * @param conversationId 会话 ID
     * @param nodeIndex 消息索引
     * @param rawText 原始文本
     */
    private suspend fun buildVerbatimArtifact(
        nodeId: String,
        conversationId: String,
        nodeIndex: Int,
        rawText: String
    ) {
        val debugLogger = DebugLogger.getInstance(context)

        // 判定 artifact 类型（写死规则）
        val artifactType = detectArtifactType(rawText) ?: return

        // 提取标题（书名号内容）
        val title = extractTitle(rawText)

        // 计算内容哈希
        val sha256 = calculateSha256(rawText)

        val artifactId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // 写入 verbatim_artifact（每条 artifact 只覆盖单节点，start==end）
        val entity = VerbatimArtifactEntity(
            id = artifactId,
            conversationId = conversationId,
            title = title,
            type = artifactType,
            startNodeIndex = nodeIndex,
            endNodeIndex = nodeIndex,  // start==end
            contentSha256 = sha256,
            createdAt = now,
            updatedAt = now
        )
        verbatimArtifactDao.insertOrReplace(entity)

        debugLogger.log(
            LogLevel.DEBUG,
            "VerbatimVault",
            "P-layer artifact created",
            mapOf(
                "artifactId" to artifactId,
                "nodeIndex" to nodeIndex,
                "type" to artifactType,
                "title" to (title ?: "null"),
                "charCount" to rawText.length
            )
        )
    }

    /**
     * 判定 artifact 类型（写死阈值）
     *
     * 规则：
     * - CODE: 包含代码围栏 (```) 且字符数 >= 200
     * - POEM: 行数 >= 6 且字符数在 [300, 6000] 之间
     * - LONG_TEXT: 字符数 >= 800
     *
     * @param text 文本内容
     * @return artifact 类型（1=POEM, 2=CODE, 3=LONG_TEXT），null 表示不符合规则
     */
    private fun detectArtifactType(text: String): Int? {
        val hasCodeFence = text.contains("```")
        val charLen = text.length
        val lineCount = text.count { it == '\n' } + 1

        return when {
            hasCodeFence && charLen >= 200 -> 2  // CODE
            lineCount >= 6 && charLen >= 300 && charLen <= 6000 -> 1  // POEM
            charLen >= 800 -> 3  // LONG_TEXT
            else -> null
        }
    }

    /**
     * 提取书名号标题
     *
     * @param text 文本内容
     * @return 提取的标题，null 表示未找到
     */
    private fun extractTitle(text: String): String? {
        val pattern = Regex("《([^》]{1,40})》")
        val match = pattern.find(text)
        return match?.groupValues?.get(1)
    }

    /**
     * 删除会话的所有逐字素材
     *
     * @param conversationId 会话 ID
     */
    suspend fun deleteArtifactsByConversation(conversationId: String) {
        verbatimArtifactDao.deleteByConversationId(conversationId)
    }

    /**
     * 删除会话的所有 P 层文本（含倒排索引和 FTS）
     *
     * @param conversationId 会话 ID
     */
    suspend fun deleteMessageNodeTextByConversation(conversationId: String) {
        // 先删除 FTS 数据（无触发器，手动删除）
        val ftsDeleteQuery = androidx.sqlite.db.SimpleSQLiteQuery(
            "DELETE FROM message_node_fts WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        messageNodeTextDao.deleteFtsByConversationId(ftsDeleteQuery)

        // 删除 P 层文本
        messageNodeTextDao.deleteByConversationId(conversationId)

        // 删除倒排索引（使用 RawQuery）
        val deleteQuery = androidx.sqlite.db.SimpleSQLiteQuery(
            "DELETE FROM message_token_index WHERE conversation_id = ?",
            arrayOf(conversationId)
        )
        messageNodeTextDao.deleteInvertedIndexByConversationId(deleteQuery)
    }

    /**
     * 计算内容哈希（SHA-256）
     *
     * @param text 文本内容
     * @return 十六进制哈希字符串
     */
    private fun calculateSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
