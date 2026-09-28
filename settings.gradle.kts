rootProject.name = "nova-drive"

// Repository order depends on the host (measured 2026-09-28). The owner's Windows PC is in China
// and its antivirus intercepts HTTPS: repo.maven.apache.org fails PKIX there and Gradle does not
// fall through on an SSL error, so the Aliyun mirrors go first. Cloud (Linux) agents reach the
// official repositories and may not reach Aliyun, so those go first there.
val mirrorsFirst = System.getProperty("os.name").startsWith("Windows")

pluginManagement {
    val mirrorsFirst = System.getProperty("os.name").startsWith("Windows")
    repositories {
        fun official() {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        fun mirrors() {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        if (mirrorsFirst) { mirrors(); official() } else { official(); mirrors() }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        fun official() {
            google()
            mavenCentral()
        }
        fun mirrors() {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        if (mirrorsFirst) { mirrors(); official() } else { official(); mirrors() }
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
