plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.novadrive.demo.DemoMainKt")
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
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
