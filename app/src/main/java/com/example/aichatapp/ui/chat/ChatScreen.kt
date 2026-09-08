package com.example.aichatapp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 聊天主屏：消息列表（LazyColumn）+ 底部输入框 + 发送按钮。
 *
 * W2 改造点（对比 W1）：
 * - 状态从 remember + mutableStateListOf 迁移到 [ChatViewModel] 的 StateFlow；
 *   UI 用 collectAsState 订阅，发消息调用 viewModel.send()，回声占位已删除。
 * - 接入真实 Ollama 后，AI 消息为流式逐字填充；发送后到首个 token 到达前显示「思考中…」。
 * - W2 收尾：生成中显示「停止」按钮（调 viewModel.stopGeneration()）；
 *   顶部「清空」按钮重置对话；Ollama 地址可在 App 内直接编辑，免重编译切换模拟器/真机。
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    var input by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val listState = rememberLazyListState()

    // 新消息到达时自动滚动到列表底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：Ollama 地址输入 + 清空对话按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { viewModel.setBaseUrl(it) },
                modifier = Modifier.weight(1f),
                label = { Text("Ollama 地址") },
                placeholder = { Text("http://10.0.2.2:11434 或 真机用局域网 IP") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { viewModel.clearChat() }) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "清空对话"
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = messages, key = { it.id }) { msg ->
                MessageBubble(message = msg)
            }
        }

        if (isThinking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "思考中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank()) {
                            viewModel.send(input)
                            input = ""
                        }
                    }
                )
            )
            Spacer(modifier = Modifier.width(8.dp))

            // 生成中 → 显示「停止」按钮；否则 → 显示「发送」按钮
            if (isGenerating) {
                IconButton(onClick = { viewModel.stopGeneration() }) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止生成",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.send(input)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送"
                    )
                }
            }
        }
    }
}
