# AiChatApp 代码解释文档（W2 完整版：接入本地 Ollama + 停止生成 + 上下文裁剪）

> 适用版本：W2 完整版（W1「消息列表 + 输入框」→ W2 接入 Ollama 流式 → W2 收尾：停止生成 / 清空对话 / 上下文裁剪 / 可编辑地址）。
> 阅读对象：想理解「Android 如何调用本地大模型」的 Android 开发者 / 转型学习者。

---

## 1. 整体架构（MVVM）

```
┌─────────────────────────────────────────────────────────────┐
│  UI 层  ChatScreen.kt (Composable)                            │
│   - 订阅 ViewModel 的 StateFlow（collectAsState）            │
│   - 渲染 LazyColumn 消息列表 + 输入框 + 发送按钮              │
│   - 用户输入 → 调用 viewModel.send(text)                      │
└───────────────┬─────────────────────────────────────────────┘
                │ 发送 / 状态订阅
┌───────────────▼─────────────────────────────────────────────┐
│  状态层  ChatViewModel.kt (ViewModel)                         │
│   - messages: StateFlow<List<Message>>  （对话列表）         │
│   - isThinking: StateFlow<Boolean>      （等待首个 token）    │
│   - isGenerating: StateFlow<Boolean>    （生成中，显示停止键）│
│   - send()：追加用户消息 + 空 AI 占位 → 调 Repository 流式    │
│     收集 → 把每个 token 拼到 AI 占位上（打字机效果）          │
│   - stopGeneration()：cancel() Job，Flow collect 中断保留已生成│
│   - trimHistory()：保留最近 N 条，避免上下文窗口溢出          │
└───────────────┬─────────────────────────────────────────────┘
                │ streamChat(history)
┌───────────────▼─────────────────────────────────────────────┐
│  数据层  OllamaRepository.kt                                  │
│   - POST {baseUrl}/api/chat，body 含 model/messages/stream   │
│   - 基于 HttpURLConnection 逐行读 NDJSON，返回 Flow<String>   │
└───────────────┬─────────────────────────────────────────────┘
                │ HTTP (localhost:11434)
        ┌───────▼────────┐
        │  本地 Ollama   │  （电脑上运行：ollama serve + qwen2.5:7b）
        └────────────────┘
```

**为什么是 MVVM**：W1 用 `remember + mutableStateListOf`，状态生命周期绑在 Composable 上，屏幕旋转/重建会丢消息。W2 把状态搬到 `ViewModel` 的 `StateFlow`，生命周期独立于 UI，更稳、可测试，也是 Android 官方推荐范式——这本身就是面试加分点（"能讲清状态持有与生命周期"）。

---

## 2. 数据流（一次发送发生了什么）

1. 用户在 `ChatScreen` 输入框回车/点发送 → `viewModel.send(input)`，`input` 清空。
2. `ViewModel.send()` 立即把两条消息追加进 `_messages`：
   - 一条 `isFromUser=true` 的用户消息；
   - 一条**空的** AI 占位（`text=""`），记下它的 `id`。
3. 进入 `viewModelScope.launch` 协程：
   - 截取当前列表作为历史，`role` 映射为 `user` / `assistant`，并剔除刚加的空占位（避免把空消息喂回模型）；
   - 调用 `repository.streamChat(history)`，对每个 `emit` 出来的 token：`_messages` 中 `id` 匹配的那条 AI 消息 `copy(text = text + piece)`，实现逐字追加。
4. 首个 token 到达前，`isThinking=true` → UI 显示「思考中…」转圈；收到 token 或异常后置回 `false`。
5. 异常（连不上/模型不存在）时，把 AI 占位消息替换为一段带排查提示的错误文本，而不是崩溃。

---

## 3. Ollama 接口对接要点（重点）

### 3.1 请求
`POST {baseUrl}/api/chat`，`Content-Type: application/json`：
```json
{
  "model": "qwen2.5:7b",
  "stream": true,
  "messages": [
    { "role": "user",      "content": "你好" },
    { "role": "assistant", "content": "你好，有什么可以帮你？" }
  ]
}
```
- `model` 必须是宿主机已 `ollama pull` 的**完整名字**（本机是 `qwen2.5:7b`，裸名 `qwen2.5` 会报 `model not found`）。
- `stream: true` 才会逐片返回；若 `false` 则一次性返回整段（适合简单 demo，但无打字机效果）。

