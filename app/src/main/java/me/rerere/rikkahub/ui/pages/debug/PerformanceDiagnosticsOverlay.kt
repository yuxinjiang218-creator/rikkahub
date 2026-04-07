package me.rerere.rikkahub.ui.pages.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject

@Composable
fun PerformanceDiagnosticsOverlay(
    onOpenDebugPage: () -> Unit,
) {
    val controller: PerformanceDiagnosticsController = koinInject()
    val state by controller.uiState.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val report = when (state.activeMode) {
        DetectionMode.Snapshot -> state.lastSnapshotReport
        DetectionMode.Deep -> state.lastDeepReport
    } ?: state.currentReport

    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 10.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.sizeIn(maxWidth = 340.dp),
    ) {
        if (!state.overlayExpanded) {
            Text(
                text = if (state.isCapturing) "诊断中..." else "诊断",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { controller.setOverlayExpanded(true) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("诊断面板", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "页面: ${state.route.screenLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "收起",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { controller.setOverlayExpanded(false) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { controller.runDetection(DetectionMode.Snapshot) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("快照检测")
                    }
                    Button(
                        onClick = { controller.runDetection(DetectionMode.Deep) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("深度检测")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            report?.text?.let {
                                toaster.show("诊断结果已复制")
                                context.writeClipboardText(it)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("复制结果")
                    }
                    FilledTonalButton(
                        onClick = onOpenDebugPage,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("调试页")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "当前模式: ${if (state.activeMode == DetectionMode.Snapshot) "快照" else "深度"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "隐藏",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { controller.hideOverlay() },
                    )
                }

                HorizontalDivider()

                if (state.isCapturing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text("正在采样，请稍等...")
                    }
                }

                state.lastError?.let { error ->
                    Text(
                        text = "错误: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                report?.let {
                    Text(
                        text = "${it.title} ${it.capturedAtLabel}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 120.dp, maxHeight = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                } ?: Text(
                    text = "点上面的按钮开始检测。未检测时不会持续采样。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
