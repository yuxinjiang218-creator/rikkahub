package me.rerere.rikkahub.ui.components.sandbox

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.FileImage
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FileType
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wrench
import com.composables.icons.lucide.X
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.sandbox.SandboxFileInfo
import org.koin.compose.koinInject

private enum class BrowserMode {
    Workspace,
    Container,
}

private data class FileSystemItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long,
    val hostFile: File? = null,
    val subtitle: String? = null,
    val canShare: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
)

private data class ContainerRootShortcut(
    val path: String,
    val description: String,
)

private val containerRootShortcuts = listOf(
    ContainerRootShortcut("/workspace", "默认工作区，项目文件和中间产物优先放这里"),
    ContainerRootShortcut("/delivery", "本轮交付目录，最终交付文件应写到这里"),
    ContainerRootShortcut("/skills", "真实技能库，可创建和编辑可复用 skills"),
    ContainerRootShortcut("/opt/rikkahub/skills", "当前助手已启用 skills 的只读运行时镜像"),
    ContainerRootShortcut("/root", "容器用户目录，可查看环境级配置"),
    ContainerRootShortcut("/usr/local", "容器持久化工具目录"),
    ContainerRootShortcut("/", "完整容器根目录"),
)

@Composable
fun SandboxFileManagerDialog(
    sandboxId: String,
    title: String = "沙箱文件管理",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prootManager: PRootManager = koinInject()

    var browserMode by remember { mutableStateOf(BrowserMode.Workspace) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showEditMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewContent by remember { mutableStateOf<String?>(null) }
    var selectedFile by remember { mutableStateOf<FileSystemItem?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var pathHistory by remember { mutableStateOf(listOf("")) }
    var currentItems by remember { mutableStateOf<List<FileSystemItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun resetNavigation(mode: BrowserMode) {
        browserMode = mode
        currentPath = ""
        pathHistory = listOf("")
    }

    fun loadDirectory(path: String = currentPath, mode: BrowserMode = browserMode) {
        scope.launch {
            isLoading = true
            currentItems = withContext(Dispatchers.IO) {
                when (mode) {
                    BrowserMode.Workspace -> SandboxEngine.listDirectory(context, sandboxId, path).map { file ->
                        file.toWorkspaceItem(context, sandboxId)
                    }

                    BrowserMode.Container -> loadContainerItems(
                        context = context,
                        sandboxId = sandboxId,
                        prootManager = prootManager,
                        path = path,
                    )
                }
            }
            isLoading = false
        }
    }

    fun navigateTo(path: String) {
        currentPath = path
        pathHistory = pathHistory + path
    }

    fun openFile(item: FileSystemItem) {
        scope.launch {
            val hostFile = item.hostFile
            if (hostFile != null && hostFile.exists() && hostFile.isFile) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    hostFile,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getMimeType(item.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "打开文件"))
                return@launch
            }

            if (browserMode == BrowserMode.Container) {
                val content = withContext(Dispatchers.IO) {
                    prootManager.readContainerTextFile(sandboxId, item.path)
                }
                previewTitle = item.path
                previewContent = content ?: "当前仅支持预览文本文件，或容器尚未运行。"
            }
        }
    }

    fun deleteFile(item: FileSystemItem) {
        scope.launch {
            val result = SandboxEngine.execute(
                context = context,
                assistantId = sandboxId,
                operation = "delete",
                params = mapOf("file_path" to item.path),
            )
            if (result["success"]?.jsonPrimitive?.boolean == true) {
                loadDirectory()
            }
        }
    }

    LaunchedEffect(sandboxId, browserMode, currentPath) {
        loadDirectory()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = if (browserMode == BrowserMode.Workspace) "工作区文件" else "容器目录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Lucide.X, contentDescription = "关闭")
                    }
                }

                BrowserModeTabs(
                    current = browserMode,
                    onSelect = { mode ->
                        if (mode != browserMode) {
                            resetNavigation(mode)
                        }
                    },
                )

                BreadcrumbNavigation(
                    browserMode = browserMode,
                    pathHistory = pathHistory,
                    onPathClick = { index ->
                        pathHistory = pathHistory.take(index + 1)
                        currentPath = pathHistory.last()
                    },
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                FileManagerToolbar(
                    currentPath = currentPath,
                    browserMode = browserMode,
                    onBackClick = {
                        if (pathHistory.size > 1) {
                            pathHistory = pathHistory.dropLast(1)
                            currentPath = pathHistory.last()
                        }
                    },
                    canGoBack = pathHistory.size > 1,
                    onRefresh = { loadDirectory() },
                    onCreateFile = { showCreateDialog = true },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (browserMode == BrowserMode.Container && currentPath.isEmpty()) {
                    Text(
                        text = "容器目录视图可直接浏览模型能工作的主要目录。工作区文件编辑请切到“工作区文件”。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }

                    currentItems.isEmpty() -> {
                        EmptyState(browserMode = browserMode, currentPath = currentPath)
                    }

                    else -> {
                        val folderCount = currentItems.count { it.isDirectory }
                        val fileCount = currentItems.size - folderCount
                        Text(
                            text = buildString {
                                append("共 ")
                                if (folderCount > 0) {
                                    append("${folderCount} 个文件夹")
                                    if (fileCount > 0) append("，")
                                }
                                if (fileCount > 0) {
                                    append("${fileCount} 个文件")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(currentItems, key = { "${it.path}:${it.name}" }) { item ->
                                FileSystemItemRow(
                                    item = item,
                                    onClick = {
                                        if (item.isDirectory) navigateTo(item.path) else openFile(item)
                                    },
                                    onShare = {
                                        val hostFile = item.hostFile ?: return@FileSystemItemRow
                                        val uri = if (browserMode == BrowserMode.Workspace) {
                                            SandboxEngine.getShareableUri(context, sandboxId, item.path)
                                        } else if (hostFile.isFile) {
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                hostFile,
                                            )
                                        } else {
                                            null
                                        }
                                        if (uri != null) {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = if (hostFile.isDirectory) "application/zip" else getMimeType(item.name)
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "分享文件"))
                                        }
                                    },
                                    onEdit = {
                                        if (item.canEdit) {
                                            selectedFile = item
                                            showEditMenu = true
                                        }
                                    },
                                    onDelete = {
                                        if (item.canDelete) {
                                            selectedFile = item
                                            showDeleteDialog = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )

    if (browserMode == BrowserMode.Workspace && showCreateDialog) {
        CreateFileDialog(
            sandboxId = sandboxId,
            currentPath = currentPath,
            onDismiss = { showCreateDialog = false },
            onSuccess = { loadDirectory() },
        )
    }

    if (browserMode == BrowserMode.Workspace && showRenameDialog && selectedFile != null) {
        RenameFileDialog(
            sandboxId = sandboxId,
            currentPath = currentPath,
            oldName = selectedFile!!.name,
            onDismiss = {
                showRenameDialog = false
                selectedFile = null
            },
            onSuccess = {
                loadDirectory()
                selectedFile = null
            },
        )
    }

    if (browserMode == BrowserMode.Workspace && showEditDialog && selectedFile != null) {
        EditFileDialog(
            sandboxId = sandboxId,
            filePath = selectedFile!!.path,
            fileName = selectedFile!!.name,
            onDismiss = {
                showEditDialog = false
                selectedFile = null
            },
            onSuccess = {
                loadDirectory()
                selectedFile = null
            },
        )
    }

    if (browserMode == BrowserMode.Workspace && showEditMenu && selectedFile != null) {
        EditOptionsMenuDialog(
            fileName = selectedFile!!.name,
            onRename = {
                showEditMenu = false
                showRenameDialog = true
            },
            onEditContent = {
                showEditMenu = false
                showEditDialog = true
            },
            onDismiss = {
                showEditMenu = false
                selectedFile = null
            },
        )
    }

    if (showDeleteDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                selectedFile = null
            },
            title = { Text("删除文件") },
            text = { Text("确定要删除文件 \"${selectedFile!!.name}\" 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteFile(selectedFile!!)
                        showDeleteDialog = false
                        selectedFile = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        selectedFile = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    if (previewTitle != null) {
        AlertDialog(
            onDismissRequest = {
                previewTitle = null
                previewContent = null
            },
            title = {
                Text(
                    text = previewTitle.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    text = previewContent.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        previewTitle = null
                        previewContent = null
                    },
                ) {
                    Text("关闭")
                }
            },
        )
    }
}

private suspend fun loadContainerItems(
    context: android.content.Context,
    sandboxId: String,
    prootManager: PRootManager,
    path: String,
): List<FileSystemItem> {
    if (path.isBlank()) {
        return containerRootShortcuts.map { shortcut ->
            FileSystemItem(
                name = shortcut.path,
                path = shortcut.path,
                isDirectory = true,
                size = 0L,
                modifiedTime = 0L,
                subtitle = shortcut.description,
            )
        }
    }

    val hostDir = SandboxEngine.resolveHostFileForContainerPath(context, sandboxId, path)
    if (hostDir != null && hostDir.exists() && hostDir.isDirectory) {
        return hostDir.listFiles()
            ?.map { file ->
                val childPath = if (path == "/") "/${file.name}" else "$path/${file.name}"
                FileSystemItem(
                    name = file.name,
                    path = childPath,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    modifiedTime = file.lastModified(),
                    hostFile = file,
                )
            }
            ?.sortedWith(compareBy<FileSystemItem>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    return prootManager.listContainerDirectory(sandboxId, path).map { item ->
        FileSystemItem(
            name = item.name,
            path = item.path,
            isDirectory = item.isDirectory,
            size = item.size,
            modifiedTime = item.modified,
        )
    }
}

private fun SandboxFileInfo.toWorkspaceItem(
    context: android.content.Context,
    sandboxId: String,
): FileSystemItem {
    val hostFile = SandboxEngine.resolveHostFileForContainerPath(
        context = context,
        assistantId = sandboxId,
        containerPath = if (path.isBlank()) "/workspace" else "/workspace/$path",
    )
    return FileSystemItem(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        modifiedTime = modified,
        hostFile = hostFile,
        canShare = true,
        canEdit = !isDirectory,
        canDelete = !isDirectory,
    )
}

@Composable
private fun BrowserModeTabs(
    current: BrowserMode,
    onSelect: (BrowserMode) -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrowserModeTab(
            text = "工作区文件",
            selected = current == BrowserMode.Workspace,
            onClick = { onSelect(BrowserMode.Workspace) },
        )
        BrowserModeTab(
            text = "容器目录",
            selected = current == BrowserMode.Container,
            onClick = { onSelect(BrowserMode.Container) },
        )
    }
}

@Composable
private fun BrowserModeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EmptyState(
    browserMode: BrowserMode,
    currentPath: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Lucide.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = when {
                    currentPath.isNotEmpty() -> "文件夹为空"
                    browserMode == BrowserMode.Container -> "容器目录为空或容器尚未运行"
                    else -> "工作区为空"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditOptionsMenuDialog(
    fileName: String,
    onRename: () -> Unit,
    onEditContent: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "编辑: $fileName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRename)
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Lucide.File, contentDescription = null)
                        Text("重命名文件")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEditContent)
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Lucide.FileText, contentDescription = null)
                        Text("编辑内容")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun BreadcrumbNavigation(
    browserMode: BrowserMode,
    pathHistory: List<String>,
    onPathClick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "位置: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val rootLabel = if (browserMode == BrowserMode.Workspace) "工作区" else "容器入口"
        if (pathHistory.size == 1 && pathHistory[0].isEmpty()) {
            Text(
                text = rootLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = rootLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onPathClick(0) },
                )

                pathHistory.drop(1).forEachIndexed { index, path ->
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = path.substringAfterLast('/').ifBlank { path },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == pathHistory.size - 2) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .widthIn(max = 140.dp)
                            .clickable { onPathClick(index + 1) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileManagerToolbar(
    currentPath: String,
    browserMode: BrowserMode,
    onBackClick: () -> Unit,
    canGoBack: Boolean,
    onRefresh: () -> Unit,
    onCreateFile: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClick, enabled = canGoBack) {
                Icon(
                    Lucide.ChevronLeft,
                    contentDescription = "返回上级",
                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }

            Text(
                text = when {
                    currentPath.isEmpty() && browserMode == BrowserMode.Workspace -> "工作区根目录"
                    currentPath.isEmpty() -> "容器入口"
                    else -> currentPath.substringAfterLast('/').ifBlank { currentPath }
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.widthIn(max = 180.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (browserMode == BrowserMode.Workspace) {
                IconButton(onClick = onCreateFile) {
                    Icon(Lucide.Plus, contentDescription = "新增文件")
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Lucide.RefreshCw, contentDescription = "刷新")
            }
        }
    }
}

@Composable
private fun FileSystemItemRow(
    item: FileSystemItem,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = when {
                    item.subtitle != null -> Lucide.FolderOpen
                    item.isDirectory -> Lucide.Folder
                    else -> getFileIcon(item.name)
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle ?: if (item.isDirectory) item.path else formatFileSize(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.canShare) {
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Lucide.Share2, contentDescription = "分享", modifier = Modifier.size(18.dp))
                }
            }
            if (item.canEdit) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Lucide.Wrench, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
            }
            if (item.canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun getFileIcon(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        fileName.endsWith(".jpg", true) ||
            fileName.endsWith(".jpeg", true) ||
            fileName.endsWith(".png", true) ||
            fileName.endsWith(".gif", true) ||
            fileName.endsWith(".webp", true) ||
            fileName.endsWith(".svg", true) -> Lucide.FileImage

        fileName.endsWith(".json", true) -> Lucide.FileJson
        fileName.endsWith(".txt", true) ||
            fileName.endsWith(".md", true) ||
            fileName.endsWith(".log", true) ||
            fileName.endsWith(".yaml", true) ||
            fileName.endsWith(".yml", true) -> Lucide.FileText

        fileName.endsWith(".zip", true) ||
            fileName.endsWith(".tar", true) ||
            fileName.endsWith(".gz", true) ||
            fileName.endsWith(".rar", true) ||
            fileName.endsWith(".7z", true) -> Lucide.FileArchive

        fileName.endsWith(".kt", true) ||
            fileName.endsWith(".java", true) ||
            fileName.endsWith(".py", true) ||
            fileName.endsWith(".js", true) ||
            fileName.endsWith(".ts", true) ||
            fileName.endsWith(".html", true) ||
            fileName.endsWith(".css", true) ||
            fileName.endsWith(".xml", true) ||
            fileName.endsWith(".sh", true) -> Lucide.FileCode

        else -> Lucide.FileType
    }
}

private fun getMimeType(fileName: String): String {
    return when {
        fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
        fileName.endsWith(".png", true) -> "image/png"
        fileName.endsWith(".gif", true) -> "image/gif"
        fileName.endsWith(".webp", true) -> "image/webp"
        fileName.endsWith(".svg", true) -> "image/svg+xml"
        fileName.endsWith(".txt", true) || fileName.endsWith(".md", true) -> "text/plain"
        fileName.endsWith(".json", true) -> "application/json"
        fileName.endsWith(".html", true) -> "text/html"
        fileName.endsWith(".pdf", true) -> "application/pdf"
        fileName.endsWith(".zip", true) -> "application/zip"
        else -> "*/*"
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}