### 3.2 响应（NDJSON，每行一个 JSON 对象）
```
{"model":"qwen2.5:7b","message":{"role":"assistant","content":"1"},"done":false}
{"model":"qwen2.5:7b","message":{"role":"assistant","content":"+1=2"},"done":false}
{"model":"qwen2.5:7b","message":{"role":"assistant","content":"。"},"done":false}
{"model":"qwen2.5:7b","done":true,"total_duration":...}
```
- 这是**换行分隔的 JSON（NDJSON）**，不是单个 JSON 数组，不能用普通 JSON 解析器一次性吃。
- 每片取 `message.content` 累加；遇到 `done:true` 即结束。
- 已用 `curl` 实测验证契约（7 行、`message.content` 存在、末片 `done=true`、拼接为「1+1=2。」），与 `OllamaRepository` 解析逻辑一致。

### 3.3 为什么用 `HttpURLConnection` 而非 OkHttp/Ktor
- **零额外依赖**：避免再引网络库、序列化库，降低构建复杂度（本机已有 AGP 9 + Kotlin 2.2 新组合，少踩坑）。
- **流式读取直接**：`conn.inputStream` 本身就是持续流，用 `BufferedReader.readLine()` 循环即可按行取 NDJSON。
- 生产环境建议升级为 **Ktor + kotlinx.serialization**（类型安全、取消/超时更完善），但学习阶段理解「HTTP 流 + Flow」的桥接更重要。

### 3.4 流式 → Flow 的桥接（关键代码思路）
`streamChat()` 返回 `flow { ... }`，在流体内用 `while (line = reader.readLine())` 循环读每一行，解析后 `emit(piece)`。UI 端 `collect { piece -> 追加 }` 即把每个 token 实时渲染。
> 注意：不能用 `BufferedReader.forEachLine { emit(...) }`——它的 lambda 不是挂起上下文，不能调用 `emit`（挂起函数）。这是本 W2 编译时踩的一个真实坑。

### 3.5 网络必须在 IO 调度器（踩坑，已修正为 flowOn）
`flow { }` 体默认在**收集者上下文**执行；`ChatViewModel.send()` 用 `viewModelScope.launch`（默认 `Dispatchers.Main`）。若 `streamChat` 体内直接做网络 I/O → **`NetworkOnMainThreadException`**。

**错误尝试**：在 `flow { }` 体内用 `withContext(Dispatchers.IO) { ... emit(...) }` 切上下文 → 抛 `Flow invariant is violated: Flow was collected in Dispatchers.Main, but emission happened in Dispatchers.IO`。Flow 要求**发射与收集在同一上下文**，跨上下文发射违反不变量。

**正确做法**：`flow { ... }.flowOn(Dispatchers.IO)`。`flowOn` 会**整个 flow 体（含所有 emit）**切到 IO 调度器执行，再通过 channel 把数据传回收集者上下文，不变量保持。这是 Kotlin Flow 处理「IO 流式 + UI 收集」的标准模式。

---

## 4. 模拟器 / 真机地址（极易踩坑）

| 运行环境 | baseUrl 应填 | 原因 |
|---|---|---|
| Android 模拟器 | `http://10.0.2.2:11434` | 模拟器把 `10.0.2.2` 映射到宿主机的 `localhost`；模拟器内 `localhost` 指的是**模拟器自己**，连不到电脑的 Ollama |
| 真实手机（同 Wi-Fi） | `http://<电脑局域网IP>:11434` | 填电脑在局域网的实际 IP（非 127.0.0.1）。**且 Ollama 必须以 `OLLAMA_HOST=0.0.0.0:11434` 启动**，否则只监听 127.0.0.1，真机 TCP 被拒（报错 `调用Ollama失败:null`） |
| 跑在电脑上的桌面/单元测试 | `http://localhost:11434` | 同进程网络命名空间 |

> W2 起可在 App 顶部「Ollama 地址」框直接编辑，免重编译切换模拟器/真机。
> 真机持久方案：`launchctl setenv OLLAMA_HOST 0.0.0.0:11434 && brew services restart ollama`（勿改 plist EnvironmentVariables，会被 brew 重写清空）。

