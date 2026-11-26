plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing keys stay outside the repo and their passwords in the Mac keychain. Builds for our own TVs use the everyday
// key; public builds (-PpublicRelease) use the release key, whose keychain item asks before every read.
// -PlocalSigning explicitly reuses this checkout's established debug certificate for in-place TV updates.
// Signing certificate and runtime debuggability are independent: local-signed release stays non-debuggable.
val localSigning = hasProperty("localSigning")
require(!localSigning || !hasProperty("publicRelease")) { "localSigning and publicRelease are mutually exclusive" }
// Without the external key, a release build is unsigned unless localSigning is explicitly requested.
val signingName = if (hasProperty("publicRelease")) "hal-release" else "hal-everyday"
val signingKey = file("${System.getProperty("user.home")}/.hal/$signingName.jks")

android {
    namespace = "com.evofactory.hal"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.evofactory.hal"
        minSdk = 28 // Android 9
        targetSdk = 36
        versionCode = 17
        versionName = "1.2.0"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        if (!localSigning && signingKey.exists()) {
            val password = providers.exec {
                commandLine("security", "find-generic-password", "-a", "hal", "-s", signingName, "-w")
            }.standardOutput.asText.get().trim()
            create("release") {
                storeFile = signingKey
                storePassword = password
                keyAlias = "hal"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (localSigning) signingConfigs.getByName("debug") else signingConfigs.findByName("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            // Same established local debug key for an explicitly authorized in-place profiling build.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("GHSA-xxph-c9ww-hj94 fixes allocation during Java collection deserialization")
        }
    }
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
