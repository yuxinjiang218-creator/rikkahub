package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.reflect.full.primaryConstructor

private const val MAX_SEARCH_API_KEYS = 100
private val SEARCH_API_KEY_SPLIT_REGEX = "[\\s,]+".toRegex()

@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_search_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    searchServices = listOf(SearchServiceOptions.BingLocalOptions()) + settings.searchServices
                                )
                            )
                            scope.launch {
                                lazyListState.animateScrollToItem(0)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_page_search_add_provider)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) {
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            // Search services start at visual index 0 after the header removal.
            val offset = 0
            val fromIndex = from.index - offset
            val toIndex = to.index - offset

            if (fromIndex >= 0 && toIndex >= 0 && fromIndex < settings.searchServices.size && toIndex < settings.searchServices.size) {
                val newServices = settings.searchServices.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                vm.updateSettings(
                    settings.copy(
                        searchServices = newServices
                    )
                )
            }
        }
        val haptic = LocalHapticFeedback.current

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = lazyListState
        ) {
            // Search service list.
            items(settings.searchServices, key = { it.id }) { service ->
                val index = settings.searchServices.indexOf(service)
                ReorderableItem(
                    state = reorderableState,
                    key = service.id
                ) { isDragging ->
                    SearchProviderCard(
                        service = service,
                        onUpdateService = { updatedService ->
                            val newServices = settings.searchServices.toMutableList()
                            newServices[index] = updatedService
                            vm.updateSettings(
                                settings.copy(
                                    searchServices = newServices
                                )
                            )
                        },
                        onDeleteService = {
                            if (settings.searchServices.size > 1) {
                                val newServices = settings.searchServices.toMutableList()
                                newServices.removeAt(index)
                                vm.updateSettings(
                                    settings.copy(
                                        searchServices = newServices
                                    )
                                )
                            }
                        },
                        canDelete = settings.searchServices.size > 1,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        dragHandle = {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                        }
                    )
                }
            }

            // Common search options.
            item("common_options") {
                CommonOptions(
                    settings = settings,
                    onUpdate = { options ->
                        vm.updateSettings(
                            settings.copy(
                                searchCommonOptions = options
                            )
                        )
                    }
                )
            }
        }
    }
}


