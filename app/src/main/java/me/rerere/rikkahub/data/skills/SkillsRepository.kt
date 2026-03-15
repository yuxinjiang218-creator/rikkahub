package me.rerere.rikkahub.data.skills

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import androidx.core.net.toUri
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager

private const val SKILL_MARKDOWN = "SKILL.md"
private const val BUNDLED_SKILLS_ASSET_ROOT = "builtin_skills"
private const val DEFAULT_CREATED_SKILL_DIRECTORY = "new-skill"
private const val DEFAULT_IMPORTED_SKILL_DIRECTORY = "skill-import"

data class SkillCatalogEntry(
    val directoryName: String,
    val path: String,
    val name: String,
    val description: String,
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasAgentConfig: Boolean = false,
    val isBundled: Boolean = false,
)

data class SkillEditorDocument(
    val originalDirectoryName: String,
    val directoryName: String,
    val name: String,
    val description: String,
    val body: String,
)

data class SkillImportResult(
    val directories: List<String>,
    val importedFiles: Int,
)

data class SkillInvalidEntry(
    val directoryName: String,
    val path: String,
    val reason: SkillInvalidReason,
)

sealed interface SkillInvalidReason {
    data object MissingSkillFile : SkillInvalidReason
    data object MissingYamlFrontmatter : SkillInvalidReason
    data object FrontmatterMustStart : SkillInvalidReason
    data object FrontmatterNotClosed : SkillInvalidReason
    data object MissingName : SkillInvalidReason
    data object MissingDescription : SkillInvalidReason
    data class FailedToRead(val detail: String) : SkillInvalidReason
    data class Other(val message: String) : SkillInvalidReason
}

data class SkillsCatalogState(
    val rootPath: String = "",
    val entries: List<SkillCatalogEntry> = emptyList(),
    val invalidEntries: List<SkillInvalidEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val refreshedAt: Long = 0L,
) {
    val entryNames: Set<String> = entries.mapTo(linkedSetOf()) { it.directoryName }
}

