package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.CompressionEvent
import me.rerere.rikkahub.data.model.CompressionSummarySection
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.data.model.parseCompressionSummarySnapshot
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.CompressionRegenerationTarget
import me.rerere.rikkahub.service.StreamingTailState
import me.rerere.rikkahub.ui.components.ai.CompressContextDialog
import me.rerere.rikkahub.ui.components.ai.CompressContextDialogMode
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

private enum class CompressionCardPage {
    DialogueSummary,
    MemoryLedger,
}

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    timelineState: ChatTimelineUiState,
    streamingTailItem: ChatTimelineMessageItem? = null,
    state: LazyListState,
    loading: Boolean,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerateLatestCompression: (CompressionRegenerationTarget) -> Unit = {},
    onEditLatestDialogueSummary: (String) -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onSearchPreviewMessages: (String) -> Unit = {},
    onLoadOlder: suspend () -> Unit = {},
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                displayedNodes = timelineState.previewMessages,
                searchResults = timelineState.previewSearchResults,
                onSearchQueryChange = onSearchPreviewMessages,
                onJumpToMessage = onJumpToMessage,
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                timelineState = timelineState,
                streamingTailItem = streamingTailItem,
                state = state,
                loading = loading,
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
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onLoadOlder = onLoadOlder,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    timelineState: ChatTimelineUiState,
    streamingTailItem: ChatTimelineMessageItem?,
    state: LazyListState,
    loading: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerateLatestCompression: (CompressionRegenerationTarget) -> Unit,
    onEditLatestDialogueSummary: (String) -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onLoadOlder: suspend () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var isRecentScroll by remember { mutableStateOf(false) }
    val timelineItems = timelineState.items
    val stableMessageItems = remember(timelineItems) {
        timelineItems.filterIsInstance<ChatTimelineMessageItem>()
    }
    val messageListIndexByNodeId = remember(timelineItems) {
        buildMap<Uuid, Int> {
            timelineItems.forEachIndexed { index, item ->
                if (item is ChatTimelineMessageItem) {
                    put(item.model.node.id, index)
                }
            }
        }
    }
    val allMessageItems = remember(stableMessageItems, streamingTailItem) {
        if (streamingTailItem == null) {
            stableMessageItems
        } else {
            stableMessageItems + streamingTailItem
        }
    }
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    // 鑱婂ぉ閫夋嫨
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 鑷姩璺熼殢閿洏婊氬姩
    ImeLazyListAutoScroller(lazyListState = state)

    // 瀵硅瘽澶у皬璀﹀憡瀵硅瘽妗?
    val sizeInfo = rememberConversationSizeInfo(
        nodeCount = timelineState.totalStableCount + if (streamingTailItem != null) 1 else 0,
        lastAssistantInputTokens = timelineState.lastAssistantInputTokens,
    )
    var showSizeWarningDialog by rememberSaveable(timelineState.conversationTitle) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    val latestCompressionEventId = timelineState.latestCompressionEventId
    var expandedCompressionEventId by rememberSaveable(timelineState.conversationTitle) { mutableStateOf(latestCompressionEventId) }
    var showRegenerateConfirm by rememberSaveable(timelineState.conversationTitle) { mutableStateOf(false) }
    var regenerateTarget by rememberSaveable(timelineState.conversationTitle) {
        mutableStateOf(CompressionRegenerationTarget.DialogueSummary)
    }
    var latestCompressionPage by rememberSaveable(timelineState.conversationTitle) {
        mutableStateOf(CompressionCardPage.DialogueSummary)
    }
    var showEditLatestSummaryDialog by rememberSaveable(timelineState.conversationTitle) { mutableStateOf(false) }
    var editingLatestSummaryText by rememberSaveable(timelineState.conversationTitle) { mutableStateOf("") }
    LaunchedEffect(latestCompressionEventId) {
        expandedCompressionEventId = latestCompressionEventId
    }
    if (showRegenerateConfirm) {
        CompressContextDialog(
            mode = CompressContextDialogMode.RegenerateConfirm,
            onDismiss = { showRegenerateConfirm = false },
            regenerateTitle = stringResource(
                if (regenerateTarget == CompressionRegenerationTarget.DialogueSummary) {
                    R.string.chat_page_regenerate_dialogue_summary_title_v2
                } else {
                    R.string.chat_page_regenerate_memory_ledger_title_v2
                }
            ),
            regenerateDescription = stringResource(
                if (regenerateTarget == CompressionRegenerationTarget.DialogueSummary) {
                    R.string.chat_page_regenerate_dialogue_summary_desc_v2
                } else {
                    R.string.chat_page_regenerate_memory_ledger_desc_v2
                }
            ),
            regenerateActionLabel = stringResource(
                if (regenerateTarget == CompressionRegenerationTarget.DialogueSummary) {
                    R.string.chat_page_regenerate_dialogue_summary_action_v2
                } else {
                    R.string.chat_page_regenerate_memory_ledger_action_v2
                }
            ),
            onConfirmRegenerate = { onRegenerateLatestCompression(regenerateTarget) }
        )
    }
    if (showEditLatestSummaryDialog) {
        AlertDialog(
            onDismissRequest = { showEditLatestSummaryDialog = false },
            title = {
                Text(text = "编辑主摘要")
            },
            text = {
                OutlinedTextField(
                    value = editingLatestSummaryText,
                    onValueChange = { editingLatestSummaryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 18,
                    placeholder = {
                        Text(text = "在这里直接修正主摘要内容")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditLatestDialogueSummary(editingLatestSummaryText)
                        showEditLatestSummaryDialog = false
                    },
                    enabled = editingLatestSummaryText.isNotBlank()
                ) {
                    Text(text = "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditLatestSummaryDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 鍒ゆ柇鏈€杩戞槸鍚︽粴鍔?
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        var pendingAnchorNodeId by remember { mutableStateOf<Uuid?>(null) }
        var pendingAnchorOffset by remember { mutableStateOf(0) }
        LaunchedEffect(timelineState.hasOlder, timelineState.isLoadingOlder, state.firstVisibleItemIndex, timelineItems) {
            if (!timelineState.hasOlder || timelineState.isLoadingOlder || stableMessageItems.isEmpty()) return@LaunchedEffect
            if (!isRecentScroll && state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                return@LaunchedEffect
            }
            if (state.firstVisibleItemIndex > 2) return@LaunchedEffect
            val anchorItem = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                timelineItems.getOrNull(info.index) is ChatTimelineMessageItem
            } ?: return@LaunchedEffect
            val anchorNodeId = (timelineItems[anchorItem.index] as? ChatTimelineMessageItem)?.model?.node?.id
                ?: return@LaunchedEffect
            pendingAnchorNodeId = anchorNodeId
            pendingAnchorOffset = anchorItem.offset
            onLoadOlder()
        }

        LaunchedEffect(timelineItems, pendingAnchorNodeId) {
            val anchorNodeId = pendingAnchorNodeId ?: return@LaunchedEffect
            val anchorIndex = messageListIndexByNodeId[anchorNodeId]
            if (anchorIndex != null) {
                state.scrollToItem(anchorIndex, pendingAnchorOffset)
            }
            pendingAnchorNodeId = null
        }

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (timelineState.isLoadingOlder) {
                item("load_older_indicator") {
                    RabbitLoadingIndicator(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .size(24.dp)
                    )
                }
            }
            items(
                items = timelineItems,
                key = { item -> item.stableKey },
                contentType = { item -> item.contentType },
            ) { item ->
                when (item) {
                    is ChatTimelineCompressionItem -> {
                        CompressionBoundaryEvent(
                            event = item.model.event,
                            latest = item.model.isLatest,
                            expanded = expandedCompressionEventId == item.model.event.id,
                            latestPage = latestCompressionPage,
                            ledgerStatus = item.model.ledgerStatus,
                            ledgerError = item.model.ledgerError,
                            onRegenerate = if (item.model.isLatest) {
                                {
                                    regenerateTarget = if (latestCompressionPage == CompressionCardPage.DialogueSummary) {
                                        CompressionRegenerationTarget.DialogueSummary
                                    } else {
                                        CompressionRegenerationTarget.MemoryLedger
                                    }
                                    showRegenerateConfirm = true
                                }
                            } else {
                                null
                            },
                            onEditLatestDialogueSummary = if (item.model.isLatest) {
                                {
                                    editingLatestSummaryText = item.model.event.dialogueSummaryText
                                    latestCompressionPage = CompressionCardPage.DialogueSummary
                                    showEditLatestSummaryDialog = true
                                }
                            } else {
                                null
                            },
                            onPageChanged = { latestCompressionPage = it },
                            onToggle = {
                                expandedCompressionEventId = if (expandedCompressionEventId == item.model.event.id) {
                                    null
                                } else {
                                    item.model.event.id
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is ChatTimelineMessageItem -> {
                        ListSelectableItem(
                            key = item.model.node.id,
                            onSelectChange = {
                                if (!selectedItems.contains(item.model.node.id)) {
                                    selectedItems.add(item.model.node.id)
                                } else {
                                    selectedItems.remove(item.model.node.id)
                                }
                            },
                            selectedKeys = selectedItems,
                            enabled = selecting,
                        ) {
                            ChatMessage(
                                renderModel = item.model.renderModel,
                                loading = loading && item.model.isLastMessage && streamingTailItem == null,
                                onRegenerate = {
                                    onRegenerate(item.model.message)
                                },
                                onEdit = {
                                    onEdit(item.model.message)
                                },
                                onFork = {
                                    onForkMessage(item.model.message)
                                },
                                onDelete = {
                                onDelete(item.model.message)
                                },
                                onShare = {
                                    selecting = true
                                    selectedItems.clear()
                                    selectedItems.addAll(
                                        allMessageItems
                                            .filter { candidate -> candidate.model.globalIndex <= item.model.globalIndex }
                                            .map { candidate -> candidate.model.node.id }
                                    )
                                },
                                onUpdate = {
                                    onUpdateMessage(it)
                                },
                                isFavorite = item.model.node.isFavorite,
                                onToggleFavorite = {
                                    onToggleFavorite?.invoke(item.model.node)
                                },
                                onTranslate = onTranslate,
                                onClearTranslation = onClearTranslation,
                                onToolApproval = onToolApproval,
                                onToolAnswer = onToolAnswer,
                                lastMessage = item.model.isLastMessage && streamingTailItem == null,
                            )
                        }
                    }
                }
            }

            if (streamingTailItem != null) {
                item(
                    key = streamingTailItem.stableKey,
                    contentType = streamingTailItem.contentType,
                ) {
                    ListSelectableItem(
                        key = streamingTailItem.model.node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(streamingTailItem.model.node.id)) {
                                selectedItems.add(streamingTailItem.model.node.id)
                            } else {
                                selectedItems.remove(streamingTailItem.model.node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        ChatMessage(
                            renderModel = streamingTailItem.model.renderModel,
                            loading = loading,
                            onRegenerate = {
                                onRegenerate(streamingTailItem.model.message)
                            },
                            onEdit = {
                                onEdit(streamingTailItem.model.message)
                            },
                            onFork = {
                                onForkMessage(streamingTailItem.model.message)
                            },
                            onDelete = {
                                onDelete(streamingTailItem.model.message)
                            },
                            onShare = {
                                selecting = true
                                selectedItems.clear()
                                selectedItems.addAll(
                                    allMessageItems
                                        .filter { candidate -> candidate.model.globalIndex <= streamingTailItem.model.globalIndex }
                                        .map { candidate -> candidate.model.node.id }
                                )
                            },
                            onUpdate = {
                                onUpdateMessage(it)
                            },
                            isFavorite = streamingTailItem.model.node.isFavorite,
                            onToggleFavorite = {
                                onToggleFavorite?.invoke(streamingTailItem.model.node)
                            },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            lastMessage = true,
                        )
                    }
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    RabbitLoadingIndicator(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(28.dp)
                    )
                }
            }

            // 涓轰簡鑳芥纭粴鍔ㄥ埌杩?
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 閿欒娑堟伅鍗＄墖
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 瀹屾垚閫夋嫨
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(allMessageItems.map { it.model.node.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = allMessageItems.filter { it.model.node.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 瀵煎嚭瀵硅瘽妗?
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversationTitle = timelineState.conversationTitle,
                selectedMessages = allMessageItems
                    .filter { it.model.node.id in selectedItems }
                    .map { it.model.message }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 娑堟伅蹇€熻烦杞?
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            if (timelineState.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    suggestions = timelineState.chatSuggestions,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun CompressionBoundaryEvent(
    event: CompressionEvent,
    latest: Boolean,
    expanded: Boolean,
    latestPage: CompressionCardPage,
    ledgerStatus: String?,
    ledgerError: String?,
    onRegenerate: (() -> Unit)?,
    onEditLatestDialogueSummary: (() -> Unit)?,
    onPageChanged: (CompressionCardPage) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialogueSnapshot = remember(event.summarySnapshot) {
        parseCompressionSummarySnapshot(event.summarySnapshot)
    }
    val ledgerSnapshotRaw = remember(event.ledgerSnapshot, event.summarySnapshot) {
        event.ledgerSnapshot.ifBlank { event.summarySnapshot }
    }
    val ledgerSnapshot = remember(ledgerSnapshotRaw) {
        parseCompressionSummarySnapshot(ledgerSnapshotRaw)
    }
    val dialoguePreview = remember(event.dialogueSummaryPreview, event.dialogueSummaryText, event.summarySnapshot) {
        event.dialogueSummaryPreview
            .ifBlank { dialogueSnapshot?.preview.orEmpty() }
            .ifBlank { event.dialogueSummaryText.trim().replace("\n", " ").take(220) }
    }
    val density = LocalDensity.current
    var dialoguePageHeightPx by remember(event.id) { mutableStateOf(0) }
    // Keep the shared pager card anchored to the dialogue-summary page height.
    // Without this, a short ledger status page collapses the card and makes the
    // return swipe feel broken even though the summary page still has long content.
    val sharedExpandedMinHeight = remember(dialoguePageHeightPx, density) {
        with(density) { dialoguePageHeightPx.toDp() }
    }
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (latest) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .combinedClickable(
                        onClick = onToggle,
                        onLongClick = {
                            // Only the latest summary is editable, and long-press should still
                            // work from the collapsed preview because that card is summary-led.
                            if (!expanded || latestPage == CompressionCardPage.DialogueSummary) {
                                onEditLatestDialogueSummary?.invoke()
                            }
                        }
                    ),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                tonalElevation = 2.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 4.dp, height = 42.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compression_boundary_title_v2),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = event.createdAt.toLocalDateTime(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (onRegenerate != null) {
                            TextButton(onClick = onRegenerate) {
                                Text(
                                    text = stringResource(
                                        if (latestPage == CompressionCardPage.DialogueSummary) {
                                            R.string.chat_page_regenerate_dialogue_summary_action_v2
                                        } else if (ledgerStatus == "ready" && ledgerSnapshotRaw.isNotBlank()) {
                                            R.string.chat_page_regenerate_memory_ledger_action_v2
                                        } else {
                                            R.string.chat_page_generate_memory_ledger_action_v2
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    dialoguePreview.takeIf { it.isNotBlank() }?.let { preview ->
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (expanded) {
                        val pagerState = rememberPagerState(
                            initialPage = if (latestPage == CompressionCardPage.DialogueSummary) 0 else 1,
                            pageCount = { 2 }
                        )
                        LaunchedEffect(pagerState.currentPage) {
                            onPageChanged(
                                if (pagerState.currentPage == 0) {
                                    CompressionCardPage.DialogueSummary
                                } else {
                                    CompressionCardPage.MemoryLedger
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompressionPageBadge(
                                text = stringResource(R.string.chat_page_dialogue_summary_page_v2),
                                active = pagerState.currentPage == 0
                            )
                            CompressionPageBadge(
                                text = stringResource(R.string.chat_page_memory_ledger_page_v2),
                                active = pagerState.currentPage == 1
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = stringResource(R.string.chat_page_compression_swipe_hint_v2),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = sharedExpandedMinHeight)
                        ) { page ->
                            when (page) {
                                0 -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { size ->
                                            dialoguePageHeightPx = kotlin.math.max(dialoguePageHeightPx, size.height)
                                        }
                                ) {
                                    DialogueSummaryPage(
                                        summaryText = event.dialogueSummaryText,
                                        fallbackSnapshotRaw = event.summarySnapshot,
                                        fallbackSnapshot = dialogueSnapshot,
                                    )
                                }

                                else -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = sharedExpandedMinHeight)
                                ) {
                                    MemoryLedgerPage(
                                        snapshotRaw = ledgerSnapshotRaw,
                                        snapshot = ledgerSnapshot,
                                        ledgerStatus = ledgerStatus,
                                        ledgerError = ledgerError,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Text(
                    text = event.createdAt.toLocalDateTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun CompressionPageBadge(text: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun DialogueSummaryPage(
    summaryText: String,
    fallbackSnapshotRaw: String,
    fallbackSnapshot: me.rerere.rikkahub.data.model.CompressionSummarySnapshot?,
) {
    if (summaryText.isNotBlank()) {
        Text(
            text = summaryText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }

    if (fallbackSnapshot != null && fallbackSnapshot.sections.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            fallbackSnapshot.sections.forEachIndexed { index, section ->
                CompressionSummarySectionView(section = section)
                if (index != fallbackSnapshot.sections.lastIndex) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    )
                }
            }
        }
    } else if (fallbackSnapshotRaw.isNotBlank()) {
        Text(
            text = fallbackSnapshotRaw,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemoryLedgerPage(
    snapshotRaw: String,
    snapshot: me.rerere.rikkahub.data.model.CompressionSummarySnapshot?,
    ledgerStatus: String?,
    ledgerError: String?,
) {
    when {
        ledgerStatus == "stale" -> {
            Text(
                text = stringResource(R.string.chat_page_memory_ledger_stale_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ledgerStatus == "pending" -> {
            Text(
                text = stringResource(R.string.chat_page_memory_ledger_pending_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ledgerStatus == "running" -> {
            Text(
                text = stringResource(R.string.chat_page_memory_ledger_generating_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ledgerStatus == "failed" -> {
            Text(
                text = buildString {
                    append(stringResource(R.string.chat_page_memory_ledger_failed_v2))
                    if (!ledgerError.isNullOrBlank()) {
                        append("\n")
                        append(ledgerError)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        snapshot != null && snapshot.sections.isNotEmpty() -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                snapshot.sections.forEachIndexed { index, section ->
                    CompressionSummarySectionView(section = section)
                    if (index != snapshot.sections.lastIndex) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        )
                    }
                }
            }
        }

        snapshotRaw.isNotBlank() -> {
            Text(
                text = snapshotRaw,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        else -> {
            Text(
                text = stringResource(R.string.chat_page_memory_ledger_empty_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompressionSummarySectionView(
    section: CompressionSummarySection,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        section.items.forEach { item ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 鎻愬彇鍖呭惈鎼滅储璇嶇殑鏂囨湰鐗囨锛岀‘淇濆尮閰嶈瘝鍦ㄥ紑澶村彲瑙?
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 鐩存帴浠庡尮閰嶈瘝寮€濮嬫樉绀猴紝纭繚鍖归厤璇嶅湪鏈€鍓嶉潰
    val snippet = text.substring(matchIndex)

    // 鍙湪鍓嶉潰鏈夊唴瀹规椂娣诲姞鐪佺暐鍙?
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 娣诲姞楂樹寒鍓嶇殑鏂囨湰
            append(text.substring(startIndex, index))

            // 娣诲姞楂樹寒鏂囨湰
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 娣诲姞鍓╀綑鏂囨湰
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    displayedNodes: List<ChatPreviewMessageItem>,
    searchResults: List<ConversationPreviewSearchResult>,
    onSearchQueryChange: (String) -> Unit,
    onJumpToMessage: (Int) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        onSearchQueryChange(searchQuery)
    }

    // 杩囨护娑堟伅锛屽悓鏃朵繚鐣欏師濮?index 閬垮厤鍚庣画 O(n) indexOf 鏌ユ壘
    val filteredMessages = remember(displayedNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            displayedNodes
        } else {
            displayedNodes.filter { node -> node.message.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize(),
    ) {
        // 鎼滅储妗?
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 娑堟伅棰勮
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (searchQuery.isBlank()) {
                items(
                    items = filteredMessages,
                    key = { item -> item.nodeId },
                ) { node ->
                    val message = node.message
                    val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                            ),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        onJumpToMessage(node.globalIndex)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                                val highlightedText = remember(searchQuery, message) {
                                    val fullText = message.toText().trim().ifBlank { "[...]" }
                                    val messageText = extractMatchingSnippet(
                                        text = fullText,
                                        query = searchQuery
                                    )
                                    buildHighlightedText(
                                        text = messageText,
                                        query = searchQuery,
                                        highlightColor = highlightColor
                                    )
                                }
                                Text(
                                    text = highlightedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            } else {
                items(
                    items = searchResults,
                    key = { result -> "${result.nodeId}-${result.messageId}" },
                ) { result ->
                    val message = result.message
                    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                    val highlightedText = remember(searchQuery, result) {
                        buildHighlightedText(
                            text = result.snippet.ifBlank { message.toText().trim().ifBlank { "[...]" } },
                            query = searchQuery,
                            highlightColor = highlightColor,
                        )
                    }
                    val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                            ),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable { onJumpToMessage(result.globalIndex) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = highlightedText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(suggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
