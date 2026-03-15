package me.rerere.rikkahub.sandbox

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.files.FileFolders

object SandboxEngine {
    private const val MAX_SANDBOX_SIZE = 1024L * 1024 * 1024
    private const val SANDBOX_ROOT = "sandboxes"
    private const val DELIVERY_DIR = "delivery"
    private const val RUNTIME_DIR = ".runtime"
    private const val RUNTIME_SKILLS_DIR = "skills"
    private const val SHARE_CACHE_DIR = ".share-cache"

    fun getSandboxDir(context: Context, assistantId: String): File {
        return File(context.filesDir, "$SANDBOX_ROOT/$assistantId").apply {
            mkdirs()
            resolve(DELIVERY_DIR).mkdirs()
        }
    }

    fun getDeliveryDir(context: Context, assistantId: String): File {
        return File(getSandboxDir(context, assistantId), DELIVERY_DIR).apply { mkdirs() }
    }

    fun getRuntimeSkillsDir(context: Context, assistantId: String): File {
        return File(getSandboxDir(context, assistantId), "$RUNTIME_DIR/$RUNTIME_SKILLS_DIR").apply { mkdirs() }
    }

    fun getShareCacheDir(context: Context, assistantId: String): File {
        return File(getSandboxDir(context, assistantId), SHARE_CACHE_DIR).apply { mkdirs() }
    }

    fun getSkillLibraryDir(context: Context): File {
        return File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
    }

    fun getSandboxUsage(context: Context, assistantId: String): SandboxUsage {
        val sandboxDir = getSandboxDir(context, assistantId)
        val totalSize = calculateDirectorySize(sandboxDir)
        return SandboxUsage(
            usedBytes = totalSize,
            maxBytes = MAX_SANDBOX_SIZE,
            fileCount = countFiles(sandboxDir),
            usagePercent = (totalSize * 100 / MAX_SANDBOX_SIZE).toInt()
        )
    }

    fun execute(
        context: Context,
        assistantId: String,
        operation: String,
        params: Map<String, Any>,
    ): JsonObject {
        return runCatching {
            val sandboxDir = getSandboxDir(context, assistantId)
            if (getSandboxUsage(context, assistantId).usedBytes > MAX_SANDBOX_SIZE) {
                return errorResult("Sandbox storage limit exceeded (1GB max). Please delete some files.")
            }

            when (operation) {
                "write" -> writeFile(sandboxDir, params)
                "read" -> readFile(sandboxDir, params)
                "delete" -> deletePath(sandboxDir, params)
                "list" -> listPath(sandboxDir, params)
                "mkdir" -> makeDirectory(sandboxDir, params)
                "copy" -> copyPath(sandboxDir, params)
                "move" -> movePath(sandboxDir, params)
                "stat" -> statPath(sandboxDir, params)
                "exists" -> existsPath(sandboxDir, params)
                "zip_create" -> createZip(context, assistantId, sandboxDir, params)
                "sqlite_tables" -> sqliteTables(sandboxDir, params)
                "sqlite_query" -> sqliteQuery(sandboxDir, params)
                else -> errorResult("Unsupported sandbox operation: $operation")
            }
        }.getOrElse {
            errorResult("[${it.javaClass.simpleName}] ${it.message}", it.stackTraceToString())
        }
    }

