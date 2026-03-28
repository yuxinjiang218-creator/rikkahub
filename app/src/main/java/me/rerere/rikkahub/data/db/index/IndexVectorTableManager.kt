package me.rerere.rikkahub.data.db.index

class IndexVectorTableManager(
    private val vectorStore: IndexVectorStore,
    private val vectorBackendVerifier: VectorBackendVerifier,
) {
    suspend fun ensureReady() {
        vectorStore.ensureReady()
    }

    suspend fun insertKnowledgeBaseVectors(
        dimension: Int,
        records: List<VectorInsertRecord>,
    ) {
        vectorStore.insertKnowledgeBaseVectors(dimension, records)
    }

    suspend fun insertMemoryVectors(
        dimension: Int,
        records: List<VectorInsertRecord>,
    ) {
        vectorStore.insertMemoryVectors(dimension, records)
    }

    suspend fun searchKnowledgeBaseDistances(
        candidateIds: List<Long>,
        queryEmbeddingJson: String,
        dimension: Int,
        limit: Int,
    ): Map<Long, Double> {
        return runCatching {
            vectorStore.searchKnowledgeBaseDistances(
                candidateIds = candidateIds,
                queryEmbeddingJson = queryEmbeddingJson,
                dimension = dimension,
                limit = limit,
            )
        }.getOrElse { error ->
            throw VectorSearchExecutionException(
                operation = "knowledge_base",
                tableName = buildKnowledgeBaseVectorTableName(dimension),
                dimension = dimension,
                candidateCount = candidateIds.size,
                message = "sqlite-vector search failed: ${error.message.orEmpty()}",
                cause = error,
            )
        }
    }

    suspend fun searchMemoryDistances(
        candidateIds: List<Long>,
        queryEmbeddingJson: String,
        dimension: Int,
        limit: Int,
    ): Map<Long, Double> {
        return runCatching {
            vectorStore.searchMemoryDistances(
                candidateIds = candidateIds,
                queryEmbeddingJson = queryEmbeddingJson,
                dimension = dimension,
                limit = limit,
            )
        }.getOrElse { error ->
            throw VectorSearchExecutionException(
                operation = "memory",
                tableName = buildMemoryVectorTableName(dimension),
                dimension = dimension,
                candidateCount = candidateIds.size,
                message = "sqlite-vector search failed: ${error.message.orEmpty()}",
                cause = error,
            )
        }
    }

    suspend fun clearAllVectorTables() {
        vectorStore.clearAllVectorTables()
    }

    suspend fun deleteMemoryVectors(chunkIdsByDimension: Map<Int, List<Long>>) {
        vectorStore.deleteMemoryVectors(chunkIdsByDimension)
    }

    suspend fun deleteKnowledgeBaseVectors(chunkIdsByDimension: Map<Int, List<Long>>) {
        vectorStore.deleteKnowledgeBaseVectors(chunkIdsByDimension)
    }
}
