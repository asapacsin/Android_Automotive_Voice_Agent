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

    // These tests read the repository's own source files at runtime, so Gradle cannot see what
    // they depend on and will call the task up to date after any change it does not recognise.
    // On 2026-09-19 that hid a real failure: AndroidToolDispatcher.kt grew past its line budget
    // and three consecutive green runs never re-ran the rule that says so. A structural guard
    // that only runs when Gradle happens to notice is not a guard.
    outputs.upToDateWhen { false }
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":ingress"))
    implementation(project(":safety"))
    implementation(project(":vehicle"))
    implementation(project(":verification"))
    implementation(project(":feedback"))
    implementation(project(":orchestration"))
    implementation(project(":simulator"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
