plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    application
}

// The NAS companion (PLAN §5.2): Ktor on the JVM, reusing shared/'s parsers, guide builder and
// matcher, so nothing is implemented twice. It ships as a Docker image, never in the APK.
kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.signal.server.MainKt"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    // The nightly guide build (PLAN §5.2): downloads EPG sources and writes guide.db with the same
    // SQLDelight schema as the app.
    implementation(libs.ktor.client.cio)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.hikari)
    implementation(libs.mariadb)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

// The version the app and the web admin show (signal.versionName in gradle.properties).
tasks.processResources {
    val version = providers.gradleProperty("signal.versionName").getOrElse("dev")
    inputs.property("version", version)
    filesMatching("signal-version.txt") { expand("version" to version) }
}
