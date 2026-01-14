package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "Migration_14_15"

/**
 * 数据库迁移 14 → 15
 *
 * 新增功能：
 * 1. 逐字根源表 (message_node_text)
 * 2. FTS4 全文检索 (message_node_fts) - 替代 FTS5
 * 3. 倒排索引兜底表 (message_token_index)
 * 4. 逐字素材库 (verbatim_artifact)
 * 5. 会话级归档回填记录 (lastArchiveRecallIds)
 *
 * 目的：
 * - 实现逐字可回收的 P 层（Verbatim Vault）
 * - 支持 FTS4 全文检索（兼容所有设备）
 * - 支持倒排索引兜底（FTS4 不可用时）
 * - 支持 VERBATIM/SEMANTIC 双路径互斥
 */
val Migration_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ========================================
        // 1. 创建 message_node_text 逐字根源表
        // ========================================
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_node_text (
                node_id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                node_index INTEGER NOT NULL,
                role INTEGER NOT NULL,
                raw_text TEXT NOT NULL,
                search_text TEXT NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversationentity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mnt_conv_idx ON message_node_text(conversation_id, node_index)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mnt_conv_role ON message_node_text(conversation_id, role)")

        // ========================================
        // 2. 创建 FTS4 虚拟表（兼容所有设备）
        // ========================================
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS message_node_fts
            USING fts4(
                search_text,
                node_id UNINDEXED,
                conversation_id UNINDEXED,
                node_index UNINDEXED,
                role UNINDEXED
            )
            """.trimIndent()
        )

        // ========================================
        // 3. 创建 FTS INSERT 触发器
        // ========================================
        // 注意：只创建 INSERT 触发器，DELETE 和 UPDATE 手动处理
        // 因为 FTS4 的 DELETE 触发器在某些设备上不稳定（会报 SQL logic error）
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS mnt_ai AFTER INSERT ON message_node_text BEGIN
                INSERT INTO message_node_fts(docid, search_text, node_id, conversation_id, node_index, role)
                VALUES (new.rowid, new.search_text, new.node_id, new.conversation_id, new.node_index, new.role);
            END
            """.trimIndent()
        )

        // ========================================
        // 4. 创建倒排索引兜底表（FTS4 不可用时使用）
        // ========================================
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_token_index (
                token TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                node_index INTEGER NOT NULL,
                node_id TEXT NOT NULL,
                PRIMARY KEY (token, conversation_id, node_index)
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_token_conv ON message_token_index(conversation_id, token)")

        // ========================================
        // 5. 创建 verbatim_artifact 逐字素材库表
        // ========================================
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS verbatim_artifact (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                title TEXT,
                type INTEGER NOT NULL,
                start_node_index INTEGER NOT NULL,
                end_node_index INTEGER NOT NULL,
                content_sha256 TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversationentity(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_va_conv ON verbatim_artifact(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_va_title ON verbatim_artifact(conversation_id, title)")

        // ========================================
        // 6. 新增 conversationentity 字段
        // ========================================
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN lastArchiveRecallIds TEXT DEFAULT '[]'")

        // ========================================
        // 7. 数据迁移：遍历现有 message_node，同步构建 P 层
        // ========================================
        db.beginTransaction()
        try {
            // 7.1 遍历所有 conversation
            val cursorConv = db.query("SELECT id FROM conversationentity")
            while (cursorConv.moveToNext()) {
                val conversationId = cursorConv.getString(0)

                // 7.2 遍历该会话的所有 message_node
                val cursorNodes = db.query(
                    "SELECT id, messages, \"index\" FROM message_node WHERE conversation_id = ? ORDER BY \"index\" ASC",
                    arrayOf(conversationId)
                )

                while (cursorNodes.moveToNext()) {
                    val nodeId = cursorNodes.getString(0)
                    val messagesJson = cursorNodes.getString(1)
                    val nodeIndex = cursorNodes.getInt(2)

                    // 7.3 解析 JSON，提取 currentMessage 的 role 和 text
                    try {
                        val jsonObject = JSONObject(messagesJson)
                        val messagesArray = jsonObject.optJSONArray("messages") ?: continue

                        if (messagesArray.length() > 0) {
                            val firstMessage = messagesArray.getJSONObject(0)
                            val roleInt = firstMessage.optInt("role", 0)

                            // 提取文本（从 parts 数组）
                            val partsArray = firstMessage.optJSONArray("parts") ?: continue
                            val textBuilder = StringBuilder()
                            for (i in 0 until partsArray.length()) {
                                val part = partsArray.getJSONObject(i)
                                if (part.optString("type") == "text") {
                                    textBuilder.append(part.optString("text", ""))
                                }
                            }

                            val rawText = textBuilder.toString()
                            if (rawText.isNotEmpty()) {
                                // 7.4 归一化搜索文本
                                val searchText = normalizeForSearchMigration(rawText)

                                // 7.5 写入 message_node_text（触发器自动同步 FTS）
                                db.execSQL(
                                    """INSERT OR REPLACE INTO message_node_text
                                    (node_id, conversation_id, node_index, role, raw_text, search_text)
                                    VALUES (?, ?, ?, ?, ?, ?)""",
                                    arrayOf(nodeId, conversationId, nodeIndex, roleInt, rawText, searchText)
                                )

                                // 7.6 写入倒排索引（兜底方案）
                                val tokens = searchText.split(" ").filter { it.isNotBlank() }.distinct().take(64)
                                for (token in tokens) {
                                    db.execSQL(
                                        """INSERT OR REPLACE INTO message_token_index
                                        (token, conversation_id, node_index, node_id)
                                        VALUES (?, ?, ?, ?)""",
                                        arrayOf(token, conversationId, nodeIndex, nodeId)
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 跳过解析失败的消息
                        android.util.Log.e(TAG, "Failed to parse message node: $nodeId", e)
                    }
                }

                cursorNodes.close()

                // 7.7 扫描 role=0 (USER) 的消息，生成 verbatim_artifact
                val cursorUserNodes = db.query(
                    """SELECT node_id, node_index, raw_text FROM message_node_text
                    WHERE conversation_id = ? AND role = 0
                    ORDER BY node_index ASC""",
                    arrayOf(conversationId)
                )

                while (cursorUserNodes.moveToNext()) {
                    val nodeId = cursorUserNodes.getString(0)
                    val nodeIndex = cursorUserNodes.getInt(1)
                    val rawText = cursorUserNodes.getString(2)

                    // 7.8 判定 artifact 类型（写死规则）
                    val artifactType = detectArtifactTypeMigration(rawText)
                    if (artifactType != null) {
                        // 7.9 提取标题（书名号内容）
                        val title = extractTitleMigration(rawText)

                        // 7.10 计算内容哈希
                        val sha256 = calculateSha256Migration(rawText)

                        val artifactId = UUID.randomUUID().toString()
                        val now = System.currentTimeMillis()

                        // 7.11 写入 verbatim_artifact（每条 artifact 只覆盖单节点，start==end）
                        db.execSQL(
                            """INSERT OR REPLACE INTO verbatim_artifact
                            (id, conversation_id, title, type, start_node_index, end_node_index, content_sha256, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            arrayOf(
                                artifactId, conversationId, title, artifactType,
                                nodeIndex, nodeIndex,  // start==end
                                sha256, now, now
                            )
                        )
                    }
                }

                cursorUserNodes.close()
            }

            cursorConv.close()

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // 辅助函数：归一化搜索文本（迁移版本）
    private fun normalizeForSearchMigration(input: String): String {
        val sb = StringBuilder(input.length * 2)
        for (ch in input) {
            when {
                ch.isWhitespace() -> sb.append(' ')
                ch.code in 0x4E00..0x9FFF -> { sb.append(ch).append(' ') }  // CJK
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    // 辅助函数：判定 artifact 类型（写死阈值）
    private fun detectArtifactTypeMigration(text: String): Int? {
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

    // 辅助函数：提取书名号标题
    private fun extractTitleMigration(text: String): String? {
        val pattern = Regex("《([^》]{1,40})》")
        val match = pattern.find(text)
        return match?.groupValues?.get(1)
    }

    // 辅助函数：计算 SHA-256
    private fun calculateSha256Migration(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
