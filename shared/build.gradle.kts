import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "com.signal.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true // Compose resources (the Archivo font) on Android
        withHostTest {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // The dev harness runs this target today; the NAS companion (milestone 3) reuses it.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Both targets are JVMs, so code that needs java.* (Unicode normalization, file I/O)
        // lives once in jvmCommonMain instead of being duplicated per target.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmCommonMain)
        jvmMain.get().dependsOn(jvmCommonMain)

        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(jvmCommonTest)
        jvmTest.get().languageSettings.optIn("androidx.compose.ui.test.ExperimentalTestApi")

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            // The per-screen key-map UI tests (PLAN §12.3) run the real app on the desktop.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.resources {
    packageOfResClass = "com.signal.resources"
    publicResClass = false
}

sqldelight {
    databases {
        // user.db: the user's data plus cached source data. Never replaced, only migrated (PLAN §5.1).
        create("UserDatabase") {
            packageName.set("com.signal.db.user")
            srcDirs("src/commonMain/sqldelight/user")
        }
        // guide.db: the guide, built whole and swapped in; never migrated, only rebuilt (PLAN §5.1, §6).
        create("GuideDatabase") {
            packageName.set("com.signal.db.guide")
            srcDirs("src/commonMain/sqldelight/guide")
        }
    }
}
