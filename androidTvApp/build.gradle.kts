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

val keystoreFile = secret("SIGNAL_KEYSTORE_FILE")?.let(rootProject::file)
val keystorePassword = secret("SIGNAL_KEYSTORE_PASSWORD")
val keyAlias = secret("SIGNAL_KEY_ALIAS")
val keyPassword = secret("SIGNAL_KEY_PASSWORD")
val canSignRelease = keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null

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
    }

    signingConfigs {
        create("release") {
            if (canSignRelease) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // A dev build never replaces the installed daily-use app or its data (PLAN §7.3).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
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
}
