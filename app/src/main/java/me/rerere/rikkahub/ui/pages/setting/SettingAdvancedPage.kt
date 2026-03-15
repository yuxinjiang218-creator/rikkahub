package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingAdvancedPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("\u9AD8\u7EA7") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("AI Code") },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingWorkflowControl) },
                        leadingContent = { Icon(Lucide.Sparkles, null) },
                        headlineContent = { Text("\u5DE5\u4F5C\u6D41\u63A7\u5236") },
                        supportingContent = { Text("\u63A7\u5236\u804A\u5929\u9875\u5DE5\u4F5C\u6D41\u5165\u53E3\u4E0E\u4FA7\u8FB9\u680F\u663E\u793A") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingScheduledTasks) },
                        leadingContent = { Icon(Lucide.Clock, null) },
                        headlineContent = { Text("\u5B9A\u65F6\u4EFB\u52A1") },
                        supportingContent = { Text("\u6309\u8BA1\u5212\u89E6\u53D1\u52A9\u624B\u6267\u884C\u4EFB\u52A1\u5E76\u67E5\u770B\u8FD0\u884C\u8BB0\u5F55") },
                    )
                }
            }
        }
    }
}
