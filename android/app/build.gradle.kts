import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cz.rybky"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.rybky"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        // Adresa backendu: api.url v local.properties (např. https://rybky.pravn.uk).
        // Bez ní lokální server přes USB: adb reverse tcp:8000 tcp:8000
        // Pro release jde přepsat: ./gradlew assembleRelease -PapiUrl=https://…
        val apiUrl = (findProperty("apiUrl") as String?) ?: localProperties.getProperty("api.url", "http://127.0.0.1:8000")
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        // Klíč z https://developer.mapy.com – do local.properties jako mapy.apikey=...
        buildConfigField("String", "MAPY_API_KEY", "\"${localProperties.getProperty("mapy.apikey", "")}\"")
    }

    // Podepisovací klíč mimo repozitář – cesta a hesla v local.properties (release.*).
    signingConfigs {
        create("release") {
            localProperties.getProperty("release.storeFile")?.let { storeFile = file(it) }
            storePassword = localProperties.getProperty("release.storePassword")
            keyAlias = localProperties.getProperty("release.keyAlias")
            keyPassword = localProperties.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release s R8 pod jiným ID, aby šel vyzkoušet vedle vývojové verze.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            isDebuggable = true // kvůli run-as při testování
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // MediaPipe čte model přímo z APK, nesmí být komprimovaný.
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")

    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
