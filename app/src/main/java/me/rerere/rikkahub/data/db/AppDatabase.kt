package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ArchiveSummaryDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeTextDao
import me.rerere.rikkahub.data.db.dao.VerbatimArtifactDao
import me.rerere.rikkahub.data.db.dao.VectorIndexDao
import me.rerere.rikkahub.data.db.entity.ArchiveSummaryEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeTextEntity
import me.rerere.rikkahub.data.db.entity.VerbatimArtifactEntity
import me.rerere.rikkahub.data.db.entity.VectorIndexEntity
import me.rerere.rikkahub.data.db.migrations.Migration_12_13
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ArchiveSummaryEntity::class,
        VectorIndexEntity::class,
        MessageNodeTextEntity::class,
        VerbatimArtifactEntity::class
    ],
    version = 16,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
    ]
)
@TypeConverters(TokenUsageConverter::class, FloatArrayConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun archiveSummaryDao(): ArchiveSummaryDao

    abstract fun vectorIndexDao(): VectorIndexDao

    abstract fun messageNodeTextDao(): MessageNodeTextDao

    abstract fun verbatimArtifactDao(): VerbatimArtifactDao
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}

object FloatArrayConverter {
    @TypeConverter
    fun fromFloatArray(array: FloatArray): String {
        return array.joinToString(",")
    }

    @TypeConverter
    fun toFloatArray(data: String): FloatArray {
        if (data.isBlank()) return FloatArray(0)
        return data.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
    }
}

