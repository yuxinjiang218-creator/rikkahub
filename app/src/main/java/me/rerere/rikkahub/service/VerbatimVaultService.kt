package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VerbatimArtifactDao
import me.rerere.rikkahub.data.db.entity.MessageNodeTextEntity
import me.rerere.rikkahub.data.db.entity.VerbatimArtifactEntity
import me.rerere.rikkahub.util.normalizeForSearch
import java.security.MessageDigest
import java.util.UUID

/**
 * Verbatim Vault 服务（P 层构建服务）
 *
 * 职责：
 * 1. 每次消息写入时同步构建 message_node_text
 * 2. 扫描并生成 verbatim_artifact
 * 3. 判定 artifact 类型（写死规则）
 */
class VerbatimVaultService(
    private val messageNodeTextDao: MessageNodeTextDao,
    private val verbatimArtifactDao: VerbatimArtifactDao
) {
    /**
     * 构建消息节点文本（同步 message_node_text）
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
        if (messages.isEmpty()) return

        val firstMessage = messages.first()
        val roleInt = firstMessage.role.ordinal

        // 提取文本（从 parts 数组）
        val textBuilder = StringBuilder()
        for (part in firstMessage.parts) {
            if (part is UIMessagePart.Text) {
                textBuilder.append(part.text)
            }
        }

        val rawText = textBuilder.toString()
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
            messageNodeTextDao.insertOrReplace(entity)

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
