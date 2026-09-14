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
