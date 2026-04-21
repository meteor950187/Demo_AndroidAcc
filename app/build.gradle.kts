plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")   // ← 加這行
}

android {
    namespace = "com.example.alipayaccdemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.alipayaccdemo"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 可視需要加入 debuggable 設定
            isDebuggable = false
            // 讓你可以生成 debug APK
            packaging {
                jniLibs.keepDebugSymbols.add("**/*.so")
            }
        }

        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // ── 你原本的 ───────────────────────────────
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

// ── WebSocket + JSON + 協程 ───────────────────────────────

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.json:json:20231013")

    // OkHttp / Okio
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("com.squareup.okio:okio:3.16.0")

    // KotlinX：請同步升級
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")// ← 升級！
}