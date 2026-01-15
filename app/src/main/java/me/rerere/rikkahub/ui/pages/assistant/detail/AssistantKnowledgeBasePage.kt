package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Database
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.DocumentStatus
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.data.db.entity.KnowledgeDocumentEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 文件大小格式化
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantKnowledgeBasePage(
    assistantId: String,
    viewModel: AssistantKnowledgeBaseVM = koinViewModel {
        parametersOf(assistantId)
    }
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    // 显示消息
    LaunchedEffect(message) {
        message?.let {
            toaster.show(it)
            viewModel.clearMessage()
        }
    }

    // 文件选择器
    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null) {
            viewModel.importDocuments(context, uris)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.knowledge_base_page_title)) },
                navigationIcon = { BackButton() }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { pickFiles.launch(arrayOf("*/*")) }
            ) {
                Icon(Lucide.Plus, contentDescription = null)
            }
        }
    ) { padding ->
        if (documents.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Lucide.Database,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.knowledge_base_page_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(documents) { document ->
                    DocumentCard(
                        document = document,
                        onDelete = { viewModel.deleteDocument(document.id) },
                        onReindex = { viewModel.reindexDocument(document.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: KnowledgeDocumentEntity,
    onDelete: () -> Unit,
    onReindex: () -> Unit
) {
    val status = DocumentStatus.valueOf(document.status)
    val dateFormatter = rememberDateFormatter()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                DocumentStatus.READY -> MaterialTheme.colorScheme.surfaceContainerLow
                DocumentStatus.INDEXING -> MaterialTheme.colorScheme.surfaceContainerLow
                DocumentStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                DocumentStatus.PENDING -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = document.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.knowledge_base_page_file_size,
                            formatFileSize(document.sizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusBadge(status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormatter.format(Date(document.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row {
                    if (status == DocumentStatus.FAILED || status == DocumentStatus.READY) {
                        IconButton(onClick = onReindex) {
                            Icon(Lucide.RefreshCw, contentDescription = null)
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Lucide.Trash2, contentDescription = null)
                    }
                }
            }

            if (status == DocumentStatus.INDEXING) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = stringResource(R.string.knowledge_base_page_status_indexing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (status == DocumentStatus.FAILED && document.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = document.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DocumentStatus) {
    val (text, color) = when (status) {
        DocumentStatus.PENDING -> stringResource(R.string.knowledge_base_page_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
        DocumentStatus.INDEXING -> stringResource(R.string.knowledge_base_page_status_indexing) to MaterialTheme.colorScheme.primary
        DocumentStatus.READY -> stringResource(R.string.knowledge_base_page_status_ready) to MaterialTheme.colorScheme.primary
        DocumentStatus.FAILED -> stringResource(R.string.knowledge_base_page_status_failed) to MaterialTheme.colorScheme.error
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f)
        ),
        shape = CircleShape
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun rememberDateFormatter(): SimpleDateFormat {
    return remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
}
