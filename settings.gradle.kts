rootProject.name = "nova-drive"

pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

include(
    ":contracts",
    ":ingress",
    ":safety",
    ":vehicle",
    ":verification",
    ":feedback",
    ":orchestration",
    ":simulator",
    ":evaluation",
    ":behavior-test",
    ":demo",
)

val androidSdkDir: File? = localAndroidSdk()
val androidPlatform = androidSdkDir?.resolve("platforms/android-34")
val includeAndroidApp = androidPlatform?.isDirectory == true
if (includeAndroidApp) {
    include(":app")
}
println("Nova Drive Android app module included: $includeAndroidApp")

fun localAndroidSdk(): File? {
    val localProperties = file("local.properties")
    if (localProperties.isFile) {
        localProperties.readLines()
            .map { it.trim() }
            .filter { it.startsWith("sdk.dir=") }
            .map { it.removePrefix("sdk.dir=").replace("\\\\", "\\") }
            .firstOrNull()
            ?.let { return File(it) }
    }
    System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
    System.getenv("ANDROID_HOME")?.let { return File(it) }
    val fallback = File("C:\\Users\\Administrator\\Android\\Sdk")
    return fallback.takeIf { it.isDirectory }
}
