plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

// The desktop dev harness (PLAN §7.1): a dev tool only, never distributed. That's also why it
// may use vlcj (GPLv3). No other module may (PLAN §1).
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.ktor.client.cio)
    implementation(libs.vlcj)
}

// -Psignal.player=fake uses the fake player instead of VLC (PLAN §7.1).
val playerArg = "-Dsignal.player=${providers.gradleProperty("signal.player").getOrElse("vlc")}"

compose.desktop {
    application {
        mainClass = "com.signal.dev.MainKt"
        jvmArgs += playerArg
    }
}

// `hotRun` is the everyday loop: `./gradlew reload` swaps in edited code without a restart, and
// `hotRun --auto` reloads on every save. It takes the main class from above but not the JVM args.
tasks.withType<org.jetbrains.compose.reload.gradle.ComposeHotRun>().configureEach {
    jvmArgs(playerArg)
}