class SkillsRepository(
    private val context: Context,
    private val appScope: AppScope,
    private val skillManager: SkillManager,
) {
    private val _state = MutableStateFlow(SkillsCatalogState())
    val state: StateFlow<SkillsCatalogState> = _state.asStateFlow()
    private var rootObserver: FileObserver? = null
    private val childObservers = linkedMapOf<String, FileObserver>()
    @Volatile
    private var pendingRefresh = false

    init {
        requestRefresh()
    }

    fun requestRefresh() {
        if (_state.value.isLoading) {
            pendingRefresh = true
            return
        }
        appScope.launch {
            refresh()
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val rootDir = skillManager.getSkillsDir()
        _state.value = _state.value.copy(
            rootPath = rootDir.absolutePath,
            isLoading = true,
            error = null,
        )

        runCatching {
            ensureBundledSkillsInstalled(rootDir)
            val discovery = discoverCatalogEntries(rootDir)
            updateFileObservers(rootDir, discovery.entries.map { it.directoryName })
            _state.value = SkillsCatalogState(
                rootPath = rootDir.absolutePath,
                entries = discovery.entries.sortedBy { it.directoryName },
                invalidEntries = discovery.invalidEntries.sortedBy { it.directoryName },
                isLoading = false,
                error = null,
                refreshedAt = System.currentTimeMillis(),
            )
        }.getOrElse { error ->
            updateFileObservers(rootDir, emptyList())
            _state.value = SkillsCatalogState(
                rootPath = rootDir.absolutePath,
                entries = emptyList(),
                invalidEntries = emptyList(),
                isLoading = false,
                error = error.message ?: error.javaClass.simpleName,
                refreshedAt = System.currentTimeMillis(),
            )
        }

        if (pendingRefresh) {
            pendingRefresh = false
            requestRefresh()
        }
    }

    suspend fun createSkill(
        directoryName: String,
        name: String,
        description: String,
        body: String,
    ): SkillCatalogEntry = withContext(Dispatchers.IO) {
        val rootDir = skillManager.getSkillsDir()
        ensureBundledSkillsInstalled(rootDir)
        val finalDirectoryName = resolveUniqueDirectoryName(
            desired = sanitizeSkillDirectoryName(directoryName.ifBlank { name }),
            existing = rootDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty(),
        )
        val dir = rootDir.resolve(finalDirectoryName)
        dir.mkdirs()
        dir.resolve(SKILL_MARKDOWN).writeText(buildSkillMarkdown(name.trim(), description.trim(), body))
        refresh()
        requireNotNull(findEntry(finalDirectoryName)) { "Failed to create skill: $finalDirectoryName" }
    }

    suspend fun loadSkillDocument(entry: SkillCatalogEntry): SkillEditorDocument = withContext(Dispatchers.IO) {
        val markdownFile = skillManager.getSkillsDir()
            .resolve(entry.directoryName)
            .resolve(SKILL_MARKDOWN)
        val content = markdownFile.readText()
        val document = parseSkillMarkdownDocument(content)
        SkillEditorDocument(
            originalDirectoryName = entry.directoryName,
            directoryName = entry.directoryName,
            name = document.frontmatter.name,
            description = document.frontmatter.description,
            body = document.body,
        )
    }

    suspend fun updateSkill(
        originalDirectoryName: String,
        directoryName: String,
        name: String,
        description: String,
        body: String,
    ): SkillCatalogEntry = withContext(Dispatchers.IO) {
        val rootDir = skillManager.getSkillsDir()
        val existing = rootDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toMutableSet() ?: mutableSetOf()
        val currentDir = rootDir.resolve(originalDirectoryName)
        require(currentDir.exists()) { "Skill not found: $originalDirectoryName" }
        existing.remove(originalDirectoryName)
        val targetName = sanitizeSkillDirectoryName(directoryName.ifBlank { name }, originalDirectoryName)
        require(targetName !in existing) { "Skill directory already exists: $targetName" }
        val targetDir = if (targetName == originalDirectoryName) currentDir else rootDir.resolve(targetName)
        if (targetName != originalDirectoryName) {
            currentDir.copyRecursively(targetDir, overwrite = true)
            currentDir.deleteRecursively()
        }
        targetDir.resolve(SKILL_MARKDOWN).writeText(buildSkillMarkdown(name.trim(), description.trim(), body))
        refresh()
        requireNotNull(findEntry(targetName)) { "Failed to update skill: $targetName" }
    }

    suspend fun deleteSkill(directoryName: String) = withContext(Dispatchers.IO) {
        require(!isBundledSkillDirectoryName(directoryName)) { "Built-in skills cannot be deleted: $directoryName" }
        skillManager.deleteSkill(directoryName)
        refresh()
    }

    suspend fun importSkillZip(
        inputStream: InputStream,
        archiveName: String? = null,
    ): SkillImportResult = withContext(Dispatchers.IO) {
        val rootDir = skillManager.getSkillsDir()
        ensureBundledSkillsInstalled(rootDir)
        val existingDirectoryNames = rootDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()
        val archive = parseSkillArchive(inputStream)
        val importPlan = buildSkillImportPlan(
            archive = archive,
            suggestedDirectoryName = archiveName
                ?.substringBeforeLast('.', archiveName)
                ?.let { sanitizeSkillDirectoryName(it, DEFAULT_IMPORTED_SKILL_DIRECTORY) },
            existingDirectoryNames = existingDirectoryNames,
        )

        importPlan.directories
            .sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
            .forEach { relativeDirectory ->
                rootDir.resolve(relativeDirectory).mkdirs()
            }

        importPlan.files.forEach { file ->
            val target = rootDir.resolve(file.path)
            target.parentFile?.mkdirs()
            target.writeBytes(file.bytes)
        }

        refresh()
        SkillImportResult(
            directories = importPlan.topLevelDirectories,
            importedFiles = importPlan.files.size,
        )
    }

    fun localizedInvalidReason(reason: SkillInvalidReason): String {
        return when (reason) {
            SkillInvalidReason.MissingSkillFile -> "缺少 SKILL.md"
            SkillInvalidReason.MissingYamlFrontmatter -> "SKILL.md 缺少 YAML frontmatter"
            SkillInvalidReason.FrontmatterMustStart -> "SKILL.md frontmatter 必须以 --- 开始"
            SkillInvalidReason.FrontmatterNotClosed -> "SKILL.md frontmatter 未正确闭合"
            SkillInvalidReason.MissingName -> "SKILL.md frontmatter 缺少 name"
            SkillInvalidReason.MissingDescription -> "SKILL.md frontmatter 缺少 description"
            is SkillInvalidReason.FailedToRead -> "读取失败: ${reason.detail}"
            is SkillInvalidReason.Other -> reason.message
        }
    }

    private fun findEntry(directoryName: String): SkillCatalogEntry? {
        return _state.value.entries.firstOrNull { it.directoryName == directoryName }
    }

    private fun ensureBundledSkillsInstalled(rootDir: File) {
        BUNDLED_SKILLS.forEach { bundled ->
            val targetDir = rootDir.resolve(bundled.directoryName)
            if (targetDir.exists()) return@forEach
            copyAssetDirectory("${BUNDLED_SKILLS_ASSET_ROOT}/${bundled.directoryName}", targetDir)
        }
    }

    private fun copyAssetDirectory(assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                targetDir.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        targetDir.mkdirs()
        children.forEach { child ->
            copyAssetDirectory("$assetPath/$child", targetDir.resolve(child))
        }
    }

    private fun discoverCatalogEntries(rootDir: File): SkillCatalogDiscoveryResult {
        val validEntries = arrayListOf<SkillCatalogEntry>()
        val invalidEntries = arrayListOf<SkillInvalidEntry>()

        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { directory ->
                val skillFile = directory.resolve(SKILL_MARKDOWN)
                if (!skillFile.exists()) {
                    invalidEntries += SkillInvalidEntry(
                        directoryName = directory.name,
                        path = directory.absolutePath,
                        reason = SkillInvalidReason.MissingSkillFile,
                    )
                    return@forEach
                }

                val markdown = runCatching { skillFile.readText() }.getOrElse { error ->
                    invalidEntries += SkillInvalidEntry(
                        directoryName = directory.name,
                        path = directory.absolutePath,
                        reason = SkillInvalidReason.FailedToRead(error.message ?: error.javaClass.simpleName),
                    )
                    return@forEach
                }

                when (val parsed = parseSkillFrontmatter(markdown)) {
                    is SkillFrontmatterParseResult.Success -> {
                        validEntries += SkillCatalogEntry(
                            directoryName = directory.name,
                            path = directory.absolutePath,
                            name = parsed.frontmatter.name,
                            description = parsed.frontmatter.description,
                            hasScripts = directory.resolve("scripts").isDirectory,
                            hasReferences = directory.resolve("references").isDirectory,
                            hasAssets = directory.resolve("assets").isDirectory,
                            hasAgentConfig = directory.resolve("agents/openai.yaml").isFile,
                            isBundled = isBundledSkillDirectoryName(directory.name),
                        )
                    }

                    is SkillFrontmatterParseResult.Error -> {
                        invalidEntries += SkillInvalidEntry(
                            directoryName = directory.name,
                            path = directory.absolutePath,
                            reason = parsed.reason,
                        )
                    }
                }
            }

        return SkillCatalogDiscoveryResult(validEntries, invalidEntries)
    }

    @Suppress("DEPRECATION")
    private fun updateFileObservers(rootDir: File, directoryNames: List<String>) {
        if (rootObserver == null) {
            rootObserver = object : FileObserver(
                rootDir.absolutePath,
                CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    requestRefresh()
                }
            }.also { it.startWatching() }
        }

        val desiredPaths = directoryNames.mapTo(linkedSetOf()) { rootDir.resolve(it).absolutePath }
        val obsoletePaths = childObservers.keys - desiredPaths
        obsoletePaths.forEach { path ->
            childObservers.remove(path)?.stopWatching()
        }

        desiredPaths.forEach { path ->
            if (childObservers.containsKey(path)) return@forEach
            childObservers[path] = object : FileObserver(
                path,
                CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    requestRefresh()
                }
            }.also { it.startWatching() }
        }
    }
}

