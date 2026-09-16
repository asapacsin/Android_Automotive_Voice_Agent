import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val amapApiKey: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.getProperty("AMAP_API_KEY")?.trim().orEmpty()
if (amapApiKey.isEmpty()) {
    logger.warn("AMAP_API_KEY missing from local.properties — the navigation view will not authorise")
}

android {
    namespace = "com.novadrive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novadrive.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 7
        versionName = "0.6.0-baidu-flex"
        // Frozen backend compatibility receives no packaged host in the direct build.
        resValue("string", "nova_backend_url", "")
        manifestPlaceholders["AMAP_API_KEY"] = amapApiKey
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
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
    implementation(files("libs/Msc.jar"))
    implementation(libs.amap.navi.sdk)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
