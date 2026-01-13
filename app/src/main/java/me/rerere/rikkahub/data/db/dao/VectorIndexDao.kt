package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.VectorIndexEntity

@Dao
interface VectorIndexDao {
    @Query("SELECT * FROM vector_index WHERE id = :id")
    suspend fun getById(id: String): VectorIndexEntity?

    @Query("SELECT * FROM vector_index WHERE id = :id")
    fun getFlowById(id: String): Flow<VectorIndexEntity?>

    @Query("SELECT * FROM vector_index WHERE archive_id = :archiveId")
    suspend fun getByArchiveId(archiveId: String): VectorIndexEntity?

    @Query("SELECT * FROM vector_index WHERE archive_id = :archiveId")
    fun getFlowByArchiveId(archiveId: String): Flow<VectorIndexEntity?>

    @Query("SELECT * FROM vector_index WHERE embedding_model_id = :modelId")
    suspend fun getByEmbeddingModelId(modelId: String): List<VectorIndexEntity>

    @Query("SELECT COUNT(*) FROM vector_index")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM vector_index WHERE embedding_model_id = :modelId")
    suspend fun countByModelId(modelId: String): Int

    @Insert
    suspend fun insert(vectorIndex: VectorIndexEntity)

    @Insert
    suspend fun insertAll(vectorIndices: List<VectorIndexEntity>)

    @Update
    suspend fun update(vectorIndex: VectorIndexEntity)

    @Delete
    suspend fun delete(vectorIndex: VectorIndexEntity)

    @Query("DELETE FROM vector_index WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM vector_index WHERE archive_id = :archiveId")
    suspend fun deleteByArchiveId(archiveId: String)

    @Query("DELETE FROM vector_index WHERE embedding_model_id = :modelId")
    suspend fun deleteByModelId(modelId: String)

    @Query("DELETE FROM vector_index")
    suspend fun deleteAll()
}