private data class SkillCatalogDiscoveryResult(
    val entries: List<SkillCatalogEntry>,
    val invalidEntries: List<SkillInvalidEntry>,
)

private data class SkillMarkdownDocument(
    val frontmatter: SkillFrontmatter,
    val body: String,
)

private data class SkillFrontmatter(
    val name: String,
    val description: String,
)

private sealed interface SkillFrontmatterParseResult {
    data class Success(val frontmatter: SkillFrontmatter) : SkillFrontmatterParseResult
    data class Error(val reason: SkillInvalidReason) : SkillFrontmatterParseResult
}

private data class SkillArchiveFile(
    val path: String,
    val bytes: ByteArray,
)

private data class ParsedSkillArchive(
    val directories: Set<String>,
    val files: List<SkillArchiveFile>,
)

private data class SkillImportPlan(
    val topLevelDirectories: List<String>,
    val directories: Set<String>,
    val files: List<SkillArchiveFile>,
)

private data class BundledSkill(
    val directoryName: String,
)

private val BUNDLED_SKILLS = listOf(
    BundledSkill(directoryName = "skill-creator"),
)

private val BUNDLED_SKILL_DIRECTORY_NAMES = BUNDLED_SKILLS.mapTo(linkedSetOf()) { it.directoryName }

fun isBundledSkillDirectoryName(directoryName: String): Boolean {
    return directoryName in BUNDLED_SKILL_DIRECTORY_NAMES
}

