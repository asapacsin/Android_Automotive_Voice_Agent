import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}
val novaBackendUrl = (localProperties.getProperty("NOVA_BACKEND_URL") ?: "http://10.0.2.2:8000").trim()

android {
    namespace = "com.novadrive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novadrive.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.1-qwen-realtime"
        resValue("string", "nova_backend_url", novaBackendUrl)
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
