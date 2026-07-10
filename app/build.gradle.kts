plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartview.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartview.app"
        minSdk = 29
        targetSdk = 35
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
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // Groq API (Whisper STT / PlayAI TTS / page-agent LLM proxy checks).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RayNeo X3 Pro SDKs (dual-projection / Mercury launcher integration).
    // Packaged at runtime (implementation, not compileOnly) since SmartView is
    // a standalone app and nothing else provides them.
    implementation(files("libs/MercuryAndroidSDK-v0.2.2-20250717110238_48b655b3.aar"))
    implementation(files("libs/RayNeoIPCSDK-For-Android-V0.1.0-20231128201840_9b41f025.aar"))
}
