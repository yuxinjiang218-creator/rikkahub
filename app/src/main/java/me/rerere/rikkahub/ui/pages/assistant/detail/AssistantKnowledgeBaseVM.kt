package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.service.knowledge.KnowledgeBaseIndexer

class AssistantKnowledgeBaseVM(
    private val assistantId: String,
    private val indexer: KnowledgeBaseIndexer,
) : ViewModel() {

    val documents: StateFlow<List<KnowledgeDocumentEntity>> = indexer
        .getDocumentsByAssistantId(assistantId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importDocuments(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                val result = indexer.importDocument(
                    assistantId = assistantId,
                    uri = uri,
                    fileName = getFileName(context, uri) ?: "Unknown",
                    mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                )
                when (result) {
                    is KnowledgeBaseIndexer.IndexResult.Success -> {
                        _message.value = "Imported: ${result.chunksCount} chunks"
                    }
                    is KnowledgeBaseIndexer.IndexResult.AlreadyExists -> {
                        _message.value = "Already exists"
                    }
                    is KnowledgeBaseIndexer.IndexResult.Error -> {
                        _message.value = "Import failed: ${result.message}"
                    }
                }
            }
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            indexer.deleteDocument(documentId)
        }
    }

    fun reindexDocument(documentId: String) {
        viewModelScope.launch {
            val result = indexer.reindexDocument(documentId)
            when (result) {
                is KnowledgeBaseIndexer.IndexResult.Success -> {
                    _message.value = "Reindexed: ${result.chunksCount} chunks"
                }
                is KnowledgeBaseIndexer.IndexResult.Error -> {
                    _message.value = "Reindex failed: ${result.message}"
                }
                else -> {}
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }
}
