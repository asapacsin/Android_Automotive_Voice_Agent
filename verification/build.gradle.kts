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
    api(project(":vehicle"))
    testImplementation(libs.junit.jupiter)
}
