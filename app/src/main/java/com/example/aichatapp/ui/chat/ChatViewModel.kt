package com.example.aichatapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichatapp.data.OllamaMessage
import com.example.aichatapp.data.OllamaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 聊天 ViewModel：以 MVVM 方式持有消息状态，连接 OllamaRepository。
 *
 * 设计要点：
 * - [messages] / [isThinking] 用 StateFlow 暴露，UI 用 collectAsState 订阅，
 *   与 W1 的 remember + mutableStateListOf 相比，状态生命周期脱离 Composable，
 *   重建/旋转屏幕不丢消息，也更利于单元测试。
 * - send() 先把用户消息和一条「空 AI 占位」追加进列表，再流式把 token 拼到该占位上，
 *   实现「先占位置、边生成边填充」的打字机效果。
 * - 调用前会截取当前列表作为对话历史喂给模型（role 映射 user/assistant），
 *   并剔除刚加的空占位，避免把空消息喂回模型。
 * - [stopGeneration]() 取消当前生成 Job，Flow 的 collect 被取消后 IO 读取中断，
 *   已生成的部分内容保留在列表中（用户可见「⏹ 已停止」标记）。
 * - 上下文裁剪：Ollama 把全部历史喂回模型会迅速吃满上下文窗口导致 OOM/截断，
 *   [trimHistory] 保留 system(如有) + 最近 MAX_HISTORY 条，超出部分丢弃。
 */
class ChatViewModel : ViewModel() {

    /** 保留最近多少条历史喂给模型（超此数量从最老的开始丢弃） */
    private val maxHistory = 20

    /**
     * Ollama 服务地址（可在 UI 内修改，免重编译）。
     * - 模拟器：http://10.0.2.2:11434（映射到宿主 localhost）
     * - 真机：电脑局域网 IP，如 http://192.168.x.x:11434
     *   注意：真机能访问的前提是 Ollama 用 `OLLAMA_HOST=0.0.0.0:11434` 启动，
     *   否则只监听 127.0.0.1，局域网设备会被拒绝（报错「调用Ollama失败:null」）。
     */
    private val _baseUrl = MutableStateFlow("http://10.87.3.163:11434")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    fun setBaseUrl(url: String) {
        val trimmed = url.trim().removeSuffix("/")
        if (trimmed.isBlank()) return
        _baseUrl.value = trimmed
        repository.baseUrl = trimmed
    }

    private val repository = OllamaRepository(baseUrl = _baseUrl.value)

    private val _messages = MutableStateFlow(
        listOf(
            Message(
                id = UUID.randomUUID().toString(),
                text = "你好，我已接入本地 Ollama（qwen2.5:7b）。在下方输入消息开始对话。",
                isFromUser = false
            )
        )
    )
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** 当前生成协程，用于 stopGeneration() 取消 */
    private var generationJob: Job? = null

    /** 是否正在流式生成（区别于 isThinking：首个 token 到达后仍为 true，用于显示停止按钮） */
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun send(text: String) {
        val content = text.trim()
        if (content.isBlank()) return
        // 若上一轮还在生成，先取消
        generationJob?.cancel()

        val userMsg = Message(id = UUID.randomUUID().toString(), text = content, isFromUser = true)
        val aiId = UUID.randomUUID().toString()
        val aiPlaceholder = Message(id = aiId, text = "", isFromUser = false)

        // 先占好两条位置：用户消息 + 空的 AI 回复
        _messages.update { it + userMsg + aiPlaceholder }
        _isThinking.value = true
        _isGenerating.value = true

        generationJob = viewModelScope.launch {
            try {
                // 截取历史（剔除刚加的空占位），role 映射为 Ollama 期望格式
                val history = trimHistory(
                    _messages.value
                        .filter { it.text.isNotBlank() }
                        .map { m ->
                            OllamaMessage(
                                role = if (m.isFromUser) "user" else "assistant",
                                content = m.text
                            )
                        }
                        .dropLastWhile { it.role == "assistant" && it.content.isEmpty() }
                )

                repository.streamChat(history).collect { piece ->
                    _isThinking.value = false
                    _messages.update { list ->
                        list.map { if (it.id == aiId) it.copy(text = it.text + piece) else it }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动停止：保留已生成内容，追加停止标记
                _messages.update { list ->
                    list.map {
                        if (it.id == aiId && it.text.isNotBlank()) it.copy(text = "${it.text}\n\n⏹ 已停止")
                        else it
                    }
                }
            } catch (e: Exception) {
                // 避免 bare "null"：优先用 message，缺失时回退到 异常类名/原因
                val detail = e.message
                    ?: e.cause?.message
                    ?: "${e.javaClass.simpleName}${e.cause?.let { " (${it.javaClass.simpleName})" } ?: ""}"
                val tip = "⚠️ 调用 Ollama 失败：$detail\n\n排查：1) 电脑已运行 `ollama serve`；" +
                        "2) 已拉取模型 `ollama pull qwen2.5:7b`；3) 确认下方地址正确——" +
                        "模拟器用 http://10.0.2.2:11434，真机用电脑局域网 IP（且 Ollama 须以 OLLAMA_HOST=0.0.0.0:11434 启动）；" +
                        "4) 真机与电脑在同一 WiFi；5) Manifest 已开 INTERNET 与 cleartext。"
                _messages.update { list ->
                    list.map { if (it.id == aiId) it.copy(text = tip) else it }
                }
            } finally {
                _isThinking.value = false
                _isGenerating.value = false
            }
        }
    }

    /**
     * 停止当前流式生成：cancel() 触发 Flow collect 抛 CancellationException，
     * catch 块追加「⏹ 已停止」标记，已生成内容保留。
     */
    fun stopGeneration() {
        generationJob?.cancel()
    }

    /**
     * 上下文裁剪：保留最近 [maxHistory] 条消息，超出部分从最老的开始丢弃。
     * 避免长对话把全部历史喂回模型导致上下文窗口溢出 / OOM / 响应变慢。
     */
    private fun trimHistory(history: List<OllamaMessage>): List<OllamaMessage> {
        return if (history.size <= maxHistory) history
        else history.takeLast(maxHistory)
    }

    /** 清空对话历史（保留欢迎语） */
    fun clearChat() {
        generationJob?.cancel()
        _messages.value = listOf(
            Message(
                id = UUID.randomUUID().toString(),
                text = "对话已清空。在下方输入消息开始新对话。",
                isFromUser = false
            )
        )
        _isThinking.value = false
        _isGenerating.value = false
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
    }
}