fun sanitizeSkillDirectoryName(
    input: String,
    fallback: String = DEFAULT_CREATED_SKILL_DIRECTORY,
): String {
    val normalized = input.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .replace(Regex("-{2,}"), "-")
        .trim('-', '.', '_')
    return normalized.ifBlank { fallback }
}

private fun resolveUniqueDirectoryName(desired: String, existing: Set<String>): String {
    if (desired !in existing) return desired
    var suffix = 2
    while (true) {
        val candidate = "$desired-$suffix"
        if (candidate !in existing) return candidate
        suffix += 1
    }
}

private fun parseSkillFrontmatter(markdown: String): SkillFrontmatterParseResult {
    val normalized = markdown.trimStart()
    if (!normalized.startsWith("---")) {
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingYamlFrontmatter)
    }
    val lines = normalized.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") {
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.FrontmatterMustStart)
    }
    val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }.let { if (it >= 0) it + 1 else -1 }
    if (endIndex <= 0) {
        return SkillFrontmatterParseResult.Error(SkillInvalidReason.FrontmatterNotClosed)
    }
    val values = linkedMapOf<String, String>()
    lines.subList(1, endIndex).forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim().trimMatchingQuotes()
        if (key.isNotBlank()) values[key] = value
    }
    val name = values["name"]?.takeIf { it.isNotBlank() }
        ?: return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingName)
    val description = values["description"]?.takeIf { it.isNotBlank() }
        ?: return SkillFrontmatterParseResult.Error(SkillInvalidReason.MissingDescription)
    return SkillFrontmatterParseResult.Success(SkillFrontmatter(name = name, description = description))
}

private fun parseSkillMarkdownDocument(markdown: String): SkillMarkdownDocument {
    val parsedFrontmatter = parseSkillFrontmatter(markdown)
    val frontmatter = when (parsedFrontmatter) {
        is SkillFrontmatterParseResult.Success -> parsedFrontmatter.frontmatter
        is SkillFrontmatterParseResult.Error -> error("Invalid skill markdown: ${parsedFrontmatter.reason}")
    }
    val body = SkillFrontmatterParser.extractBody(markdown).trim()
    return SkillMarkdownDocument(frontmatter = frontmatter, body = body)
}

private fun buildSkillMarkdown(name: String, description: String, body: String): String {
    val resolvedBody = body.trim().ifBlank {
        """
        # Instructions

        Describe when this skill should be used, which files to inspect, and what steps to follow.
        """.trimIndent()
    }
    return buildString {
        appendLine("---")
        appendLine("name: \"${name.escapeForDoubleQuotedYaml()}\"")
        appendLine("description: \"${description.escapeForDoubleQuotedYaml()}\"")
        appendLine("---")
        appendLine()
        appendLine(resolvedBody)
        appendLine()
    }
}

