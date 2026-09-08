# AiChatApp

Android → AI 开发转型练习项目：用 Jetpack Compose 实现的**本地大模型聊天 App**，接入电脑上的 Ollama（`qwen2.5:7b`），把模型回复流式显示到对话列表。

> 这是「Android 转型 12 周计划」的 W1–W2 工程：W1 完成聊天 UI，W2 接入本地 Ollama 流式接口并迁移到 MVVM。已通过 `assembleDebug` 编译验证（产出 `app-debug.apk`）。
> 代码结构与接口对接细节见 **[CODE_EXPLANATION.md](./CODE_EXPLANATION.md)**。

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Android Gradle Plugin | 9.0.0（**内置 Kotlin**，默认启用，无需 `kotlin-android` 插件） |
| Gradle | 9.5.0（已缓存于 `~/.gradle/wrapper/dists`） |
| Kotlin | 2.2.10（由 AGP 9 内置 KGP 固定） |
| Compose Compiler | 2.2.10（通过 `org.jetbrains.kotlin.plugin.compose` 提供） |
| Compose BOM | 2025.10.00（Compose 1.8.x） |
| UI | Material 3 + material-icons（发送按钮用 `Icons.AutoMirrored.Filled.Send`） |
| 架构 | MVVM：`ChatViewModel`（StateFlow）+ `OllamaRepository`（HTTP 流式） |
| 网络 | 基于 `java.net.HttpURLConnection` 的流式 `/api/chat` 调用（零额外网络库） |

## 已实现

- **W1 基础 UI**：消息列表（`LazyColumn` + 左右气泡）、输入框 + 发送、自动滚底
- **W2 Ollama 接入**：
  - `ViewModel + StateFlow` 持有消息状态（替代 W1 的 `remember`）
  - `OllamaRepository.streamChat()` 调 `POST /api/chat`（`stream:true`），逐行解析 NDJSON，返回 `Flow<String>`
  - 发送后先把 AI 消息占空位，再**逐字流式填充**（打字机效果），首 token 前显示「思考中…」
  - 异常兜底为带排查提示的错误消息，不崩溃

## 如何运行

### 1. 电脑端准备 Ollama
```bash
ollama serve                 # 若未常驻
ollama pull qwen2.5:7b       # 本机已拉可跳过（注意写全名字，裸名 qwen2.5 会 not found）
```

### 2. 安卓端运行
1. 用 **Android Studio** 打开本项目目录
2. 首次 **Sync Project**（依赖从阿里云镜像下载，国内直连、无需代理）
3. 选**模拟器**（API 30+）点 ▶ Run
4. 输入消息，应看到 `qwen2.5:7b` **逐字回复**

> 默认 `baseUrl = http://10.0.2.2:11434`（模拟器访问宿主机）。真机用电脑局域网 IP（见 CODE_EXPLANATION.md §4）。
> 编译命令：`export JAVA_HOME=/.../jbr-21 && ./gradlew assembleDebug`

## 下一步（W3–W4 方向）
- `baseUrl` / `model` 抽到设置项；上下文裁剪（超长历史截断）
- 接入云端 LLM API 做「端侧 vs 云侧」对照
- Function Calling、本地 RAG

## 注意事项
- `compileSdk = 37`：本机 SDK 仅装有 API 31 / 34 / 37（无 35），故用 37；换机器请按需调整
- `local.properties` 含 `sdk.dir`，已本地化，**不要提交到 git**
- AGP 9.0 对 `compileSdk 37` 仅 warning（tested up to 36.1），不影响编译
- Manifest 已开 `INTERNET` 权限与 `usesCleartextTraffic`（Ollama 为 http 明文，必须）

