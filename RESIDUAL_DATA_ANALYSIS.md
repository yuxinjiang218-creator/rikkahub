# 残留数据问题分析报告

## 1. 删除对话的清理逻辑

### 当前实现
```kotlin
suspend fun deleteConversation(conversation: Conversation) {
    database.withTransaction {
        // 1. 删除逐字素材
        verbatimVaultService.deleteArtifactsByConversation(conversationIdStr)

        // 2. 删除 P 层文本（含倒排索引和 FTS）
        verbatimVaultService.deleteMessageNodeTextByConversation(conversationIdStr)

        // 3. 删除向量索引
        archiveSummaries.forEach { archive ->
            vectorIndexDao.deleteByArchiveId(archive.id)
        }

        // 4. 删除归档摘要
        archiveSummaryDao.deleteByConversationId(conversationIdStr)

        // 5. 删除会话（message_node 级联删除）
        conversationDAO.delete(...)
    }
}
```

### 评估
✅ **完整且正确**

所有相关表都会被清理：
- `verbatim_artifact` ✅
- `message_node_text` ✅
- `message_node_fts` ✅（通过触发器）
- `message_token_index` ✅
- `vector_index` ✅
- `archive_summary` ✅
- `message_node` ✅（级联）
- `conversation` ✅

**无残留数据风险**

---

## 2. 编辑消息的处理逻辑

### 当前实现

#### 2.1 编辑消息 (ChatVM.handleMessageEdit)
```kotlin
fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
    val newConversation = conversation.value.copy(
        messageNodes = conversation.value.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            node.copy(
                messages = node.messages + UIMessage(...),  // 追加新消息
                selectIndex = node.messages.size
            )
        },
    )
    viewModelScope.launch {
        chatService.saveConversation(_conversationId, newConversation)
    }
}
```

**行为**：在现有 node 的 messages 列表后追加新消息，**不删除旧消息**

#### 2.2 保存并更新 (ChatService.saveConversation → ConversationRepository.updateConversation)
```kotlin
suspend fun updateConversation(conversation: Conversation) {
    database.withTransaction {
        conversationDAO.update(conversationToConversationEntity(conversation))

        // 删除旧的节点
        messageNodeDAO.deleteByConversation(conversation.id.toString())

        // 插入新的节点并构建 P 层
        saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
    }
}

private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
    val entities = nodes.mapIndexed { index, node ->
        MessageNodeEntity(id = node.id.toString(), ...)
    }
    messageNodeDAO.insertAll(entities)

    // 同步构建 P 层
    entities.forEach { entity ->
        verbatimVaultService.buildMessageNodeText(
            nodeId = entity.id,
            conversationId = conversationId,
            nodeIndex = entity.nodeIndex,
            messages = JsonInstant.decodeFromString(entity.messages)
        )
    }
}
```

#### 2.3 P 层构建 (VerbatimVaultService.buildMessageNodeText)
```kotlin
suspend fun buildMessageNodeText(...) {
    val entity = MessageNodeTextEntity(
        nodeId = nodeId,
        conversationId = conversationId,
        ...
    )
    messageNodeTextDao.insertOrReplace(entity)  // 关键：insertOrReplace
    buildInvertedIndex(...)
}
```

### 问题识别

#### 问题 1：P 层数据残留（严重）

**场景**：
1. 初始状态：conversation 有 node1, node2, node3, node4
2. 用户编辑 node3 的消息
3. `handleMessageEdit` 追加新消息到 node3
4. 用户**删除** node4（手动删除对话历史）
5. `updateConversation` 调用：
   - 删除所有 message_node
   - 重新插入 node1, node2, node3（node4 被删除）
6. `saveMessageNodes` 只构建 node1, node2, node3 的 P 层
7. **残留**：`message_node_text` 和 `message_node_fts` 中 node4 的记录未被删除

**根本原因**：
- `message_node_text` 的外键关联到 `conversation`，不是 `message_node`
- `message_node` 被删除不会级联删除 `message_node_text`
- `insertOrReplace` 只更新/插入，不删除旧记录

**影响**：
- 数据库膨胀
- 搜索可能返回已删除的消息
- 潜在的数据不一致

#### 问题 2：编辑后需要手动重新生成（用户体验）

**当前流程**：
1. 用户编辑消息 → `handleMessageEdit` → 追加新消息
2. 用户需要手动点击"重新生成" → `regenerateAtMessage` → 删除后续节点并重新生成

**问题**：
- 编辑消息后，对话不会自动继续
- 用户需要额外操作
- 容易忘记重新生成，导致对话不一致

---

## 3. 解决方案建议

### 3.1 修复 P 层残留问题

#### 方案 A：在 updateConversation 中清理 P 层
```kotlin
suspend fun updateConversation(conversation: Conversation) {
    database.withTransaction {
        conversationDAO.update(conversationToConversationEntity(conversation))

        // 删除旧的节点
        messageNodeDAO.deleteByConversation(conversation.id.toString())

        // 删除旧的 P 层数据（新增）
        messageNodeTextDao.deleteByConversationId(conversation.id.toString())

        // 插入新的节点并构建 P 层
        saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
    }
}
```

#### 方案 B：在 saveMessageNodes 中清理
```kotlin
private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
    // 先删除旧的 P 层数据
    messageNodeTextDao.deleteByConversationId(conversationId)

    // 然后插入新的
    val entities = nodes.mapIndexed { index, node ->
        MessageNodeEntity(...)
    }
    messageNodeDAO.insertAll(entities)

    // 构建新的 P 层
    entities.forEach { entity ->
        verbatimVaultService.buildMessageNodeText(...)
    }
}
```

**推荐方案 A**，因为：
- 逻辑更清晰
- 与删除对话的清理逻辑一致
- 保证事务完整性

### 3.2 优化编辑消息的处理方式

#### 方案 A：编辑后自动重新生成
```kotlin
fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
    // ... 处理编辑 ...

    val newConversation = conversation.value.copy(...)
    viewModelScope.launch {
        chatService.saveConversation(_conversationId, newConversation)
        // 自动触发重新生成
        chatService.regenerateAtMessage(_conversationId, editedMessage)
    }
}
```

#### 方案 B：UI 提示
在编辑消息后，UI 上明确提示用户：
- "消息已编辑，请点击重新生成以继续对话"

**推荐方案 B**，因为：
- 不改变现有架构
- 给用户更多控制权
- 避免意外的自动行为

---

## 4. 验证方法

### 4.1 验证残留数据问题

**测试步骤**：
1. 创建对话，发送多条消息
2. 检查数据库，确认所有 P 层数据存在
3. 编辑某条消息
4. 手动删除后续消息（通过 UI 或直接操作数据库）
5. 检查数据库，确认被删除消息的 P 层数据是否还存在

**预期**：
- 当前实现：残留数据存在
- 修复后：无残留数据

### 4.2 验证删除对话完整性

**测试步骤**：
1. 创建对话，发送消息
2. 检查所有相关表的数据
3. 删除对话
4. 检查所有相关表，确认数据已清理

**预期**：所有数据都被清理

---

## 5. 总结

| 问题 | 严重程度 | 影响 | 解决方案 |
|------|---------|------|---------|
| 删除对话残留数据 | 低 | 无（当前实现完整） | 无需修改 |
| 编辑消息残留数据 | **高** | 数据库膨胀、搜索异常 | 需在 updateConversation 中清理 P 层 |
| 编辑后需手动重新生成 | 中 | 用户体验 | UI 提示或自动重新生成 |

**优先级**：
1. 修复编辑消息的 P 层残留问题（高优先级）
2. 优化编辑后的用户体验（中优先级）