@Composable
private fun SearchProviderCard(
    service: SearchServiceOptions,
    onUpdateService: (SearchServiceOptions) -> Unit,
    onDeleteService: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = {}
) {
    var options by remember(service) {
        mutableStateOf(service)
    }
    var expand by remember { mutableStateOf(false) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Select(
                    options = SearchServiceOptions.TYPES.keys.toList(),
                    selectedOption = options::class,
                    optionToString = { SearchServiceOptions.TYPES[it] ?: "[Unknown]" },
                    onOptionSelected = {
                        options = it.primaryConstructor!!.callBy(mapOf())
                        onUpdateService(options)
                    },
                    optionLeading = {
                        AutoAIIcon(
                            name = SearchServiceOptions.TYPES[it] ?: it.simpleName ?: "unknown",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    leading = {
                        AutoAIIcon(
                            name = SearchServiceOptions.TYPES[options::class] ?: "unknown",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        expand = !expand
                    }
                ) {
                    Icon(
                        imageVector = if (expand) HugeIcons.Cancel01 else HugeIcons.QuillWrite01,
                        contentDescription = if (expand) "Hide details" else "Show details"
                    )
                }
            }

            SearchAbilityTagLine(options = options, modifier = Modifier.padding(horizontal = 8.dp))

            AnimatedVisibility(expand) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (options) {
                        is SearchServiceOptions.TavilyOptions -> {
                            TavilyOptions(options as SearchServiceOptions.TavilyOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.ExaOptions -> {
                            ExaOptions(options as SearchServiceOptions.ExaOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.ZhipuOptions -> {
                            ZhipuOptions(options as SearchServiceOptions.ZhipuOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.SearXNGOptions -> {
                            SearXNGOptions(options as SearchServiceOptions.SearXNGOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.LinkUpOptions -> {
                            SearchLinkUpOptions(options as SearchServiceOptions.LinkUpOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.BraveOptions -> {
                            BraveOptions(options as SearchServiceOptions.BraveOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.MetasoOptions -> {
                            MetasoOptions(options as SearchServiceOptions.MetasoOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.OllamaOptions -> {
                            OllamaOptions(options as SearchServiceOptions.OllamaOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.PerplexityOptions -> {
                            PerplexityOptions(options as SearchServiceOptions.PerplexityOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.BingLocalOptions -> {}

                        is SearchServiceOptions.FirecrawlOptions -> {
                            FirecrawlOptions(options as SearchServiceOptions.FirecrawlOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.JinaOptions -> {
                            JinaOptions(options as SearchServiceOptions.JinaOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.BochaOptions -> {
                            BochaOptions(options as SearchServiceOptions.BochaOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.RikkaHubOptions -> {
                            RikkaHubOptions(options as SearchServiceOptions.RikkaHubOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }

                        is SearchServiceOptions.RikkaLocalOptions -> {}

                        is SearchServiceOptions.GrokOptions -> {
                            GrokOptions(options as SearchServiceOptions.GrokOptions) {
                                options = it
                                onUpdateService(options)
                            }
                        }
                    }

                    ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                        SearchService.getService(options).Description()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    IconButton(
                        onClick = onDeleteService
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_page_search_delete_provider)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = {}
                ) {
                    dragHandle()
                }
            }
        }
    }
}

@Composable
fun SearchAbilityTagLine(
    modifier: Modifier = Modifier,
    options: SearchServiceOptions
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Tag(
            type = TagType.DEFAULT,
        ) {
            Text(stringResource(R.string.search_ability_search))
        }
        if (SearchService.getService(options).scrapingParameters != null) {
            Tag(
                type = TagType.DEFAULT,
            ) {
                Text(stringResource(R.string.search_ability_scrape))
            }
        }
    }
}

@Composable
private fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit
) {
    SearchApiKeysEditor(
        apiKeysRaw = options.apiKey,
        poolLabel = "Tavily Key Pool",
        onUpdate = { updated ->
            onUpdateOptions(options.copy(apiKey = updated))
        }
    )

    FormItem(
        label = {
            Text("Depth")
        }
    ) {
        val depthOptions = listOf("basic", "advanced")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(
                            options.copy(
                                depth = depth
                            )
                        )
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
private fun SearchApiKeysEditor(
    apiKeysRaw: String,
    poolLabel: String,
    onUpdate: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var keyInputs by remember(apiKeysRaw) { mutableStateOf(parseSearchApiKeys(apiKeysRaw)) }

    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = poolLabel,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${keyInputs.size}/$MAX_SEARCH_API_KEYS")
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) HugeIcons.Cancel01 else HugeIcons.QuillWrite01,
                        contentDescription = null
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    keyInputs.forEachIndexed { index, value ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = value,
                                onValueChange = { text ->
                                    val updated = keyInputs.toMutableList()
                                    updated[index] = text
                                    keyInputs = updated
                                    onUpdate(joinSearchApiKeys(updated))
                                },
                                label = { Text("Key ${index + 1}") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    val updated = keyInputs.toMutableList().apply { removeAt(index) }
                                    keyInputs = updated
                                    onUpdate(joinSearchApiKeys(updated))
                                }
                            ) {
                                Icon(imageVector = HugeIcons.Delete01, contentDescription = "Remove key")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (keyInputs.size >= MAX_SEARCH_API_KEYS) return@Button
                            val updated = keyInputs + ""
                            keyInputs = updated
                            onUpdate(joinSearchApiKeys(updated))
                        },
                        enabled = keyInputs.size < MAX_SEARCH_API_KEYS
                    ) {
                        Icon(imageVector = HugeIcons.Add01, contentDescription = null)
                        Text("Add Key")
                    }
                }
            }
        }
    }
}

private fun parseSearchApiKeys(raw: String): List<String> {
    return raw
        .split(SEARCH_API_KEY_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_SEARCH_API_KEYS)
}

private fun joinSearchApiKeys(keys: List<String>): String {
    return keys
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_SEARCH_API_KEYS)
        .joinToString(separator = "\n")
}

@Composable
private fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Composable
fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CommonOptions(
    settings: Settings,
    onUpdate: (SearchCommonOptions) -> Unit
) {
    var commonOptions by remember(settings.searchCommonOptions) {
        mutableStateOf(settings.searchCommonOptions)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_common_options),
                style = MaterialTheme.typography.titleMedium
            )

            FormItem(
                label = {
                    Text(stringResource(R.string.setting_page_search_result_size))
                }
            ) {
                OutlinedNumberInput(
                    value = commonOptions.resultSize,
                    onValueChange = {
                        commonOptions = commonOptions.copy(
                            resultSize = it
                        )
                        onUpdate(commonOptions)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API URL")
        }
    ) {
        OutlinedTextField(
            value = options.url,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        url = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Engines")
        }
    ) {
        OutlinedTextField(
            value = options.engines,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        engines = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Language")
        }
    ) {
        OutlinedTextField(
            value = options.language,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        language = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Username")
        }
    ) {
        OutlinedTextField(
            value = options.username,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        username = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Password")
        }
    ) {
        OutlinedTextField(
            value = options.password,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        password = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit
) {
    // LinkUp rotates over a newline-delimited key pool just like Tavily, so exposing only a
    // single input here silently breaks the rotation feature at the configuration layer.
    SearchApiKeysEditor(
        apiKeysRaw = options.apiKey,
        poolLabel = "LinkUp Key Pool",
        onUpdate = { updated ->
            onUpdateOptions(options.copy(apiKey = updated))
        }
    )

    FormItem(
        label = {
            Text("Depth")
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(
                            options.copy(
                                depth = depth
                            )
                        )
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
private fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}


@Composable
private fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Max Tokens")
        }
    ) {
        OutlinedTextField(
            value = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(
                    options.copy(
                        maxTokens = value.toIntOrNull()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormItem(
        label = {
            Text("Max Tokens / Page")
        }
    ) {
        OutlinedTextField(
            value = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdateOptions(
                    options.copy(
                        maxTokensPerPage = value.toIntOrNull()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    FormItem(
        label = {
            Text("Base URL")
        }
    ) {
        OutlinedTextField(
            value = options.baseUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        baseUrl = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://api.firecrawl.dev")
            }
        )
    }

    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Search URL")
        }
    ) {
        OutlinedTextField(
            value = options.searchUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        searchUrl = it.trim()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            }
        )
    }

    FormItem(
        label = {
            Text("Scrape URL")
        }
    ) {
        OutlinedTextField(
            value = options.scrapeUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        scrapeUrl = it.trim()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            }
        )
    }
}

@Composable
private fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Summary")
        },
        description = {
            Text("Enable summary generation")
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(
                        options.copy(
                            summary = checked
                        )
                    )
                }
            )
        }
    )
}

@Composable
private fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: (SearchServiceOptions.RikkaHubOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Depth")
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(
                            options.copy(
                                depth = depth
                            )
                        )
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
private fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit
) {
    FormItem(
        label = {
            Text("API Key")
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        apiKey = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Model")
        }
    ) {
        OutlinedTextField(
            value = options.model,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        model = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("Custom URL")
        }
    ) {
        OutlinedTextField(
            value = options.customUrl,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        customUrl = it
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text("System Prompt")
        }
    ) {
        OutlinedTextField(
            value = options.systemPrompt,
            onValueChange = {
                onUpdateOptions(
                    options.copy(
                        systemPrompt = it
                    )
                )
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