---

## 5. 必须的两处 Manifest 配置

```xml
<uses-permission android:name="android.permission.INTERNET" />   <!-- 访问网络 -->
<application android:usesCleartextTraffic="true" ... >            <!-- Ollama 是 http 非 https，Android 9+ 默认禁明文，必须开 -->
```
漏掉 `INTERNET` 会直接 `SecurityException`；漏掉 `usesCleartextTraffic` 会报 `IOException: Cleartext HTTP traffic to ... not permitted`。

---

## 6. 关键文件清单

| 文件 | 职责 |
|---|---|
| `ui/chat/ChatScreen.kt` | UI：列表 + 输入框，订阅 ViewModel 状态，发消息 |
| `ui/chat/ChatViewModel.kt` | 状态层：messages/isThinking 的 StateFlow，send() 编排流式收集 |
| `ui/chat/Message.kt` | 消息数据模型（id/text/isFromUser/timestamp） |
| `ui/chat/MessageBubble.kt` | 单条气泡（用户右/AI 左，Material 3 配色） |
| `data/OllamaRepository.kt` | 数据层：Ollama `/api/chat` 流式 HTTP 客户端 + `OllamaMessage` |
| `MainActivity.kt` | 入口，挂载主题 + `ChatScreen()` |
| `app/build.gradle.kts` | 新增 `lifecycle-viewmodel-compose` + `kotlinx-coroutines-android` 依赖 |
| `AndroidManifest.xml` | `INTERNET` 权限 + `usesCleartextTraffic` |

---

## 7. 如何运行（端到端）

```bash
# 1) 电脑端确保 Ollama 在跑，且已拉取模型
ollama serve                 # 若未常驻
ollama pull qwen2.5:7b       # 本机已拉，可跳过

# 2) 用 Android Studio 打开本项目 → 选模拟器（API 30+）→ Run
#    点开 App，输入消息，应看到 qwen2.5:7b 逐字回复
```

常见失败与排查（已内置到错误提示）：
- 连不上 → 确认 `ollama serve` 在跑、模拟器用 `10.0.2.2`、Manifest 已开 `INTERNET`；
- `model not found` → 模型名写全（`qwen2.5:7b` 而非 `qwen2.5`）；
- 明文被拦 → Manifest 漏 `usesCleartextTraffic="true"`。

---

## 8. W2 收尾功能

### 8.1 停止生成
- `ChatViewModel` 持有 `generationJob: Job?`，`send()` 时赋值。
- `stopGeneration()` 调 `generationJob?.cancel()`，Flow collect 被取消 → 抛 `CancellationException`。
- `catch (CancellationException)` 分支：**不报错**，在已生成内容后追加「⏹ 已停止」标记，保留部分回复。
- UI：`isGenerating=true` 时发送按钮变为红色「停止」图标（`Icons.Filled.Stop`）。

### 8.2 上下文裁剪
- `trimHistory()` 保留最近 `maxHistory=20` 条消息，超出部分从最老的丢弃。
- 避免长对话把全部历史喂回模型导致 `context_length` 溢出 / OOM / 响应变慢。
- 这是端侧 AI 工程化的基本功——面试常考「长对话怎么处理」。

### 8.3 清空对话
- `clearChat()` 取消生成 + 重置消息列表为欢迎语 + 复位状态标记。

### 8.4 长按复制
- `MessageBubble` 用 `combinedClickable.onLongClick` 捕获长按手势。
- `LocalClipboardManager.setText()` 把消息全文写入系统剪贴板（跨 App 可粘贴）。
- 复制后在气泡内短暂显示「✓ 已复制」1.5 秒（`LaunchedEffect + delay`），然后恢复原文。

---

## 9. 下一步（W3–W4 方向）
- 把 `model` 抽到设置项（目前硬编码 `qwen2.5:7b`）；
- 接入**云端 LLM API**（OpenAI 兼容 / 国内大模型）做对照，理解「端侧 vs 云侧」取舍；
- 进阶：**Function Calling**（让模型输出 JSON 调用本地工具）、**本地 RAG**（向量检索 + 端侧 Embedding）。
