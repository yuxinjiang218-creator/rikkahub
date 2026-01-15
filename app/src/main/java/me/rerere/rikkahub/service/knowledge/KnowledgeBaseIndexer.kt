package me.rerere.rikkahub.service.knowledge

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.db.dao.KnowledgeChunkDao
import me.rerere.rikkahub.data.db.dao.KnowledgeDocumentDao
import me.rerere.rikkahub.data.db.dao.KnowledgeVectorDao
import me.rerere.rikkahub.data.db.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.data.db.entity.DocumentStatus
import me.rerere.rikkahub.data.db.entity.KnowledgeVectorEntity
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.knowledge.DocumentExtractor
import me.rerere.rikkahub.service.knowledge.DocumentParseException
import me.rerere.rikkahub.service.knowledge.TextChunker
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * 知识库索引器
 * 负责文档导入、解析、分块、embedding 生成和数据库写入
 *
 * 流程（不可乱序）：
 * 1. status = INDEXING
 * 2. 检查 embeddingModelId（null → FAILED）
 * 3. 解析全文 → 分块（不截断）
 * 4. 获取 provider + embedding model
 * 5. 对每个 chunk 生成 embedding
 * 6. 计算 norm
 * 7. 清理旧 chunk/vector（重建时）
 * 8. 写入 chunks + vectors
 * 9. status = READY；写 embeddingModelId
 * 10. 异常 → FAILED + errorMessage
 */
