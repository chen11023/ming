plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.aj.miniapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.aj.miniapp"
        minSdk = 26
        // 必须是 28：Android 10+ 对 targetSdk>=29 的应用禁止在数据目录 execve，
        // proot 起 rootfs 里的 /bin/sh 会 EACCES 直接 255（Termux 自己就是 28）
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // 必须用 legacy 打包，否则 .so 不会被解压成可执行文件
        jniLibs { useLegacyPackaging = true }
    }
    androidResources { noCompress += listOf("tar.gz") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("commons-io:commons-io:2.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Termux 官方终端组件（JitPack 多模块坐标），带 PTY 的 libtermux.so
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
}
