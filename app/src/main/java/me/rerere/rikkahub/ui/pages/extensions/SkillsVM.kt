package me.rerere.rikkahub.ui.pages.extensions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillInvalidReason
import me.rerere.rikkahub.data.skills.SkillsCatalogState
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.sanitizeSkillDirectoryName
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import org.json.JSONArray

class SkillsVM(
    private val context: Context,
    private val skillsRepository: SkillsRepository,
    private val skillManager: SkillManager,
) : ViewModel() {
    val state: StateFlow<SkillsCatalogState> = skillsRepository.state

    fun refresh() {
        skillsRepository.requestRefresh()
    }

    fun createSkill(
        directoryName: String,
        name: String,
        description: String,
        body: String,
        onResult: (Result<SkillCatalogEntry>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                skillsRepository.createSkill(directoryName, name, description, body)
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun loadSkillDocument(
        entry: SkillCatalogEntry,
        onResult: (Result<SkillEditorDocument>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { skillsRepository.loadSkillDocument(entry) }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun updateSkill(
        originalDirectoryName: String,
        directoryName: String,
        name: String,
        description: String,
        body: String,
        onResult: (Result<SkillCatalogEntry>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                skillsRepository.updateSkill(
                    originalDirectoryName = originalDirectoryName,
                    directoryName = directoryName,
                    name = name,
                    description = description,
                    body = body,
                )
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun deleteSkill(
        directoryName: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                skillsRepository.deleteSkill(directoryName)
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun importSkillZip(
        uri: Uri,
        onResult: (Result<String>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val importResult = skillsRepository.importSkillZip(input, queryDisplayName(uri))
                    importResult.directories.joinToString()
                } ?: error("无法读取所选 ZIP")
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun importSkillFromGitHub(
        repoUrl: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "无效的 GitHub 仓库链接") }
                    return@launch
                }

                val files = mutableListOf<Pair<String, String>>()
                val listed = listFilesRecursively(
                    owner = info.owner,
                    repo = info.repo,
                    branch = info.branch,
                    dirPath = info.path,
                    basePath = info.path,
                    result = files,
                )
                if (!listed) {
                    withContext(Dispatchers.Main) { onResult(false, "读取 GitHub 目录失败") }
                    return@launch
                }

                val skillMdEntry = files.find { it.first == "SKILL.md" } ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "目录中未找到 SKILL.md") }
                    return@launch
                }

                val skillMdContent = downloadText(skillMdEntry.second) ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "下载 SKILL.md 失败，请检查链接或网络") }
                    return@launch
                }

                val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
                val skillName = frontmatter["name"]
                if (skillName.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onResult(false, "SKILL.md 格式错误：缺少 name 字段") }
                    return@launch
                }

                val fileContents = LinkedHashMap<String, String>()
                for ((relativePath, downloadUrl) in files) {
                    val content = downloadText(downloadUrl)
                    if (content == null) {
                        withContext(Dispatchers.Main) { onResult(false, "下载文件失败：$relativePath") }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val targetDirectory = resolveImportDirectoryName(skillName)
                val saved = skillManager.saveSkillFilesAtomically(targetDirectory, fileContents)
                if (!saved) {
                    withContext(Dispatchers.Main) { onResult(false, "保存失败") }
                    return@launch
                }

                skillsRepository.refresh()
                withContext(Dispatchers.Main) { onResult(true, targetDirectory) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "未知错误") }
            }
        }
    }

    fun localizedInvalidReason(reason: SkillInvalidReason): String {
        return skillsRepository.localizedInvalidReason(reason)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else {
                null
            }
        }
    }

    private fun resolveImportDirectoryName(skillName: String): String {
        val baseName = sanitizeSkillDirectoryName(skillName)
        val existing = skillManager.getSkillsDir()
            .listFiles()
            ?.filter { it.isDirectory }
            ?.mapTo(mutableSetOf()) { it.name }
            ?: mutableSetOf()
        var candidate = baseName
        var suffix = 2
        while (candidate in existing) {
            candidate = "$baseName-$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() } ?: return false
                    result += relativePath to downloadUrl
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().readText() else null
        } finally {
            connection.disconnect()
        }
    }
}
