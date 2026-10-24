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
        // 版本标识：每次 CI 构建自带 run number + commit，装完能分清是哪一版
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.2." + (System.getenv("BUILD_NUMBER") ?: "0")
        buildConfigField("String", "BUILD_TAG", "\"${System.getenv("BUILD_TAG") ?: "local"}\"")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { buildConfig = true }

    // 固定签名：CI 每次跑的 debug.keystore 都是新的，装出来签名不同，
    // Android 就拒绝覆盖安装。用固定密钥 aj.jks 签（CI 里从 Secret 解出来，不进仓库），以后都能直接升级。
    val ks = file("aj.jks")
    if (ks.exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = ks
                storePassword = "ajmini123"
                keyAlias = "aj"
                keyPassword = "ajmini123"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (ks.exists()) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (ks.exists()) signingConfig = signingConfigs.getByName("fixed")
        }
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
