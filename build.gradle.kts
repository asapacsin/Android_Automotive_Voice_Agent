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
    val asciiName = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    layout.buildDirectory.set(File("C:/Users/Administrator/tools/nova-drive-build/$asciiName"))
}
