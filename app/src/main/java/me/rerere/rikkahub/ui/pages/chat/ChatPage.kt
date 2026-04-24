package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.CompressionRegenerationTarget
import me.rerere.rikkahub.service.StreamingTailState
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.LedgerGenerationDialog
import me.rerere.rikkahub.ui.components.sandbox.SandboxFileManagerDialog
import me.rerere.rikkahub.ui.components.workflow.WorkflowFloatingPanel
import me.rerere.rikkahub.ui.components.workflow.WorkflowSidebarHandle
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.stableConversation.collectAsStateWithLifecycle()
    val chatTimelineUiState by vm.chatTimelineUiState.collectAsStateWithLifecycle()
    val chatPreviewUiState by vm.chatPreviewUiState.collectAsStateWithLifecycle()
    val chatStreamingTailUiState by vm.chatStreamingTailUiState.collectAsStateWithLifecycle()
    val chatChromeState by vm.chatChromeState.collectAsStateWithLifecycle()
    val chatInputUiState by vm.chatInputUiState.collectAsStateWithLifecycle()
    val streamingUiTick by vm.streamingUiTick.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    val inputState = vm.inputState
    var pendingCompressionScrollEventId by rememberSaveable(id) { mutableStateOf<Long?>(null) }

    // Initialize input state from incoming files/text arguments
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(vm, chatTimelineUiState.messageItems.size, chatTimelineUiState.compressionItems.size) {
        if (nodeId == null && !vm.chatListInitialized && chatTimelineUiState.totalListItemCount() > 0) {
            snapshotFlow { chatListState.layoutInfo.totalItemsCount }
                .first { it > 0 }
            chatListState.scrollToItem((chatListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            vm.chatListInitialized = true
        }
    }

    LaunchedEffect(nodeId, chatTimelineUiState.messageItems, chatTimelineUiState.compressionItems) {
        if (nodeId != null && chatTimelineUiState.totalListItemCount() > 0 && !vm.chatListInitialized) {
            val targetIndex = chatTimelineUiState.listIndexForNode(nodeId)
            if (targetIndex != null) {
                snapshotFlow { chatListState.layoutInfo.totalItemsCount }
                    .first { it > targetIndex }
                chatListState.scrollToItem(targetIndex)
                vm.chatListInitialized = true
            }
        }
    }

    LaunchedEffect(vm, id) {
        vm.compressionScrollEvents.collect { (conversationId, eventId) ->
            if (conversationId != id) return@collect
            // Keep the target event id until the conversation state actually contains the
            // new compression boundary card. Emitting the scroll event and updating the
            // conversation flow are asynchronous, so scrolling immediately is easy to lose.
            pendingCompressionScrollEventId = eventId
        }
    }

    LaunchedEffect(
        pendingCompressionScrollEventId,
        chatTimelineUiState.compressionItems,
    ) {
        val eventId = pendingCompressionScrollEventId ?: return@LaunchedEffect
        val targetIndex = chatTimelineUiState.listIndexForCompressionEvent(eventId) ?: return@LaunchedEffect

        // Wait until LazyColumn has laid out the target item before jumping to it.
        repeat(20) {
            if (chatListState.layoutInfo.totalItemsCount > targetIndex) {
                chatListState.animateScrollToItem(targetIndex)
                pendingCompressionScrollEventId = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50)
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    setting = setting,
                    conversation = conversation,
                    chatTimelineUiState = chatTimelineUiState,
                    chatPreviewUiState = chatPreviewUiState,
                    chatStreamingTailUiState = chatStreamingTailUiState,
                    chatChromeState = chatChromeState,
                    chatInputUiState = chatInputUiState,
                    streamingUiTick = streamingUiTick,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    setting = setting,
                    conversation = conversation,
                    chatTimelineUiState = chatTimelineUiState,
                    chatPreviewUiState = chatPreviewUiState,
                    chatStreamingTailUiState = chatStreamingTailUiState,
                    chatChromeState = chatChromeState,
                    chatInputUiState = chatInputUiState,
                    streamingUiTick = streamingUiTick,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun StreamingChatList(
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    state: LazyListState,
    loading: Boolean,
    previewMode: Boolean,
    settings: Settings,
    hazeState: dev.chrisbanes.haze.HazeState,
    chatTimelineUiState: ChatTimelineUiState,
    chatPreviewUiState: ChatPreviewUiState,
    chatStreamingTailUiState: ChatStreamingTailUiState,
    streamingUiTick: Long,
    conversationTitle: String,
    chatSuggestions: List<String>,
    lastAssistantInputTokens: Int,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerateLatestCompression: (CompressionRegenerationTarget) -> Unit,
    onEditLatestDialogueSummary: (String) -> Unit,
    onRegenerate: (me.rerere.ai.ui.UIMessage) -> Unit,
    onEdit: (me.rerere.ai.ui.UIMessage) -> Unit,
    onForkMessage: (me.rerere.ai.ui.UIMessage) -> Unit,
    onDelete: (me.rerere.ai.ui.UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((me.rerere.ai.ui.UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (me.rerere.ai.ui.UIMessage) -> Unit,
    onJumpToMessage: (Int) -> Unit,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
    onToggleFavorite: ((MessageNode) -> Unit)?,
    onSearchPreviewMessages: (String) -> Unit,
    onAutoScrollCheck: (String) -> Unit,
) {
    val autoScrollTargetIndex = chatTimelineUiState.totalListItemCount(
        includeStreamingTail = chatStreamingTailUiState.item != null,
        includeLoadingIndicator = loading,
    )

    StreamingChatListAutoScrollEffect(
        state = state,
        innerPadding = innerPadding,
        loading = loading,
        streamingUiTick = streamingUiTick,
        targetScrollIndex = autoScrollTargetIndex,
        enableAutoScroll = settings.displaySetting.enableAutoScroll && !previewMode,
        onAutoScrollCheck = onAutoScrollCheck,
    )

    ChatList(
        innerPadding = innerPadding,
        timelineState = chatTimelineUiState,
        previewState = chatPreviewUiState,
        streamingTailItem = chatStreamingTailUiState.item,
        state = state,
        loading = loading,
        previewMode = previewMode,
        conversationTitle = conversationTitle,
        chatSuggestions = chatSuggestions,
        lastAssistantInputTokens = lastAssistantInputTokens,
        settings = settings,
        hazeState = hazeState,
        errors = errors,
        onDismissError = onDismissError,
        onClearAllErrors = onClearAllErrors,
        onRegenerateLatestCompression = onRegenerateLatestCompression,
        onEditLatestDialogueSummary = onEditLatestDialogueSummary,
        onRegenerate = onRegenerate,
        onEdit = onEdit,
        onForkMessage = onForkMessage,
        onDelete = onDelete,
        onUpdateMessage = onUpdateMessage,
        onClickSuggestion = onClickSuggestion,
        onTranslate = onTranslate,
        onClearTranslation = onClearTranslation,
        onJumpToMessage = onJumpToMessage,
        onToolApproval = onToolApproval,
        onToolAnswer = onToolAnswer,
        onToggleFavorite = onToggleFavorite,
        onSearchPreviewMessages = onSearchPreviewMessages,
    )
}

@Composable
private fun StreamingChatListAutoScrollEffect(
    state: LazyListState,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    loading: Boolean,
    streamingUiTick: Long,
    targetScrollIndex: Int,
    enableAutoScroll: Boolean,
    onAutoScrollCheck: (String) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    if (!enableAutoScroll) return

    LaunchedEffect(streamingUiTick, loading, targetScrollIndex) {
        if (!loading || streamingUiTick <= 0L) return@LaunchedEffect
        val visibleItemsInfo = state.layoutInfo.visibleItemsInfo
        val atBottom = visibleItemsInfo.isAtBottom(state = state, density = density, innerPadding = innerPadding)
        onAutoScrollCheck(
            "tick=$streamingUiTick loading=$loading atBottom=$atBottom scrolling=${state.isScrollInProgress}"
        )
        if (!state.isScrollInProgress && atBottom) {
            state.requestScrollToItem(targetScrollIndex)
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    chatTimelineUiState: ChatTimelineUiState,
    chatPreviewUiState: ChatPreviewUiState,
    chatStreamingTailUiState: ChatStreamingTailUiState,
    chatChromeState: ChatChromeUiState,
    chatInputUiState: ChatInputUiState,
    streamingUiTick: Long,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var previewMode by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val hazeState = rememberHazeState()

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)
    val assistant = setting.assistants.find { it.id == conversation.assistantId }
        ?: setting.getCurrentAssistant()
    val workflowEnabled = chatChromeState.workflowEnabled
    val workflowActive = chatChromeState.workflowActive
    var showWorkflowPanel by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var showSandboxFileManager by rememberSaveable(conversation.id) { mutableStateOf(false) }

    LaunchedEffect(workflowEnabled, workflowActive) {
        if (!workflowEnabled || !workflowActive) {
            showWorkflowPanel = false
        }
    }

    LaunchedEffect(
        previewMode,
        chatTimelineUiState.messageItems.size,
        chatTimelineUiState.compressionItems.size,
    ) {
        vm.recordUiDiagnostic(
            category = "chat-list-mode",
            detail = "preview=$previewMode messages=${chatTimelineUiState.messageItems.size} compression=${chatTimelineUiState.compressionItems.size}",
            phase = if (chatTimelineUiState.compressionItems.isEmpty()) "main-compatible" else "augmented",
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting)
        Scaffold(
            topBar = {
                TopBar(
                    chatChromeState = chatChromeState,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = chatInputUiState.loading,
                    settings = setting,
                    messageCount = chatInputUiState.messageCount,
                    mcpManager = vm.mcpManager,
                    hazeState = hazeState,
                    autoCompressionUiState = chatInputUiState.compressionUiState,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    onCancelCompressionProgress = {
                        vm.cancelCompressionWork()
                    },
                    enableSearch = chatInputUiState.enableWebSearch,
                    onToggleSearch = {
                        vm.updateSettings(setting.copy(enableWebSearch = !chatInputUiState.enableWebSearch))
                    },
                    workflowEnabled = chatInputUiState.workflowEnabled,
                    workflowActive = chatInputUiState.workflowActive,
                    onToggleWorkflow = {
                        if (conversation.workflowState == null) {
                            vm.initializeWorkflowState()
                        } else {
                            vm.disableWorkflowState()
                        }
                    },
                    onOpenSandboxFileManager = {
                        showSandboxFileManager = true
                    },
                    onSendClick = {
                        if (chatInputUiState.currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(chatListState.layoutInfo.totalItemsCount + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(chatListState.layoutInfo.totalItemsCount + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onCompressContext = { additionalPrompt, keepRecentMessages, autoCompressEnabled, autoCompressTriggerTokens, generateMemoryLedger ->
                        vm.handleCompressContext(
                            additionalPrompt = additionalPrompt,
                            keepRecentMessages = keepRecentMessages,
                            autoCompressEnabled = autoCompressEnabled,
                            autoCompressTriggerTokens = autoCompressTriggerTokens,
                            generateMemoryLedger = generateMemoryLedger,
                        )
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            StreamingChatList(
                innerPadding = innerPadding,
                state = chatListState,
                loading = chatInputUiState.loading,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                chatTimelineUiState = chatTimelineUiState,
                chatPreviewUiState = chatPreviewUiState,
                chatStreamingTailUiState = chatStreamingTailUiState,
                streamingUiTick = streamingUiTick,
                conversationTitle = conversation.title,
                chatSuggestions = conversation.chatSuggestions,
                lastAssistantInputTokens = conversation.messageNodes.lastAssistantInputTokens(),
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerateLatestCompression = { target -> vm.regenerateLatestCompression(target) },
                onEditLatestDialogueSummary = { summary -> vm.editLatestDialogueSummary(summary) },
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateMessageNode(newNode)
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        val targetIndex = chatTimelineUiState.listIndexForMessage(index)
                        if (targetIndex != null) {
                            snapshotFlow { chatListState.layoutInfo.totalItemsCount }
                                .first { it > targetIndex }
                            chatListState.animateScrollToItem(targetIndex)
                        }
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onSearchPreviewMessages = vm::searchPreviewMessages,
                onAutoScrollCheck = { detail ->
                    vm.recordUiDiagnostic(
                        category = "auto-scroll-check",
                        detail = detail,
                        phase = "tailTick",
                    )
                },
            )
        }

        if (workflowEnabled && workflowActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 96.dp, end = 8.dp, bottom = 120.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                WorkflowSidebarHandle(
                    onClick = {
                        showWorkflowPanel = !showWorkflowPanel
                    }
                )
            }

            WorkflowFloatingPanel(
                visible = showWorkflowPanel,
                onDismiss = { showWorkflowPanel = false },
                currentPhase = chatChromeState.workflowPhase ?: me.rerere.rikkahub.data.model.WorkflowPhase.PLAN,
                onPhaseChange = { phase ->
                    vm.updateWorkflowPhase(phase)
                },
            )
        }

        if (showSandboxFileManager) {
            SandboxFileManagerDialog(
                sandboxId = conversation.id.toString(),
                onDismiss = { showSandboxFileManager = false }
            )
        }

        if (chatInputUiState.showLedgerGenerationDialog) {
            LedgerGenerationDialog(
                onCancel = {
                    vm.cancelCompressionWork()
                }
            )
        }
    }
}

@Composable
private fun TopBar(
    chatChromeState: ChatChromeUiState,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (chatChromeState.hasMessages) {
                        titleState.open(chatChromeState.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    Text(
                        text = chatChromeState.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatChromeState.subtitle != null) {
                        Text(
                            text = chatChromeState.subtitle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

private fun List<LazyListItemInfo>.isAtBottom(
    state: LazyListState,
    density: androidx.compose.ui.unit.Density,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
): Boolean {
    val lastItem = lastOrNull() ?: return false
    val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
    val lastPos = lastItem.offset + lastItem.size
    val inputPos = state.layoutInfo.viewportEndOffset - inputBarHeight.toInt()
    return lastPos <= inputPos - 8
}
