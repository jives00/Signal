plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The desktop dev harness (PLAN §7.1): a dev tool only, never distributed. That's also why it
// may use vlcj (GPLv3) when the player arrives in milestone 1. No other module may.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.signal.dev.MainKt"
    }
}
