package me.rerere.rikkahub.ui.components.debug

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.debug.DebugLogger
import me.rerere.rikkahub.debug.model.DebugLogEntry

/**
 * 日志查看器对话框
 *
 * @param onDismiss 关闭回调
 */
@Composable
fun LogViewerDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val debugLogger = remember { DebugLogger.getInstance(context) }
    var logs by remember { mutableStateOf<List<DebugLogEntry>>(emptyList()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) {
            debugLogger.getRecentLogs(200)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(600.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "调试日志（最近 200 条）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(8.dp)
                ) {
                    items(logs.reversed()) { log ->
                        LogEntryItem(log)
                    }
                }
            }
        }
    }
}

/**
 * 日志条目项
 */
@Composable
private fun LogEntryItem(entry: DebugLogEntry) {
    val levelColor = when (entry.level) {
        me.rerere.rikkahub.debug.model.LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
        me.rerere.rikkahub.debug.model.LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        me.rerere.rikkahub.debug.model.LogLevel.INFO -> MaterialTheme.colorScheme.primary
        me.rerere.rikkahub.debug.model.LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        me.rerere.rikkahub.debug.model.LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    Text(
        text = entry.toString(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = levelColor,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
