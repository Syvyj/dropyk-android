import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.syvyj.dropyk"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.syvyj.dropyk"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release key from `.env` or `-P`; without it the release build is debug-signed.
    val localEnv = Properties().apply { rootProject.file(".env").takeIf { it.isFile }?.inputStream()?.use(::load) }
    val env = { key: String -> (findProperty(key) as String?) ?: localEnv.getProperty(key).orEmpty() }
    val releaseKeystore = env("DROPYK_KEYSTORE").takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("DROPYK_KEYSTORE_PASSWORD")
                keyAlias = env("DROPYK_KEY_ALIAS")
                keyPassword = env("DROPYK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.1.0")
    // QR matrix only — no camera or scanner.
    implementation("com.google.zxing:core:3.5.3")
}
