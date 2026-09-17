plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("nova.repo.root", rootProject.projectDir.absolutePath)
    systemProperty("nova.updateUtterances", providers.gradleProperty("updateUtterances").getOrElse("false"))
}

// Evaluation infrastructure only: telemetry schema, scenarios, oracle, metrics, baselines, reports.
// No Android, no vendor SDKs, no network. Used by the app (telemetry + device runner) and by the
// JVM simulation benchmark.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
