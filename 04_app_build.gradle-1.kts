plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.zenpx.zagent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.zenpx.zagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}
dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
