package me.rerere.rikkahub.data.db.index

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val VECTOR_HEALTH_TABLE = "__vector_backend_probe"

class VectorSearchExecutionException(
    val operation: String,
    val tableName: String,
    val dimension: Int,
    val candidateCount: Int,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(
    "$message [operation=$operation, table=$tableName, dimension=$dimension, candidates=$candidateCount]",
    cause
)

class VectorBackendVerifier(
    private val database: IndexDatabase,
) {
    companion object {
        private const val TAG = "VectorBackendVerifier"
    }

    private val verificationMutex = Mutex()

    @Volatile
    private var lastKnownHealthy = false

    @Volatile
    private var lastFailureMessage = "Vector backend has not been verified yet"

    suspend fun verifyBackendHealth(force: Boolean = false) = withContext(Dispatchers.IO) {
        verificationMutex.withLock {
            if (!force && lastKnownHealthy) return@withLock
            runCatching {
                runBackendProbe(database.openHelper.writableDatabase)
                lastKnownHealthy = true
                lastFailureMessage = ""
                Log.i(TAG, "sqlite-vector backend health check passed")
            }.getOrElse { error ->
                lastKnownHealthy = false
                lastFailureMessage = error.message.orEmpty().ifBlank { "Unknown sqlite-vector health check failure" }
                Log.e(TAG, "sqlite-vector backend health check failed", error)
                throw error
            }
        }
    }

    suspend fun assertHealthy(
        operation: String,
        tableName: String,
        dimension: Int,
        candidateCount: Int,
    ) {
        try {
            verifyBackendHealth(force = false)
        } catch (error: Throwable) {
            throw VectorSearchExecutionException(
                operation = operation,
                tableName = tableName,
                dimension = dimension,
                candidateCount = candidateCount,
                message = "sqlite-vector backend health check failed: ${error.message.orEmpty()}",
                cause = error
            )
        }
        if (!lastKnownHealthy) {
            throw VectorSearchExecutionException(
                operation = operation,
                tableName = tableName,
                dimension = dimension,
                candidateCount = candidateCount,
                message = "sqlite-vector backend is unhealthy: $lastFailureMessage"
            )
        }
    }

    private fun runBackendProbe(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$VECTOR_HEALTH_TABLE` (
                `probe_id` INTEGER PRIMARY KEY NOT NULL,
                `embedding` BLOB NOT NULL
            )
            """.trimIndent()
        )
        initializeVectorTable(db, VECTOR_HEALTH_TABLE, 2)
        db.execSQL("DELETE FROM `$VECTOR_HEALTH_TABLE`")
        val statement = db.compileStatement(
            "INSERT OR REPLACE INTO `$VECTOR_HEALTH_TABLE` (`probe_id`, `embedding`) VALUES (?, vector_as_f32(?))"
        )
        statement.bindLong(1, 1L)
        statement.bindString(2, "[1.0,0.0]")
        statement.executeInsert()

        statement.clearBindings()
        statement.bindLong(1, 2L)
        statement.bindString(2, "[0.0,1.0]")
        statement.executeInsert()

        db.query(
            """
            SELECT rowid, distance
            FROM vector_full_scan('$VECTOR_HEALTH_TABLE', 'embedding', vector_as_f32(?))
            ORDER BY distance
            LIMIT 2
            """.trimIndent(),
            arrayOf("[1.0,0.0]")
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Vector backend probe returned no rows" }
            val firstRowId = cursor.getLong(0)
            val firstDistance = cursor.getDouble(1)
            check(firstRowId == 1L) { "Vector backend probe returned unexpected rowid=$firstRowId" }
            check(firstDistance.isFinite()) { "Vector backend probe returned non-finite distance=$firstDistance" }
        }
    }
}
