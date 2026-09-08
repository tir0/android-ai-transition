plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.aichatapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.aichatapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // AGP 9 内置 Kotlin 会据此自动设置 JVM target
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM 锁定组件版本，避免各库版本漂移（2025.10 含 Compose 1.8.x，对应 Kotlin 2.2.x）
    val composeBom = "androidx.compose:compose-bom:2025.10.00"
    implementation(platform(composeBom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // 图标（发送按钮用 Icons.AutoMirrored.Filled.Send）
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")

    // MVVM：viewModel() 组合函数 + StateFlow 状态持有
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    // 协程：Flow 流式收集、viewModelScope 协程作用域
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