    fun getFileShareUri(context: Context, assistantId: String, filePath: String): Uri? {
        val file = resolveSandboxPath(getSandboxDir(context, assistantId), filePath) ?: return null
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    fun getShareableUri(context: Context, assistantId: String, path: String): Uri? {
        val sandboxDir = getSandboxDir(context, assistantId)
        val target = resolveSandboxPath(sandboxDir, path) ?: return null
        if (!target.exists()) return null
        val shareTarget = if (target.isDirectory) {
            val zipName = "${target.name.ifBlank { "folder" }}-${System.currentTimeMillis()}.zip"
            val cacheFile = getShareCacheDir(context, assistantId).resolve(zipName)
            zipTargets(sandboxDir, listOf(target), cacheFile)
            cacheFile
        } else {
            target
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareTarget)
    }

    fun getFileDownloadInfo(context: Context, assistantId: String, filePath: String): FileDownloadInfo? {
        val file = resolveSandboxPath(getSandboxDir(context, assistantId), filePath) ?: return null
        if (!file.exists()) return null
        val uri = getFileShareUri(context, assistantId, filePath) ?: return null
        return FileDownloadInfo(
            fileName = file.name,
            filePath = normalizeRelative(file.relativeTo(getSandboxDir(context, assistantId)).path),
            fileSize = if (file.isFile) file.length() else 0L,
            uri = uri.toString(),
            mimeType = getFileMimeType(file.name)
        )
    }

    fun listAllFiles(context: Context, assistantId: String): List<SandboxFileInfo> {
        val sandboxDir = getSandboxDir(context, assistantId)
        return sandboxDir.walkTopDown()
            .filter { it != sandboxDir && isUserVisibleSandboxEntry(sandboxDir, it) }
            .map {
                SandboxFileInfo(
                    name = it.name,
                    path = normalizeRelative(it.relativeTo(sandboxDir).path),
                    size = if (it.isFile) it.length() else 0L,
                    modified = it.lastModified(),
                    isDirectory = it.isDirectory
                )
            }
            .sortedWith(compareBy<SandboxFileInfo>({ !it.isDirectory }, { it.path }))
            .toList()
    }

    fun listDirectory(context: Context, assistantId: String, directoryPath: String = ""): List<SandboxFileInfo> {
        val sandboxDir = getSandboxDir(context, assistantId)
        val targetDir = resolveSandboxPath(sandboxDir, directoryPath) ?: return emptyList()
        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()
        return targetDir.listFiles()
            ?.filter { isUserVisibleSandboxEntry(sandboxDir, it) }
            ?.map { file ->
                SandboxFileInfo(
                    name = file.name,
                    path = normalizeRelative(file.relativeTo(sandboxDir).path),
                    size = if (file.isFile) file.length() else 0L,
                    modified = file.lastModified(),
                    isDirectory = file.isDirectory
                )
            }
            ?.sortedWith(compareBy<SandboxFileInfo>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    fun deleteSandboxFile(context: Context, assistantId: String, filePath: String): Boolean {
        val sandboxDir = getSandboxDir(context, assistantId)
        val target = resolveSandboxPath(sandboxDir, filePath) ?: return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    fun clearSandbox(context: Context, assistantId: String): Boolean {
        return runCatching {
            getSandboxDir(context, assistantId).listFiles()?.forEach { it.deleteRecursively() }
            true
        }.getOrDefault(false)
    }

    fun deleteSandbox(context: Context, assistantId: String): Boolean {
        return runCatching {
            getSandboxDir(context, assistantId).deleteRecursively()
            true
        }.getOrDefault(false)
    }

    fun importFileToSandbox(
        context: Context,
        assistantId: String,
        sourceUri: Uri,
        targetPath: String? = null,
    ): String? {
        return runCatching {
            val sandboxDir = getSandboxDir(context, assistantId)
            val sourceName = getFileNameFromUri(context, sourceUri) ?: "imported_${UUID.randomUUID()}"
            val relativePath = targetPath ?: sourceName
            val targetFile = resolveSandboxPathForWrite(sandboxDir, relativePath)
            targetFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            normalizeRelative(relativePath)
        }.getOrNull()
    }

    fun copySandbox(context: Context, sourceAssistantId: String, targetAssistantId: String): Boolean {
        return runCatching {
            val sourceDir = getSandboxDir(context, sourceAssistantId)
            if (!sourceDir.exists()) return true
            val targetDir = getSandboxDir(context, targetAssistantId)
            sourceDir.walkTopDown().forEach { source ->
                if (source == sourceDir) return@forEach
                val target = targetDir.resolve(source.relativeTo(sourceDir).path)
                if (source.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    target.setLastModified(source.lastModified())
                }
            }
            true
        }.getOrDefault(false)
    }

    fun getFileMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".zip", true) -> "application/zip"
            fileName.endsWith(".txt", true) -> "text/plain"
            fileName.endsWith(".json", true) -> "application/json"
            fileName.endsWith(".md", true) -> "text/markdown"
            fileName.endsWith(".py", true) -> "text/x-python"
            fileName.endsWith(".csv", true) -> "text/csv"
            fileName.endsWith(".svg", true) -> "image/svg+xml"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            fileName.endsWith(".pdf", true) -> "application/pdf"
            else -> URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
        }
    }

    private fun writeFile(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val filePath = params.string("file_path") ?: return errorResult("file_path is required")
        val content = params["content"]?.toString() ?: ""
        val target = resolveSandboxPathForWrite(sandboxDir, filePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return successResult {
            put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
            put("size", target.length())
        }
    }

    private fun readFile(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val filePath = params.string("file_path") ?: return errorResult("file_path is required")
        val target = resolveSandboxPath(sandboxDir, filePath) ?: return errorResult("Invalid file_path")
        if (!target.exists() || !target.isFile) return errorResult("File not found: $filePath")
        return successResult {
            put("data", buildJsonObject {
                put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
                put("content", target.readText())
                put("size", target.length())
                put("mime", getFileMimeType(target.name))
            })
        }
    }

    private fun deletePath(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val inputPath = params.string("file_path") ?: params.string("path") ?: return errorResult("file_path is required")
        val recursive = params.boolean("recursive")
        val target = resolveSandboxPath(sandboxDir, inputPath) ?: return errorResult("Invalid path")
        if (!target.exists()) return errorResult("Path not found: $inputPath")
        val deleted = when {
            target.isDirectory && recursive -> target.deleteRecursively()
            target.isDirectory -> target.delete()
            else -> target.delete()
        }
        return if (deleted) successResult {} else errorResult("Failed to delete $inputPath")
    }

    private fun listPath(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val path = params.string("path").orEmpty()
        val showHidden = params.boolean("show_hidden")
        val target = resolveSandboxPath(sandboxDir, path) ?: return errorResult("Invalid path")
        if (!target.exists() || !target.isDirectory) return errorResult("Directory not found: $path")
        return successResult {
            put("items", JsonArray(target.listFiles()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.map { file ->
                    buildJsonObject {
                        put("name", file.name)
                        put("path", normalizeRelative(file.relativeTo(sandboxDir).path))
                        put("isDirectory", file.isDirectory)
                        put("size", if (file.isFile) file.length() else 0L)
                        put("modified", file.lastModified())
                    }
                } ?: emptyList()))
        }
    }

    private fun makeDirectory(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val path = params.string("dir_path") ?: params.string("path") ?: return errorResult("dir_path is required")
        val target = resolveSandboxPathForWrite(sandboxDir, path)
        return if (target.mkdirs() || target.exists()) successResult {
            put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
        } else {
            errorResult("Failed to create directory: $path")
        }
    }

    private fun copyPath(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val sourcePath = params.string("src") ?: params.string("source") ?: return errorResult("src is required")
        val targetPath = params.string("dst") ?: params.string("destination") ?: return errorResult("dst is required")
        val source = resolveSandboxPath(sandboxDir, sourcePath) ?: return errorResult("Invalid source path")
        val target = resolveSandboxPathForWrite(sandboxDir, targetPath)
        if (!source.exists()) return errorResult("Source not found: $sourcePath")
        target.parentFile?.mkdirs()
        if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
        return successResult {
            put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
        }
    }

    private fun movePath(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val sourcePath = params.string("src") ?: params.string("source") ?: return errorResult("src is required")
        val targetPath = params.string("dst") ?: params.string("destination") ?: return errorResult("dst is required")
        val source = resolveSandboxPath(sandboxDir, sourcePath) ?: return errorResult("Invalid source path")
        val target = resolveSandboxPathForWrite(sandboxDir, targetPath)
        if (!source.exists()) return errorResult("Source not found: $sourcePath")
        target.parentFile?.mkdirs()
        source.copyRecursively(target, overwrite = true)
        source.deleteRecursively()
        return successResult {
            put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
        }
    }

    private fun statPath(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val filePath = params.string("file_path") ?: params.string("path") ?: return errorResult("file_path is required")
        val target = resolveSandboxPath(sandboxDir, filePath) ?: return errorResult("Invalid path")
        if (!target.exists()) return errorResult("Path not found: $filePath")
        return successResult {
            put("data", buildJsonObject {
                put("name", target.name)
                put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
                put("isDirectory", target.isDirectory)
                put("size", if (target.isFile) target.length() else 0L)
                put("modified", target.lastModified())
                put("mime", if (target.isFile) getFileMimeType(target.name) else "inode/directory")
            })
        }
    }

    private fun existsPath(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val filePath = params.string("file_path") ?: params.string("path") ?: return errorResult("file_path is required")
        val target = resolveSandboxPath(sandboxDir, filePath)
        return successResult {
            put("exists", target?.exists() == true)
        }
    }

    private fun createZip(context: Context, assistantId: String, sandboxDir: File, params: Map<String, Any>): JsonObject {
        val zipName = params.string("zip_name") ?: return errorResult("zip_name is required")
        val outputPath = params.string("output_path") ?: zipName
        val sourcePaths = (params["source_paths"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        if (sourcePaths.isEmpty()) return errorResult("source_paths is required")
        val sources = sourcePaths.mapNotNull { resolveSandboxPath(sandboxDir, it) }
        if (sources.size != sourcePaths.size) return errorResult("One or more source paths are invalid")
        val target = resolveSandboxPathForWrite(sandboxDir, outputPath)
        target.parentFile?.mkdirs()
        zipTargets(sandboxDir, sources, target)
        val uri = getFileShareUri(context, assistantId, normalizeRelative(target.relativeTo(sandboxDir).path))
        return successResult {
            put("path", normalizeRelative(target.relativeTo(sandboxDir).path))
            put("mime", getFileMimeType(target.name))
            put("size", target.length())
            put("uri", uri?.toString().orEmpty())
        }
    }

    private fun sqliteTables(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val dbPath = params.string("db_path") ?: return errorResult("db_path is required")
        val dbFile = resolveSandboxPath(sandboxDir, dbPath) ?: return errorResult("Invalid db_path")
        if (!dbFile.exists()) return errorResult("Database not found: $dbPath")
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = mutableListOf<JsonObject>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).useRows { cursor ->
                while (cursor.moveToNext()) {
                    val tableName = cursor.getString(0)
                    val rowCount = db.rawQuery("SELECT COUNT(*) FROM \"$tableName\"", null).useSingleLong()
                    val columns = db.rawQuery("PRAGMA table_info(\"$tableName\")", null).useRows { pragma ->
                        buildJsonArray {
                            while (pragma.moveToNext()) {
                                add(buildJsonObject {
                                    put("name", pragma.getString(1))
                                    put("type", pragma.getString(2).orEmpty())
                                    put("notnull", pragma.getInt(3) == 1)
                                    put("primaryKey", pragma.getInt(5) == 1)
                                })
                            }
                        }
                    }
                    tables += buildJsonObject {
                        put("name", tableName)
                        put("row_count", rowCount)
                        put("columns", columns)
                    }
                }
            }
            successResult { put("tables", JsonArray(tables)) }
        }
    }

    private fun sqliteQuery(sandboxDir: File, params: Map<String, Any>): JsonObject {
        val dbPath = params.string("db_path") ?: return errorResult("db_path is required")
        val query = params.string("query") ?: return errorResult("query is required")
        val maxRows = params.int("max_rows") ?: 100
        val dbFile = resolveSandboxPath(sandboxDir, dbPath) ?: return errorResult("Invalid db_path")
        if (!dbFile.exists()) return errorResult("Database not found: $dbPath")
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(query, emptyArray<String>()).useRows { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = buildJsonArray {
                    var count = 0
                    while (cursor.moveToNext() && count < maxRows) {
                        add(buildJsonObject {
                            columns.forEachIndexed { index, name ->
                                put(name, cursor.valueAsJson(index))
                            }
                        })
                        count++
                    }
                }
                successResult {
                    put("columns", buildJsonArray { columns.forEach { add(JsonPrimitive(it)) } })
                    put("rows", rows)
                    put("row_count", rows.size)
                }
            }
        }
    }

    private fun zipTargets(sandboxDir: File, sources: List<File>, outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
            sources.forEach { source ->
                if (source.isDirectory) {
                    source.walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->
                            val entryName = normalizeRelative(file.relativeTo(sandboxDir).path)
                            zip.putNextEntry(ZipEntry(entryName))
                            FileInputStream(file).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                } else {
                    val entryName = normalizeRelative(source.relativeTo(sandboxDir).path)
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(source).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun resolveSandboxPath(root: File, relativePath: String): File? {
        val candidate = if (relativePath.isBlank()) root else File(root, relativePath)
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return canonicalCandidate.takeIf {
            it.path == canonicalRoot.path || it.path.startsWith(canonicalRoot.path + File.separator)
        }
    }

    private fun resolveSandboxPathForWrite(root: File, relativePath: String): File {
        return resolveSandboxPath(root, relativePath)
            ?: throw IllegalArgumentException("Invalid sandbox path: $relativePath")
    }

    private fun normalizeRelative(path: String): String = path.replace(File.separatorChar, '/')

    fun resolveHostFileForContainerPath(context: Context, assistantId: String, containerPath: String): File? {
        val normalized = normalizeContainerPath(containerPath)
        return when {
            normalized == "/workspace" -> getSandboxDir(context, assistantId)
            normalized.startsWith("/workspace/") -> resolveSandboxPath(
                getSandboxDir(context, assistantId),
                normalized.removePrefix("/workspace/")
            )

            normalized == "/delivery" -> getDeliveryDir(context, assistantId)
            normalized.startsWith("/delivery/") -> resolveSandboxPath(
                getDeliveryDir(context, assistantId),
                normalized.removePrefix("/delivery/")
            )

            normalized == "/opt/rikkahub/skills" -> getRuntimeSkillsDir(context, assistantId)
            normalized.startsWith("/opt/rikkahub/skills/") -> resolveSandboxPath(
                getRuntimeSkillsDir(context, assistantId),
                normalized.removePrefix("/opt/rikkahub/skills/")
            )

            normalized == "/skills" -> getSkillLibraryDir(context)
            normalized.startsWith("/skills/") -> resolveSandboxPath(
                getSkillLibraryDir(context),
                normalized.removePrefix("/skills/")
            )

            else -> null
        }
    }

    private fun normalizeContainerPath(path: String): String {
        if (path.isBlank()) return "/"
        val normalized = path.replace('\\', '/')
        val collapsed = normalized.replace(Regex("/+"), "/")
        return if (collapsed.startsWith("/")) collapsed.trimEnd('/').ifBlank { "/" } else "/${collapsed.trimEnd('/')}"
    }

    private fun isUserVisibleSandboxEntry(root: File, file: File): Boolean {
        val relative = normalizeRelative(file.relativeTo(root).path)
        return relative != RUNTIME_DIR &&
            !relative.startsWith("$RUNTIME_DIR/") &&
            relative != SHARE_CACHE_DIR &&
            !relative.startsWith("$SHARE_CACHE_DIR/")
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun successResult(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        return buildJsonObject {
            put("success", true)
            builder()
        }
    }

    private fun errorResult(message: String, traceback: String? = null): JsonObject {
        return buildJsonObject {
            put("success", false)
            put("error", message)
            if (!traceback.isNullOrBlank()) put("traceback", traceback)
        }
    }

    private fun Map<String, Any>.string(key: String): String? = this[key]?.toString()
    private fun Map<String, Any>.boolean(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }
    private fun Map<String, Any>.int(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

data class SandboxUsage(
    val usedBytes: Long,
    val maxBytes: Long,
    val fileCount: Int,
    val usagePercent: Int,
) {
    fun formatUsedSize(): String = formatFileSize(usedBytes)
    fun formatMaxSize(): String = formatFileSize(maxBytes)
}

data class SandboxFileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
) {
    fun formatSize(): String = formatFileSize(size)
    fun formatModified(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(modified))
}

data class FileDownloadInfo(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val uri: String,
    val mimeType: String,
) {
    fun formatSize(): String = formatFileSize(fileSize)
}

data class CheckpointInfo(
    val checkpointId: String,
    val fullSha: String,
    val message: String,
    val boundMessageIndex: Int?,
    val timestamp: Long,
) {
    fun formatTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun <T> SQLiteDatabase.use(block: (SQLiteDatabase) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}

private fun <T> Cursor.useRows(block: (Cursor) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}

private fun Cursor.useSingleLong(): Long {
    return useRows {
        if (moveToFirst()) getLong(0) else 0L
    }
}

private fun Cursor.valueAsJson(index: Int): JsonPrimitive {
    return when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JsonPrimitive("")
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(index))
        else -> JsonPrimitive(getString(index).orEmpty())
    }
}
