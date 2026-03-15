package me.rerere.rikkahub.ui.pages.extensions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillInvalidReason
import me.rerere.rikkahub.data.skills.SkillsCatalogState
import me.rerere.rikkahub.data.skills.SkillsRepository

class SkillsVM(
    private val context: Context,
    private val skillsRepository: SkillsRepository,
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
}
