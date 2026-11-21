plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yatralens"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.yatralens"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes {
        debug {
            // A disposable LAN-only debug token, read from ignored .env. Never the Sarvam secret.
            val envFile = rootProject.projectDir.parentFile.resolve(".env")
            val token = envFile.takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("YATRALENS_DEV_TOKEN=") }
                ?.substringAfter("=") ?: ""
            buildConfigField("String", "DEV_TOKEN", "\"$token\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEV_TOKEN", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}

