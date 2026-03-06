package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    initialAutoCompressEnabled: Boolean,
    initialAutoCompressTriggerTokens: Int,
    onConfirm: (
        additionalPrompt: String,
        keepRecentMessages: Int,
        autoCompressEnabled: Boolean,
        autoCompressTriggerTokens: Int
    ) -> Job
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var keepRecentMessagesInput by remember { mutableStateOf("6") }
    var autoCompressEnabled by remember { mutableStateOf(initialAutoCompressEnabled) }
    var autoCompressTriggerTokensInput by remember { mutableStateOf(initialAutoCompressTriggerTokens.toString()) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) onDismiss()
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.chat_page_compress_context_desc),
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = keepRecentMessagesInput,
                        onValueChange = { keepRecentMessagesInput = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.chat_page_compress_keep_recent)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(
                            checked = autoCompressEnabled,
                            onCheckedChange = { autoCompressEnabled = it }
                        )
                        Text(
                            text = stringResource(R.string.chat_page_auto_compress),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    OutlinedTextField(
                        value = autoCompressTriggerTokensInput,
                        onValueChange = { autoCompressTriggerTokensInput = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.chat_page_auto_compress_threshold)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    onClick = {
                        val keepRecentMessages = keepRecentMessagesInput.toIntOrNull()?.coerceAtLeast(0) ?: 6
                        val autoThreshold = autoCompressTriggerTokensInput.toIntOrNull()?.coerceAtLeast(1000) ?: 12000
                        currentJob = onConfirm(
                            additionalPrompt,
                            keepRecentMessages,
                            autoCompressEnabled,
                            autoThreshold
                        )
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
