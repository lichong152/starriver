import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.xingchuan.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.xingchuan.tv"
        // Android 5.0 —— 国产电视存量机型覆盖面最大的一档。
        minSdk = 21
        targetSdk = 34
        versionCode = 14
        versionName = "1.2.0"
    }

    // standard: 正常声明 HOME，作为系统桌面。
    // guard: 完全不声明 HOME，供 TCL/海信等校验 HOME 声明的固件使用（以普通应用安装后前台常驻）。
    flavorDimensions += "home"
    productFlavors {
        create("standard") { dimension = "home" }
        create("guard") {
            dimension = "home"
            versionNameSuffix = "-guard"
        }
    }

    // 可选：提供 local/signing.properties 时用稳定签名，便于覆盖升级。
    val signingProps = rootProject.file("local/signing.properties")
    signingConfigs {
        if (signingProps.exists()) {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            create("stable") {
                storeFile = rootProject.file("local/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源收缩：不开启时 classes.dex 达 16 MB（Compose 依赖全量打进包），
            // 开启后缩到 ~4 MB。工程无反射、无序列化库，默认规则即可。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
    lint {
        // 侧载桌面，不上架 Play。
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.11.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    // 提供 lifecycle.compose.LocalLifecycleOwner，供视频壁纸在回前台时恢复播放
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    testImplementation("junit:junit:4.13.2")
}
