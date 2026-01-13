package me.rerere.rikkahub.ui.components.debug

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 崩溃报告弹窗
 *
 * @param crashFile 崩溃报告文件
 * @param onDismiss 关闭回调
 */
@Composable
fun CrashDialog(
    crashFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var crashContent by remember { mutableStateOf("") }

    LaunchedEffect(crashFile) {
        crashContent = withContext(Dispatchers.IO) {
            crashFile.readText()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "⚠️ 应用上次崩溃",
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = "检测到崩溃日志，您可以复制或分享报告。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            context.copyToClipboard(crashContent)
                            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("复制")
                    }

                    OutlinedButton(
                        onClick = {
                            context.shareText(crashContent)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("分享")
                    }
                }

                TextButton(
                    onClick = {
                        crashFile.delete()
                        Toast.makeText(context, "日志已删除", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除日志")
                }
            }
        }
    }
}

/**
 * 复制文本到剪贴板
 */
private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Crash Report", text)
    clipboard.setPrimaryClip(clip)
}

/**
 * 分享文本
 */
private fun Context.shareText(text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    startActivity(android.content.Intent.createChooser(intent, "分享崩溃报告"))
}
