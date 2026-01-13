package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 向量索引数据库实体
 *
 * 对应数据模型：VectorIndex
 */
@Entity(
    tableName = "vector_index",
    foreignKeys = [
        ForeignKey(
            entity = ArchiveSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["archive_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["archive_id"], unique = true),
        Index("embedding_model_id")
    ]
)
data class VectorIndexEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo("archive_id")
    val archiveId: String,

    @ColumnInfo("embedding_vector")
    val embeddingVector: FloatArray,

    @ColumnInfo("embedding_model_id")
    val embeddingModelId: String,

    @ColumnInfo("created_at")
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VectorIndexEntity

        if (id != other.id) return false
        if (archiveId != other.archiveId) return false
        if (!embeddingVector.contentEquals(other.embeddingVector)) return false
        if (embeddingModelId != other.embeddingModelId) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + archiveId.hashCode()
        result = 31 * result + embeddingVector.contentHashCode()
        result = 31 * result + embeddingModelId.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