private fun parseSkillArchive(inputStream: InputStream): ParsedSkillArchive {
    val directories = linkedSetOf<String>()
    val files = arrayListOf<SkillArchiveFile>()

    ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
        while (true) {
            val entry = zipInputStream.nextEntry ?: break
            val normalizedPath = normalizeSkillArchiveEntryPath(entry.name)
            val shouldIgnore = normalizedPath == null || isIgnoredSkillArchiveEntry(normalizedPath)
            if (shouldIgnore) {
                zipInputStream.closeEntry()
                continue
            }
            if (entry.isDirectory) {
                directories += normalizedPath
            } else {
                val bytes = zipInputStream.readBytes()
                files += SkillArchiveFile(normalizedPath, bytes)
                collectParentDirectories(normalizedPath).forEach { directories += it }
            }
            zipInputStream.closeEntry()
        }
    }

    if (files.isEmpty() && directories.isEmpty()) error("Zip archive is empty")
    return collapseSkillArchiveContainerLayers(ParsedSkillArchive(directories, files))
}

private fun buildSkillImportPlan(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
    existingDirectoryNames: Set<String>,
): SkillImportPlan {
    if (archive.files.isEmpty() && archive.directories.isEmpty()) error("Zip archive is empty")

    val hasRootLevelContent = archive.files.any { !it.path.contains('/') }
    val remappedFiles: List<SkillArchiveFile>
    val remappedDirectories: Set<String>
    val topLevelDirectories: List<String>

    if (hasRootLevelContent) {
        val desiredRootDirectory = deriveRootImportDirectoryName(archive, suggestedDirectoryName)
        val resolvedRootDirectory = resolveUniqueDirectoryName(desiredRootDirectory, existingDirectoryNames)
        remappedFiles = archive.files.map { file -> file.copy(path = "$resolvedRootDirectory/${file.path}") }
        remappedDirectories = buildSet {
            add(resolvedRootDirectory)
            archive.directories.forEach { add("$resolvedRootDirectory/$it") }
        }
        topLevelDirectories = listOf(resolvedRootDirectory)
    } else {
        val desiredTopLevelDirectories = archive.topLevelDirectories()
        val mapping = resolveUniqueDirectoryNames(desiredTopLevelDirectories, existingDirectoryNames)
        remappedFiles = archive.files.map { file ->
            file.copy(path = replaceTopLevelDirectory(file.path, mapping))
        }
        remappedDirectories = archive.directories.mapTo(linkedSetOf()) { replaceTopLevelDirectory(it, mapping) }
        topLevelDirectories = desiredTopLevelDirectories.map { mapping.getValue(it) }
    }

    val allDirectories = linkedSetOf<String>()
    allDirectories += topLevelDirectories
    allDirectories += remappedDirectories
    remappedFiles.forEach { file ->
        collectParentDirectories(file.path).forEach { allDirectories += it }
    }

    val hasSkillFile = remappedFiles.any { file ->
        file.path.endsWith("/$SKILL_MARKDOWN") && file.path.count { it == '/' } == 1
    }
    if (!hasSkillFile) error("Zip package must contain $SKILL_MARKDOWN at the root of a skill directory")

    return SkillImportPlan(
        topLevelDirectories = topLevelDirectories.distinct(),
        directories = allDirectories,
        files = remappedFiles.sortedBy { it.path },
    )
}

private fun resolveUniqueDirectoryNames(
    desired: List<String>,
    existing: Set<String>,
): Map<String, String> {
    val reserved = existing.toMutableSet()
    val resolved = linkedMapOf<String, String>()
    desired.distinct().forEach { original ->
        val baseName = original.ifBlank { DEFAULT_IMPORTED_SKILL_DIRECTORY }
        var candidate = baseName
        var suffix = 2
        while (candidate in reserved) {
            candidate = "$baseName-$suffix"
            suffix += 1
        }
        reserved += candidate
        resolved[original] = candidate
    }
    return resolved
}

