package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.sandbox.SandboxEngine

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
        private const val SKILL_MARKDOWN = "SKILL.md"
    }

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        return getSkillsDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> parseSkillFile(dir.resolve(SKILL_MARKDOWN), dir) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        return resolveSkillDir(skillName)
            ?.resolve(SKILL_MARKDOWN)
            ?.takeIf { it.exists() }
            ?.readText()
            ?.let(SkillFrontmatterParser::extractBody)
    }

    fun readSkillContent(skillName: String): String? {
        return resolveSkillDir(skillName)
            ?.resolve(SKILL_MARKDOWN)
            ?.takeIf { it.exists() }
            ?.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        val skillDir = resolveSkillDir(name) ?: return null
        if (!skillDir.exists()) skillDir.mkdirs()
        skillDir.resolve(SKILL_MARKDOWN).writeText(content)
        return parseSkillFile(skillDir.resolve(SKILL_MARKDOWN), skillDir)
    }

    suspend fun importSkillZip(uri: Uri): SkillMetadata? = withContext(Dispatchers.IO) {
        val extractedDir = extractSkillArchive(uri) ?: return@withContext null
        val metadata = parseSkillFile(extractedDir.resolve(SKILL_MARKDOWN), extractedDir) ?: run {
            extractedDir.deleteRecursively()
            return@withContext null
        }
        val finalDir = resolveSkillDir(metadata.name) ?: run {
            extractedDir.deleteRecursively()
            return@withContext null
        }
        if (finalDir.exists()) finalDir.deleteRecursively()
        extractedDir.copyRecursively(finalDir, overwrite = true)
        extractedDir.deleteRecursively()
        parseSkillFile(finalDir.resolve(SKILL_MARKDOWN), finalDir)
    }

    suspend fun syncSkillsToRuntime(assistantId: String, enabledSkills: Set<String>) = withContext(Dispatchers.IO) {
        if (enabledSkills.isEmpty()) {
            val runtimeRoot = SandboxEngine.getRuntimeSkillsDir(context, assistantId)
            if (runtimeRoot.exists()) {
                runtimeRoot.deleteRecursively()
                runtimeRoot.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
            }
            return@withContext
        }
        val runtimeRoot = SandboxEngine.getRuntimeSkillsDir(context, assistantId)
        runtimeRoot.mkdirs()

        runtimeRoot.listFiles()
            ?.filter { it.isDirectory && it.name !in enabledSkills }
            ?.forEach { it.deleteRecursively() }

        enabledSkills.forEach { skillName ->
            val sourceDir = resolveSkillDir(skillName) ?: return@forEach
            if (!sourceDir.exists()) return@forEach
            val targetDir = runtimeRoot.resolve(skillName)
            if (targetDir.exists()) targetDir.deleteRecursively()
            sourceDir.copyRecursively(targetDir, overwrite = true)
        }
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun extractSkillArchive(uri: Uri): File? {
        val workDir = File(context.cacheDir, "skill-import-${System.currentTimeMillis()}").apply { mkdirs() }
        val entries = mutableListOf<Pair<String, ByteArray>>()

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = normalizeZipEntry(entry.name)
                        if (safeName != null) {
                            entries += safeName to zip.readBytes()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: return null

        if (entries.isEmpty()) return null
        val normalizedNames = entries.map { it.first }
        val hasDirectSkill = normalizedNames.any { it == SKILL_MARKDOWN }
        val prefix = if (hasDirectSkill) "" else detectSingleRoot(normalizedNames)
        val extractedFiles = entries.mapNotNull { (name, bytes) ->
            val relativeName = name.removePrefix(prefix).trimStart('/')
            if (relativeName.isBlank()) null else relativeName to bytes
        }
        if (extractedFiles.none { it.first == SKILL_MARKDOWN }) return null

        extractedFiles.forEach { (relativeName, bytes) ->
            val target = SkillPaths.resolveSkillFile(workDir, relativeName) ?: return null
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { it.write(bytes) }
        }
        return workDir
    }

    private fun detectSingleRoot(entries: List<String>): String {
        val topLevels = entries.map { it.substringBefore('/') }.distinct()
        return if (topLevels.size == 1) topLevels.first() + "/" else ""
    }

    private fun normalizeZipEntry(name: String): String? {
        val cleaned = name.replace('\\', '/').trimStart('/')
        if (cleaned.isBlank() || cleaned.startsWith("__MACOSX/")) return null
        if (cleaned.split('/').any { it == "." || it == ".." || it.isBlank() }) return null
        return cleaned
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            if (!skillFile.exists()) return null
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                directoryName = skillDir.name,
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = frontmatter["allowed-tools"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                skillDir = skillDir,
                hasScripts = skillDir.resolve("scripts").isDirectory,
                hasReferences = skillDir.resolve("references").isDirectory,
                hasAssets = skillDir.resolve("assets").isDirectory,
                hasAgentConfig = skillDir.resolve("agents/openai.yaml").isFile,
            )
        }.getOrElse {
            Log.w(TAG, "Failed to parse skill: ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val directoryName: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasAgentConfig: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(content) ?: return result
        val yaml = content.substring(3, endRange.first).trim()
        yaml.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank() && value.isNotBlank()) result[key] = value
            }
        }
        return result
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }
}
