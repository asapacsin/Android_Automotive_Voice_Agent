plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":contracts"))
    api(project(":ingress"))
    implementation(project(":safety"))
    implementation(project(":vehicle"))
    implementation(project(":verification"))
    implementation(project(":feedback"))
    testImplementation(libs.junit.jupiter)
}
