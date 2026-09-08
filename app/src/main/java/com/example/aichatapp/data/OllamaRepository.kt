package com.example.aichatapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 本地 Ollama HTTP 客户端（Android 端）。
 *
 * 核心接口：POST {baseUrl}/api/chat
 * 请求体（JSON）：{ "model": "...", "messages": [...], "stream": true }
 * 响应：以 NDJSON（换行分隔的 JSON）逐条返回 token，最后一条 `"done": true`。
 *
 * 实现说明：
 * - 不引入额外网络库（OkHttp/Ktor），直接基于 java.net.HttpURLConnection，
 *   在 Dispatchers.IO 上做阻塞式流式读取，配合 Kotlin Flow 把每个 token 抛给 UI。
 * - 这是「工程化落地」视角：客户端只管把流式响应干净地渲染出来，
 *   模型训练/推理由 Ollama 服务端负责（端侧 AI 的边界即在此）。
 * - 生产环境建议换 Ktor/OkHttp + kotlinx.serialization，并补超时分级、重试、取消。
 *
 * 关键坑（已踩并修正）：
 * - `flow { }` 体默认在**收集者上下文**执行。若收集在 `viewModelScope`（Dispatchers.Main），
 *   体内直接做网络 I/O → `NetworkOnMainThreadException`。
 * - 不能在 `flow { }` 体内用 `withContext(Dispatchers.IO) { ... emit(...) }`
 *   来切上下文——Flow 要求**发射与收集在同一上下文**，跨上下文发射会抛
 *   `Flow invariant is violated: ... emission happened in Dispatchers.IO`。
 * - 正确做法：用 `flowOn(Dispatchers.IO)`，它会**整个 flow 体（含所有 emit）**
 *   切到 IO 调度器执行，再通过 channel 把数据传回收集者上下文，不变量保持。
 *   这是 Kotlin Flow 处理「IO 流式 + UI 收集」的标准模式。
 */
class OllamaRepository(
    /** 模拟器访问宿主机用 http://10.0.2.2:11434；真机用电脑局域网 IP，如 http://192.168.x.x:11434 */
    var baseUrl: String = "http://10.87.3.163:11434"
) {
    /**
     * 流式调用 /api/chat，每收到一个 content 片段就 emit 一次。
     *
     * @param history 对话历史（role 只能是 user / assistant / system）
     * @param model   Ollama 模型名，默认 qwen2.5:7b
     *
     * 线程模型：flow 体在 Dispatchers.IO 执行（网络 I/O），收集者（UI）在 Main。
     * flowOn 负责上下文切换，emit 在 IO 发射、collect 在 Main 接收，满足 Flow 不变量。
     */
    fun streamChat(history: List<OllamaMessage>, model: String = "qwen2.5:7b"): Flow<String> = flow {
        val conn = (URL("$baseUrl/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000 // 大模型生成可能较慢，给足时间
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put(
                "messages",
                JSONArray(
                    history.map { m ->
                        JSONObject().apply { put("role", m.role); put("content", m.content) }
                    }
                )
            )
        }.toString()

        conn.outputStream.use { os -> os.write(payload.toByteArray(StandardCharsets.UTF_8)) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText()
                ?: "HTTP $code"
            throw RuntimeException("Ollama 返回错误（$code）：$err")
        }

        // 逐行读取 NDJSON 流式响应。while(readLine()) 循环直接在 flow 体内，
        // 由 flowOn(Dispatchers.IO) 整体切到 IO 调度器执行。
        BufferedReader(conn.inputStream.reader(StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val obj = JSONObject(line)
                    if (obj.optBoolean("done", false)) break
                    val piece = obj.optJSONObject("message")?.optString("content") ?: ""
                    if (piece.isNotEmpty()) emit(piece)
                }
                line = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO) // 整个 flow 体在 IO 执行，emit 经 channel 传回收集者（Main）
}

/** Ollama /api/chat 所需的单条消息（字段与 JSON 一一对应） */
data class OllamaMessage(val role: String, val content: String)
