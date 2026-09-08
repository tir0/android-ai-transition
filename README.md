# AiChatApp

Android → AI 开发转型 **第 1 周** 练习项目：用 Jetpack Compose 实现的本地聊天 UI（消息列表 + 输入框）。

> 这是「Android 转型 12 周计划」的 W1 起步工程，已通过 `assembleDebug` 编译验证（产出 `app-debug.apk`）。

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| Android Gradle Plugin | 9.0.0（**内置 Kotlin**，默认启用，无需 `kotlin-android` 插件） |
| Gradle | 9.5.0（已缓存于 `~/.gradle/wrapper/dists`） |
| Kotlin | 2.2.10（由 AGP 9 内置 KGP 固定） |
| Compose Compiler | 2.2.10（通过 `org.jetbrains.kotlin.plugin.compose` 提供） |
| Compose BOM | 2025.10.00（Compose 1.8.x） |
| UI | Material 3 + material-icons（发送按钮用 `Icons.AutoMirrored.Filled.Send`） |

## 已实现（W1 范围）

- **消息列表**：`LazyColumn` + 左右气泡（`MessageBubble`），用户消息靠右、AI 消息靠左
- **输入框 + 发送**：`OutlinedTextField` + `IconButton`，回车或点按钮发送
- **本地状态**：`remember + mutableStateListOf<Message>`，发送即追加，新消息自动滚到底部（`animateScrollToItem`）
- **占位回声**：发送后追加一条「回声演示」AI 消息，仅用于演示 UI 反馈
- **工程结构**：`MainActivity` → `AiChatAppTheme`（Material 3）→ `ChatScreen`

## 如何运行

1. 用 **Android Studio** 打开 `AiChatApp` 目录
2. 首次会自动 **Sync Project**（依赖从阿里云镜像下载，国内直连、无需代理）
3. 选模拟器或真机，点 ▶ Run，即可看到聊天界面并发送消息

> 编译命令（命令行）：`export JAVA_HOME=/.../jbr-21 && ./gradlew assembleDebug`
> 依赖走 `settings.gradle.kts` 里的阿里云 Maven 镜像；`gradle.properties` 未设代理。

## 下一步（W2 计划）

- 接入 **Ollama 本地 `/api/chat`** 流式输出，替换回声占位
- 把状态迁移到 **ViewModel + StateFlow**（当前用 `remember` 仅演示）
- 加「停止生成」按钮 + 上下文裁剪

## 注意事项

- `compileSdk = 37`：本机 SDK 仅装有 API 31 / 34 / 37（无 35），故用 37；换机器请按需调整
- `local.properties` 含 `sdk.dir`，已本地化，**不要提交到 git**
- AGP 9.0 对 `compileSdk 37` 仅 warning（tested up to 36.1），不影响编译