private fun collapseSkillArchiveContainerLayers(archive: ParsedSkillArchive): ParsedSkillArchive {
    var current = archive
    while (true) {
        if (current.files.isEmpty()) break
        if (current.files.any { !it.path.contains('/') }) break
        val topLevelDirectories = current.topLevelDirectories()
        if (topLevelDirectories.size != 1) break
        val container = topLevelDirectories.single()
        val containsTopLevelSkillFile = current.files.any { it.path == "$container/$SKILL_MARKDOWN" }
        if (containsTopLevelSkillFile) break
        current = ParsedSkillArchive(
            directories = current.directories.mapNotNullTo(linkedSetOf()) { stripLeadingDirectory(it) },
            files = current.files.map { file ->
                file.copy(path = stripLeadingDirectory(file.path) ?: file.path)
            },
        )
    }
    return current
}

private fun normalizeSkillArchiveEntryPath(path: String): String? {
    val slashNormalized = path.replace('\\', '/').trim()
    if (slashNormalized.startsWith('/')) error("Zip entry path must be relative: $path")
    val trimmed = slashNormalized.trim('/').removePrefix("./")
    if (trimmed.isBlank()) return null
    if (Regex("^[A-Za-z]:").containsMatchIn(trimmed)) error("Zip entry path must be relative: $path")
    val segments = trimmed.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." || '\u0000' in it }) {
        error("Zip entry contains an invalid path: $path")
    }
    return segments.joinToString("/")
}

private fun isIgnoredSkillArchiveEntry(path: String): Boolean {
    val segments = path.split('/')
    val fileName = segments.lastOrNull().orEmpty()
    return segments.any { it == "__MACOSX" } ||
        fileName == ".DS_Store" ||
        fileName == "Thumbs.db" ||
        fileName.startsWith("._")
}

private fun collectParentDirectories(path: String): List<String> {
    val segments = path.split('/')
    if (segments.size <= 1) return emptyList()
    return buildList {
        for (index in 1 until segments.lastIndex) {
            add(segments.take(index).joinToString("/"))
        }
        add(segments.dropLast(1).joinToString("/"))
    }.distinct()
}

private fun ParsedSkillArchive.topLevelDirectories(): List<String> {
    return buildSet {
        files.forEach { add(it.path.substringBefore('/')) }
        directories.forEach { add(it.substringBefore('/')) }
    }.sorted()
}

private fun replaceTopLevelDirectory(path: String, mapping: Map<String, String>): String {
    val firstSegment = path.substringBefore('/')
    val remainder = path.substringAfter('/', "")
    val replaced = mapping[firstSegment] ?: firstSegment
    return if (remainder.isBlank()) replaced else "$replaced/$remainder"
}

private fun stripLeadingDirectory(path: String): String? {
    val slashIndex = path.indexOf('/')
    return if (slashIndex < 0) null else path.substring(slashIndex + 1)
}

private fun deriveRootImportDirectoryName(
    archive: ParsedSkillArchive,
    suggestedDirectoryName: String?,
): String {
    suggestedDirectoryName?.takeIf { it.isNotBlank() }?.let { return it }
    val rootSkillFile = archive.files.firstOrNull { it.path == SKILL_MARKDOWN }
    if (rootSkillFile != null) {
        val parsed = parseSkillFrontmatter(rootSkillFile.bytes.toString(Charsets.UTF_8))
        if (parsed is SkillFrontmatterParseResult.Success) {
            return sanitizeSkillDirectoryName(parsed.frontmatter.name, DEFAULT_IMPORTED_SKILL_DIRECTORY)
        }
    }
    return DEFAULT_IMPORTED_SKILL_DIRECTORY
}

private fun String.trimMatchingQuotes(): String {
    if (length >= 2 && first() == last()) {
        return when (first()) {
            '"' -> substring(1, lastIndex).unescapeDoubleQuotedYaml()
            '\'' -> substring(1, lastIndex).replace("''", "'")
            else -> this
        }
    }
    return this
}

private fun String.escapeForDoubleQuotedYaml(): String {
    return buildString(length + 8) {
        for (char in this@escapeForDoubleQuotedYaml) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private fun String.unescapeDoubleQuotedYaml(): String {
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        val current = this[index]
        if (current == '\\' && index + 1 < length) {
            when (val next = this[index + 1]) {
                '\\' -> result.append('\\')
                '"' -> result.append('"')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                else -> result.append(next)
            }
            index += 2
        } else {
            result.append(current)
            index += 1
        }
    }
    return result.toString()
}
