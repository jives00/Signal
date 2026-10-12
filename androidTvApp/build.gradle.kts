import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// One version for the app, companion and web admin (PLAN §5.10), from gradle.properties.
val versionNameProp = providers.gradleProperty("signal.versionName").get()
val (major, minor, patch) = versionNameProp.split(".").map(String::toInt)

// The release's apk-* tag, baked in so the auto-updater can compare it with the newest
// GitHub Release (PLAN §4.3.15). Local and debug builds are "dev" and never offer updates.
val buildTag = providers.gradleProperty("signal.buildTag").orNull ?: "dev"

// Release signing comes from env vars in CI, or local.properties on the laptop. Neither the
// keystore nor its passwords are ever committed (PLAN §1).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String): String? =
    providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("SIGNAL_KEYSTORE_FILE")?.let(rootProject::file)
val releaseStorePassword = secret("SIGNAL_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("SIGNAL_KEY_ALIAS")
val releaseKeyPassword = secret("SIGNAL_KEY_PASSWORD")
val canSignRelease = releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.signal.tv"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Permanent: changing it after the first install breaks updates and loses data (PLAN §1).
        applicationId = "com.signal.tv"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = major * 10000 + minor * 100 + patch
        versionName = versionNameProp
        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
        buildConfigField("Boolean", "DEV_TOOLS", "false")
    }

    signingConfigs {
        create("release") {
            if (canSignRelease) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // A dev build never replaces the installed daily-use app or its data (PLAN §7.3).
            applicationIdSuffix = ".debug"
            buildConfigField("Boolean", "DEV_TOOLS", "true")
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
        // Release-speed build for measuring performance on the Shield (PLAN §10): optimized and not
        // debuggable, like release, but debug-signed, installed beside the others, and with the
        // developer tools (Demo source, guide spike) visible.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profile"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("Boolean", "DEV_TOOLS", "true")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // Media3's FFmpeg extension, built LGPL-only by the "FFmpeg (LGPL)" workflow (PLAN §5.3) and
    // committed here. Without it, AC3/E-AC3/DTS still pass through to a receiver.
    val ffmpeg = file("libs/media3-decoder-ffmpeg-${libs.versions.androidx.media3.get()}.aar")
    if (ffmpeg.exists()) {
        implementation(files(ffmpeg))
        implementation(libs.androidx.media3.decoder)
    }
    implementation(libs.sqldelight.android.driver)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
