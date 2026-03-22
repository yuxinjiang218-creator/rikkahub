package me.rerere.rikkahub.ui.pages.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillEditorDocument
import me.rerere.rikkahub.data.skills.SkillInvalidEntry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current

    LaunchedEffect(Unit) {
        vm.refresh()
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showGitHubImportDialog by remember { mutableStateOf(false) }
    var editDocument by remember { mutableStateOf<SkillEditorDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<SkillCatalogEntry?>(null) }

    val zipImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importSkillZip(uri) { result ->
                result.fold(
                    onSuccess = { toaster.show("已导入技能包: $it") },
                    onFailure = { toaster.show(it.message ?: "ZIP 技能包导入失败") },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.skills_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { showGitHubImportDialog = true },
                ) {
                    Icon(HugeIcons.Download01, contentDescription = "Import from GitHub")
                }
                SmallFloatingActionButton(
                    onClick = { zipImporter.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                ) {
                    Icon(HugeIcons.FileImport, contentDescription = "导入 ZIP")
                }
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(HugeIcons.Add01, contentDescription = "新建 Skill")
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("summary") {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("本地技能库", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.rootPath.ifBlank { "技能库路径解析中..." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "支持创建、编辑、删除、ZIP 导入。目录型 skill 包默认包含 SKILL.md，可选 scripts / references / assets / agents/openai.yaml。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.isLoading) {
                item("loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            state.error?.let { error ->
                item("error") {
                    SkillInfoCard(title = "刷新失败", body = error, error = true)
                }
            }

            if (!state.isLoading && state.entries.isEmpty() && state.invalidEntries.isEmpty()) {
                item("empty") {
                    SkillInfoCard(
                        title = "还没有 Skills",
                        body = "点击右下角新建，或从 ZIP 导入现成 skill 包。",
                    )
                }
            }

            if (state.entries.isNotEmpty()) {
                item("valid-title") {
                    Text("可用 Skills", style = MaterialTheme.typography.titleMedium)
                }
                items(state.entries, key = { it.directoryName }) { entry ->
                    SkillEntryCard(
                        entry = entry,
                        onClick = { navController.navigate(Screen.SkillDetail(entry.directoryName)) },
                        onEdit = {
                            vm.loadSkillDocument(entry) { result ->
                                result.fold(
                                    onSuccess = { editDocument = it },
                                    onFailure = { toaster.show(it.message ?: "读取 Skill 失败") },
                                )
                            }
                        },
                        onDelete = if (entry.isBundled) null else { { deleteTarget = entry } },
                    )
                }
            }

            if (state.invalidEntries.isNotEmpty()) {
                item("invalid-title") {
                    Text("无效 Skills", style = MaterialTheme.typography.titleMedium)
                }
                items(state.invalidEntries, key = { "${it.directoryName}:${it.reason}" }) { entry ->
                    InvalidSkillCard(entry = entry, vm = vm)
                }
            }
        }
    }

    if (showCreateDialog) {
        SkillEditorDialog(
            title = "新建 Skill",
            confirmText = "创建",
            document = SkillEditorDocument(
                originalDirectoryName = "",
                directoryName = "",
                name = "",
                description = "",
                body = "",
            ),
            onDismiss = { showCreateDialog = false },
            onConfirm = { document ->
                vm.createSkill(
                    directoryName = document.directoryName,
                    name = document.name,
                    description = document.description,
                    body = document.body,
                ) { result ->
                    result.fold(
                        onSuccess = {
                            showCreateDialog = false
                            toaster.show("已创建 Skill: ${it.directoryName}")
                        },
                        onFailure = { toaster.show(it.message ?: "创建 Skill 失败") },
                    )
                }
            },
        )
    }

    if (showGitHubImportDialog) {
        ImportSkillDialog(
            onDismiss = { showGitHubImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showGitHubImportDialog = false
                    if (success) {
                        toaster.show("Imported Skill: $message")
                    } else {
                        toaster.show(message)
                    }
                }
            },
        )
    }

    editDocument?.let { document ->
        SkillEditorDialog(
            title = "编辑 Skill",
            confirmText = "保存",
            document = document,
            onDismiss = { editDocument = null },
            onConfirm = { updated ->
                vm.updateSkill(
                    originalDirectoryName = updated.originalDirectoryName,
                    directoryName = updated.directoryName,
                    name = updated.name,
                    description = updated.description,
                    body = updated.body,
                ) { result ->
                    result.fold(
                        onSuccess = {
                            editDocument = null
                            toaster.show("已更新 Skill: ${it.directoryName}")
                        },
                        onFailure = { toaster.show(it.message ?: "更新 Skill 失败") },
                    )
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 Skill") },
            text = { Text("确定要删除 ${entry.directoryName} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSkill(entry.directoryName) { result ->
                            result.fold(
                                onSuccess = {
                                    deleteTarget = null
                                    toaster.show("已删除 Skill: ${entry.directoryName}")
                                },
                                onFailure = { toaster.show(it.message ?: "删除 Skill 失败") },
                            )
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SkillInfoCard(
    title: String,
    body: String,
    error: Boolean = false,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkillEntryCard(
    entry: SkillCatalogEntry,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(
        onClick = onClick,
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Puzzle, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.directoryName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                val features = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasAgentConfig) add("agent")
                    if (entry.isBundled) add("builtin")
                }
                if (features.isNotEmpty()) {
                    Text(features.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.PencilEdit01, contentDescription = "编辑")
            }
            onDelete?.let {
                IconButton(onClick = it) {
                    Icon(HugeIcons.Delete01, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo") },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun InvalidSkillCard(
    entry: SkillInvalidEntry,
    vm: SkillsVM,
) {
    SkillInfoCard(
        title = entry.directoryName,
        body = vm.localizedInvalidReason(entry.reason),
        error = true,
    )
}

@Composable
private fun SkillEditorDialog(
    title: String,
    confirmText: String,
    document: SkillEditorDocument,
    onDismiss: () -> Unit,
    onConfirm: (SkillEditorDocument) -> Unit,
) {
    var name by remember(document) { mutableStateOf(document.name) }
    var directory by remember(document) { mutableStateOf(document.directoryName) }
    var description by remember(document) { mutableStateOf(document.description) }
    var body by remember(document) { mutableStateOf(document.body) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = directory, onValueChange = { directory = it }, label = { Text("目录名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("正文") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        document.copy(
                            directoryName = directory,
                            name = name,
                            description = description,
                            body = body,
                        )
                    )
                },
                enabled = name.isNotBlank() && description.isNotBlank(),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
