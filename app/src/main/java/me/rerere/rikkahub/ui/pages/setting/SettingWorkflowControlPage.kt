package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingWorkflowControlPage(vm: SettingVM = koinViewModel()) {
    val settings = vm.settings.collectAsStateWithLifecycle().value
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("\u5DE5\u4F5C\u6D41\u63A7\u5236") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ListItem(
                        headlineContent = { Text("\u542F\u7528\u5DE5\u4F5C\u6D41\u63A7\u5236") },
                        supportingContent = {
                            Text("\u5F00\u542F\u540E\u804A\u5929\u9875\u4F1A\u663E\u793A\u5DE5\u4F5C\u6D41\u5F00\u5173\uFF0C\u5E76\u5728\u5DE5\u4F5C\u6D41\u6FC0\u6D3B\u65F6\u663E\u793A\u4FA7\u8FB9\u680F\u5165\u53E3\u3002")
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.enableWorkflowControl,
                                onCheckedChange = { enabled ->
                                    vm.updateSettings(settings.copy(enableWorkflowControl = enabled))
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}
