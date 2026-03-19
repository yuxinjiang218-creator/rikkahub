package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getEmbeddingModel
import me.rerere.rikkahub.data.model.KnowledgeBaseDocument
import me.rerere.rikkahub.data.model.KnowledgeBaseDocumentStatus
import me.rerere.rikkahub.data.model.KnowledgeBaseIndexState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val knowledgeBaseMimeTypes = arrayOf(
    "text/*",
    "application/json",
    "application/xml",
    "text/csv",
    "text/tab-separated-values",
    "application/pdf",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-word.document.macroEnabled.12",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel.sheet.macroEnabled.12",
)

private val knowledgeBaseTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun AssistantKnowledgeBasePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val toaster = LocalToaster.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val documents by vm.knowledgeBaseDocuments.collectAsStateWithLifecycle()
    val indexState by vm.knowledgeBaseIndexState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingDelete by remember { mutableStateOf<KnowledgeBaseDocument?>(null) }

    val embeddingConfigured = settings.getEmbeddingModel() != null
    val effectiveChatModel = assistant.chatModelId?.let(settings::findModelById)
        ?: settings.findModelById(settings.chatModelId)
    val toolSupported = effectiveChatModel?.abilities?.contains(ModelAbility.TOOL) == true

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        vm.uploadKnowledgeBaseDocuments(uris) { result ->
            result.fold(
                onSuccess = { imported ->
                    when {
                        imported > 0 -> toaster.show("已加入 $imported 个文档，开始排队索引", type = ToastType.Success)
                        else -> toaster.show("没有可导入的受支持文档", type = ToastType.Warning)
                    }
                },
                onFailure = { error ->
                    toaster.show(error.message ?: "上传失败", type = ToastType.Error)
                }
            )
        }
    }

    pendingDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文档") },
            text = { Text("删除后会移除原文件、索引和正在进行的任务：${document.displayName}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteKnowledgeBaseDocument(document.id) { result ->
                            result.fold(
                                onSuccess = {
                                    toaster.show("文档已删除", type = ToastType.Success)
                                },
                                onFailure = { error ->
                                    toaster.show(error.message ?: "删除失败", type = ToastType.Error)
                                }
                            )
                            pendingDelete = null
                        }
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("知识库") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                KnowledgeBaseIntroCard(
                    embeddingConfigured = embeddingConfigured,
                    toolSupported = toolSupported,
                    toolEnabled = assistant.enableKnowledgeBaseTool,
                    hasDocuments = documents.isNotEmpty(),
                )
            }

            item {
                KnowledgeBaseToolSwitchCard(
                    enabled = assistant.enableKnowledgeBaseTool,
                    onCheckedChange = { enabled ->
                        vm.update(assistant.copy(enableKnowledgeBaseTool = enabled))
                    }
                )
            }

            item {
                KnowledgeBaseQueueCard(indexState = indexState)
            }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        enabled = embeddingConfigured,
                        onClick = { pickerLauncher.launch(knowledgeBaseMimeTypes) }
                    ) {
                        Text("上传文档")
                    }
                    OutlinedButton(
                        enabled = embeddingConfigured && documents.isNotEmpty(),
                        onClick = {
                            vm.reindexAllKnowledgeBase { result ->
                                result.fold(
                                    onSuccess = { count ->
                                        if (count > 0) {
                                            toaster.show("已将 $count 个文档加入重建队列", type = ToastType.Success)
                                        } else {
                                            toaster.show("当前没有新的重建任务", type = ToastType.Warning)
                                        }
                                    },
                                    onFailure = { error ->
                                        toaster.show(error.message ?: "重建失败", type = ToastType.Error)
                                    }
                                )
                            }
                        }
                    ) {
                        Text("全部重建索引")
                    }
                }
            }

            if (documents.isEmpty()) {
                item { EmptyKnowledgeBaseCard() }
            } else {
                items(documents, key = { it.id }) { document ->
                    KnowledgeBaseDocumentCard(
                        document = document,
                        onReindex = {
                            if (!embeddingConfigured) {
                                toaster.show("请先配置全局嵌入模型", type = ToastType.Warning)
                                return@KnowledgeBaseDocumentCard
                            }
                            vm.reindexKnowledgeBaseDocument(document.id) { result ->
                                result.fold(
                                    onSuccess = {
                                        toaster.show("已加入重建队列", type = ToastType.Success)
                                    },
                                    onFailure = { error ->
                                        toaster.show(error.message ?: "重建失败", type = ToastType.Error)
                                    }
                                )
                            }
                        },
                        onDelete = { pendingDelete = document }
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeBaseIntroCard(
    embeddingConfigured: Boolean,
    toolSupported: Boolean,
    toolEnabled: Boolean,
    hasDocuments: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "为当前助手上传文档并建立私有索引，对话时模型会按需调用知识库搜索工具。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "支持常见文本文件、PDF、Word、PowerPoint、Excel 文档，包括老式和现代 Office 格式。超大文件会以前台服务串行索引，避免一次性吃满内存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!embeddingConfigured) {
            WarningCard(
                title = "未配置嵌入模型",
                content = "知识库索引依赖全局嵌入模型。请先在设置中配置 embedding 模型，当前上传入口已禁用。"
            )
        }

        if (hasDocuments && !toolEnabled) {
            WarningCard(
                title = "知识库工具已关闭",
                content = "当前助手不会在对话中调用知识库。打开下方开关后，模型才会按需列文档、检索和读取分块。"
            )
        }

        if (!toolSupported) {
            WarningCard(
                title = "当前聊天模型不支持工具调用",
                content = "你仍然可以管理文档，但该助手在对话中暂时无法调用知识库搜索工具。"
            )
        }
    }
}

@Composable
private fun KnowledgeBaseToolSwitchCard(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "在对话中启用知识库工具",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "关闭后仍可上传和索引文档，但聊天不会注册知识库工具，可减少不必要的 token 开销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun KnowledgeBaseQueueCard(indexState: KnowledgeBaseIndexState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "索引状态",
                style = MaterialTheme.typography.titleMedium
            )
            when {
                indexState.isRunning -> {
                    Text(
                        text = "当前文档：${indexState.currentDocumentName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = buildString {
                            append("队列剩余：")
                            append(indexState.queuedCount)
                            if (indexState.progressLabel.isNotBlank()) {
                                append(" · ")
                                append(indexState.progressLabel)
                                append(' ')
                                append(indexState.progressCurrent)
                                if (indexState.progressTotal > 0) {
                                    append('/')
                                    append(indexState.progressTotal)
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (indexState.progressTotal > 0) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = {
                                (indexState.progressCurrent.toFloat() / indexState.progressTotal.toFloat()).coerceIn(0f, 1f)
                            }
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                indexState.queuedCount > 0 -> {
                    Text(
                        text = "有 ${indexState.queuedCount} 个文档正在等待索引",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    Text(
                        text = "当前没有正在运行的索引任务",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    content: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun EmptyKnowledgeBaseCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "还没有知识库文档",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "上传后会自动加入队列，并在前台服务中串行建立索引。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KnowledgeBaseDocumentCard(
    document: KnowledgeBaseDocument,
    onReindex: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = document.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusText(document),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor(document.status)
                    )
                }
                Text(
                    text = formatBytes(document.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = buildString {
                    append(friendlyMimeType(document))
                    if (document.chunkCount > 0) {
                        append(" · ")
                        append(document.chunkCount)
                        append(" 段")
                    }
                    document.lastIndexedAt?.let {
                        append(" · 最近索引 ")
                        append(formatInstant(it))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (document.status == KnowledgeBaseDocumentStatus.INDEXING ||
                document.status == KnowledgeBaseDocumentStatus.QUEUED
            ) {
                val progress = if (document.progressTotal > 0) {
                    (document.progressCurrent.toFloat() / document.progressTotal.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = { progress }
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (document.progressLabel.isNotBlank() &&
                (document.status == KnowledgeBaseDocumentStatus.QUEUED || document.status == KnowledgeBaseDocumentStatus.INDEXING)
            ) {
                Text(
                    text = buildString {
                        append(document.progressLabel)
                        append(' ')
                        append(document.progressCurrent)
                        if (document.progressTotal > 0) {
                            append('/')
                            append(document.progressTotal)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (document.isRebuilding) {
                Text(
                    text = "正在重建，当前旧索引仍可搜索。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (document.status == KnowledgeBaseDocumentStatus.FAILED && document.lastError.isNotBlank()) {
                Text(
                    text = document.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onReindex) {
                    Text("重建")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: KnowledgeBaseDocumentStatus) = when (status) {
    KnowledgeBaseDocumentStatus.QUEUED,
    KnowledgeBaseDocumentStatus.INDEXING -> MaterialTheme.colorScheme.primary
    KnowledgeBaseDocumentStatus.READY -> MaterialTheme.colorScheme.tertiary
    KnowledgeBaseDocumentStatus.FAILED -> MaterialTheme.colorScheme.error
}

private fun statusText(document: KnowledgeBaseDocument): String = when (document.status) {
    KnowledgeBaseDocumentStatus.QUEUED -> "排队中"
    KnowledgeBaseDocumentStatus.INDEXING -> if (document.isSearchable) "重建中" else "索引中"
    KnowledgeBaseDocumentStatus.READY -> "已完成"
    KnowledgeBaseDocumentStatus.FAILED -> if (document.isSearchable) "重建失败（旧索引仍可用）" else "失败"
}

private fun friendlyMimeType(document: KnowledgeBaseDocument): String {
    val ext = document.displayName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "PDF"
        "doc", "docx", "docm" -> "Word"
        "ppt", "pptx", "pptm" -> "PowerPoint"
        "xls", "xlsx", "xlsm" -> "Excel"
        "md", "markdown" -> "Markdown"
        "csv" -> "CSV"
        "tsv" -> "TSV"
        "json" -> "JSON"
        "xml" -> "XML"
        "html", "htm" -> "HTML"
        else -> document.mimeType
    }
}

private fun formatInstant(instant: java.time.Instant): String {
    return knowledgeBaseTimeFormatter.format(
        instant.atZone(ZoneId.systemDefault())
    )
}

private fun formatBytes(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