class KnowledgeBaseIndexer(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val documentDao: KnowledgeDocumentDao,
    private val chunkDao: KnowledgeChunkDao,
    private val vectorDao: KnowledgeVectorDao,
    private val providerManager: ProviderManager
) {

    companion object {
        private const val TAG = "KnowledgeBaseIndexer"
    }

    /**
     * 索引单个文档
     */
    suspend fun indexDocument(documentId: String): IndexResult = withContext(Dispatchers.IO) {
        val document = documentDao.getById(documentId)
            ?: return@withContext IndexResult.Error("Document not found: $documentId")

        try {
            // 1. 更新状态为 INDEXING
            documentDao.update(document.copy(status = DocumentStatus.INDEXING.name))

            // 2. 读取 embeddingModelId
            val settings = settingsStore.settingsFlow.first()
            val embeddingModelId = settings.embeddingModelId
            if (embeddingModelId == null) {
                failed(document, "Embedding model not configured")
                return@withContext IndexResult.Error("Embedding model not configured")
            }

            // 3. 解析全文 → 分块
            val file = File(document.localPath)
            if (!file.exists()) {
                failed(document, "File not found: ${document.localPath}")
                return@withContext IndexResult.Error("File not found")
            }

            val fullText = DocumentExtractor.extractText(file, document.mime)
            val chunks = TextChunker.chunk(fullText)

            if (chunks.isEmpty()) {
                failed(document, "No chunks extracted from document")
                return@withContext IndexResult.Error("No content extracted")
            }

            // 4. 找 provider + embedding model
            val providerSetting: ProviderSetting? = settings.providers.find { provider: ProviderSetting ->
                provider.models.any { model -> model.id == embeddingModelId }
            }
            if (providerSetting == null) {
                failed(document, "Provider not found for model: $embeddingModelId")
                return@withContext IndexResult.Error("Provider not found")
            }

            val embeddingModel: Model? = providerSetting.models.find { model -> model.id == embeddingModelId }
            if (embeddingModel == null || embeddingModel.type != ModelType.EMBEDDING) {
                failed(document, "Embedding model not found: $embeddingModelId")
                return@withContext IndexResult.Error("Model not found or not embedding type")
            }

            // 5-6. 对每个 chunk 生成 embedding + 计算 norm
            val chunkEntities = mutableListOf<KnowledgeChunkEntity>()
            val vectorEntities = mutableListOf<KnowledgeVectorEntity>()

            chunks.forEach { chunk ->
                val chunkId = kotlin.uuid.Uuid.random().toString()

                // 生成 embedding
                val embedding = generateEmbedding(chunk.text, embeddingModel!!, providerSetting)
                val norm = computeNorm(embedding)

                chunkEntities.add(
                    KnowledgeChunkEntity(
                        id = chunkId,
                        documentId = documentId,
                        assistantId = document.assistantId,
                        chunkIndex = chunk.index,
                        text = chunk.text,
                        charCount = chunk.charCount
                    )
                )

                vectorEntities.add(
                    KnowledgeVectorEntity(
                        id = kotlin.uuid.Uuid.random().toString(),
                        chunkId = chunkId,
                        assistantId = document.assistantId,
                        embeddingModelId = embeddingModelId.toString(),
                        embeddingVector = embedding,
                        vectorNorm = norm
                    )
                )
            }

            // 7. 清理旧 chunk/vector（重建时）
            chunkDao.deleteByDocumentId(documentId)
            vectorDao.deleteByDocumentId(documentId)

            // 8. 写入 chunks + vectors
            chunkDao.insertAll(chunkEntities)
            vectorDao.insertAll(vectorEntities)

            // 9. 更新状态为 READY
            documentDao.update(
                document.copy(
                    status = DocumentStatus.READY.name,
                    embeddingModelId = embeddingModelId.toString()
                )
            )

            IndexResult.Success(
                documentId = documentId,
                chunksCount = chunks.size,
                embeddingModelId = embeddingModelId.toString()
            )

        } catch (e: DocumentParseException) {
            failed(document, "Parse failed: ${e.message}")
            IndexResult.Error("Parse failed: ${e.message}")
        } catch (e: Exception) {
            failed(document, "Indexing failed: ${e.message}")
            IndexResult.Error("Indexing failed: ${e.message}")
        }
    }

    /**
     * 导入新文档
     *
     * @param assistantId 所属 assistant ID
     * @param uri 文件 URI
     * @param fileName 文件名
     * @param mime MIME 类型
     * @return IndexResult
     */
    suspend fun importDocument(
        assistantId: String,
        uri: Uri,
        fileName: String,
        mime: String
    ): IndexResult = withContext(Dispatchers.IO) {
        try {
            // 检查 MIME 类型是否支持
            if (!DocumentExtractor.isSupported(mime)) {
                return@withContext IndexResult.Error("Unsupported MIME type: $mime")
            }

            // 拷贝文件到私有目录
            val destDir = File(context.filesDir, "knowledge_base/$assistantId")
            destDir.mkdirs()

            val destFile = File(destDir, "${System.currentTimeMillis()}_${fileName}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext IndexResult.Error("Failed to open file")

            // 计算 contentHash
            val contentHash = computeSha256(destFile)

            // 检查是否重复（同 assistant 下 hash 相同）
            val existing = documentDao.getByAssistantIdAndContentHash(assistantId, contentHash)
            if (existing != null) {
                destFile.delete() // 删除拷贝的文件
                return@withContext when (existing.status) {
                    DocumentStatus.READY.name -> {
                        IndexResult.AlreadyExists(existing.id)
                    }
                    else -> {
                        // 重新索引失败的文档
                        indexDocument(existing.id)
                    }
                }
            }

            // 创建文档记录
            val document = KnowledgeDocumentEntity(
                id = kotlin.uuid.Uuid.random().toString(),
                assistantId = assistantId,
                fileName = fileName,
                mime = mime,
                localPath = destFile.absolutePath,
                sizeBytes = destFile.length(),
                contentHash = contentHash,
                createdAt = System.currentTimeMillis(),
                status = DocumentStatus.PENDING.name
            )

            documentDao.insert(document)

            // 开始索引
            indexDocument(document.id)

        } catch (e: Exception) {
            IndexResult.Error("Import failed: ${e.message}")
        }
    }

    /**
     * 重新索引文档
     */
    suspend fun reindexDocument(documentId: String): IndexResult {
        return indexDocument(documentId)
    }

    /**
     * 删除文档（级联删除 chunk 和 vector）
     */
    suspend fun deleteDocument(documentId: String) {
        val document = documentDao.getById(documentId) ?: return

        // 删除本地文件
        try {
            File(document.localPath).delete()
        } catch (e: Exception) {
            // 忽略文件删除失败
        }

        // 删除数据库记录（CASCADE 会自动删除 chunk 和 vector）
        documentDao.deleteById(documentId)
    }

    /**
     * 生成 embedding
     */
    private suspend fun generateEmbedding(
        text: String,
        model: Model,
        providerSetting: ProviderSetting
    ): FloatArray {
        val provider = providerManager.getProviderByType(providerSetting)
        return provider.generateEmbedding(
            providerSetting = providerSetting,
            text = text,
            params = EmbeddingGenerationParams(model = model)
        )
    }

    /**
     * 计算向量范数（用于余弦相似度）
     */
    private fun computeNorm(vector: FloatArray): Float {
        var sum = 0f
        for (v in vector) {
            sum += v * v
        }
        return sqrt(sum)
    }

    /**
     * 计算 SHA-256 hash
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 更新文档状态为 FAILED
     */
    private suspend fun failed(document: KnowledgeDocumentEntity, errorMessage: String) {
        documentDao.update(
            document.copy(
                status = DocumentStatus.FAILED.name,
                errorMessage = errorMessage
            )
        )
    }

    /**
     * 获取指定 assistant 的所有文档
     */
    fun getDocumentsByAssistantId(assistantId: String) = documentDao.getByAssistantId(assistantId)

    /**
     * 索引结果
     */
    sealed class IndexResult {
        data class Success(
            val documentId: String,
            val chunksCount: Int,
            val embeddingModelId: String
        ) : IndexResult()

        data class AlreadyExists(val documentId: String) : IndexResult()

        data class Error(val message: String) : IndexResult()
    }
}
