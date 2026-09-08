// 顶层构建脚本：仅声明插件版本，由 :app 模块 apply
plugins {
    // AGP 9.0 内置 Kotlin 支持（默认启用），无需再 apply org.jetbrains.kotlin.android
    id("com.android.application") version "9.0.0" apply false
    // Compose 编译器插件（版本需与 AGP 9 内置的 KGP 2.2.10 对齐）
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
