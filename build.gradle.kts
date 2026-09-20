plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "com.novadrive"
    version = "0.1.0-checkpoint1"
    // Windows JVM @argfiles are read with the OEM code page (GBK here). A UTF-8
    // classpath that contains 桌面 makes Gradle Test Executor ClassNotFoundException.
    // Keep compiled outputs on an ASCII path so workers can load classes.
    // Cloud/Linux agents use NOVA_BUILD_DIR or ~/nova-drive-build; Windows keeps D11.
    val asciiName = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    val buildRoot = System.getenv("NOVA_BUILD_DIR")?.takeIf { it.isNotBlank() }
        ?: if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            "C:/Users/Administrator/tools/nova-drive-build"
        } else {
            File(System.getProperty("user.home"), "nova-drive-build").absolutePath
        }
    layout.buildDirectory.set(File("$buildRoot/$asciiName"))
}
