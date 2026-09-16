package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SecretScanTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    @Test
    fun androidSourcesDoNotContainBaiduSecrets() {
        val roots = listOf("app/src", "ingress/src", "contracts/src", "orchestration/src")
        val hits = mutableListOf<String>()
        roots.forEach { rel ->
            File(root, rel).walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "xml", "properties", "kts", "json") }
                .forEach { file ->
                    val text = file.readText()
                    if (text.contains("BAIDU_SECRET_KEY=") || text.contains("BAIDU_API_KEY=") ||
                        text.contains("DASHSCOPE_API_KEY=") || text.contains("OPENAI_API_KEY=")
                    ) {
                        hits += file.relativeTo(root).path
                    }
                    if (text.contains("wss://aip.baidubce.com") && text.contains("access_token=")) {
                        hits += file.relativeTo(root).path
                    }
                }
        }
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") }
    }

    @Test
    fun gitignoreCoversBackendEnv() {
        val gitignore = File(root, ".gitignore").readText()
        assertTrue(gitignore.contains("backend/.env"))
        val example = File(root, "backend/.env.example").readText()
        assertTrue(example.contains("YOUR_API_KEY"))
        assertTrue(example.contains("audio-mini-realtime-near"))
        assertTrue(example.contains("qwen-audio-3.0-realtime-flash"))
        assertFalse(example.contains("BAIDU_API_KEY=ak"))
    }

    @Test
    fun gitignoreCoversLocalPropertiesAndKeystore() {
        val gitignore = File(root, ".gitignore").readText()
        assertTrue(gitignore.contains("local.properties"))
        val hasKeystorePattern = gitignore.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed == "*.jks" || trimmed == "*.keystore"
        }
        assertTrue(hasKeystorePattern)
    }

    @Test
    fun scanSetIncludesGradleBuildFilesAndSrcRootsNotMarkdown() {
        val files = scannedRepoFiles().map { repoRelative(it) }
        assertTrue(files.contains("build.gradle.kts"))
        assertTrue(files.contains("settings.gradle.kts"))
        assertTrue(files.contains("app/build.gradle.kts"))
        assertTrue(files.any { it.startsWith("app/src/") })
        assertTrue(files.any { it.startsWith("ingress/src/") })
        assertTrue(files.any { it.startsWith("contracts/src/") })
        assertTrue(files.any { it.startsWith("orchestration/src/") })
        assertTrue(files.none { it.endsWith(".md") })
        val moduleBuilds = files.filter { it.matches(Regex("[^/]+/build\\.gradle\\.kts")) }
        assertTrue(moduleBuilds.size > 1)
    }

    @Test
    fun scannedSourcesDoNotContainHardCodedAmapAndroidKeys() {
        val hits = mutableListOf<SecretScanHit>()
        scannedRepoFiles().forEach { file ->
            hits += findHardCodedAmapAndroidKeys(repoRelative(file), file.readText())
        }
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") { it.location() } }
    }

    @Test
    fun scannedSourcesDoNotContainHardCodedIflytekCredentials() {
        val hits = mutableListOf<SecretScanHit>()
        scannedRepoFiles().forEach { file ->
            hits += findHardCodedIflytekCredentials(repoRelative(file), file.readText())
        }
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") { it.location() } }
    }

    @Test
    fun wakeWordCredentialConstantNamesAreNotFlagged() {
        val file = File(root, "app/src/main/kotlin/com/novadrive/app/wake/WakeWordCredentials.kt")
        assertTrue(file.isFile)
        val hits = findHardCodedIflytekCredentials(repoRelative(file), file.readText())
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") { it.location() } }
    }

    @Test
    fun amapMatcherFailsOnHardCodedManifestMetaData(@TempDir dir: Path) {
        val secret = "0123456789abcdef0123456789abcdef"
        val relative = "AndroidManifest.xml"
        val file = dir.resolve(relative)
        file.writeText(
            """
            <manifest>
              <application>
                <meta-data
                    android:name="com.amap.api.v2.apikey"
                    android:value="$secret" />
              </application>
            </manifest>
            """.trimIndent(),
        )
        val hits = findHardCodedAmapAndroidKeys(relative, file.readText())
        assertMatcherFiredWithLocationOnly(hits, relative, secret)
    }

    @Test
    fun amapMatcherAllowsManifestPlaceholders(@TempDir dir: Path) {
        val relative = "AndroidManifest.xml"
        val file = dir.resolve(relative)
        file.writeText(
            """
            <manifest>
              <application>
                <meta-data android:name="com.amap.api.v2.apikey" android:value="" />
                <meta-data android:name="com.amap.api.v2.apikey" android:value="${'$'}{AMAP_ANDROID_KEY}" />
                <meta-data android:name="com.amap.api.v2.apikey" android:value="YOUR_AMAP_KEY" />
                <meta-data android:name="com.amap.api.v2.apikey" android:value="PLACEHOLDER" />
                <meta-data android:name="com.amap.api.v2.apikey" android:value="TODO" />
                <meta-data android:name="com.amap.api.v2.apikey" android:value="xxx" />
                <meta-data android:name="com.amap.api.v2.apikey" android:value="BuildConfig.AMAP_KEY" />
              </application>
            </manifest>
            """.trimIndent(),
        )
        val hits = findHardCodedAmapAndroidKeys(relative, file.readText())
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") { it.location() } }
    }

    @Test
    fun amapMatcherFailsOnGradleAmapHexProperty(@TempDir dir: Path) {
        val secret = "cafebabedeadbeefcafebabedeadbeef"
        val relative = "app/build.gradle.kts"
        val file = dir.resolve("build.gradle.kts")
        file.writeText(
            """
            val amapAndroidKey = "$secret"
            extra["amapApiKey"] = "$secret"
            """.trimIndent(),
        )
        val hits = findHardCodedAmapAndroidKeys(relative, file.readText())
        assertMatcherFiredWithLocationOnly(hits, relative, secret)
    }

    @Test
    fun iflytekMatcherFailsOnXmlStringCredentials(@TempDir dir: Path) {
        val appId = "13579246"
        val apiKey = "ab12cd34ef56ab12cd34ef56"
        val apiSecret = "98fe76dc54ba98fe76dc54ba"
        val relative = "res/values/strings.xml"
        val file = dir.resolve("strings.xml")
        file.writeText(
            """
            <resources>
              <string name="appId">$appId</string>
              <string name="apiKey">$apiKey</string>
              <string name="apiSecret">$apiSecret</string>
            </resources>
            """.trimIndent(),
        )
        val hits = findHardCodedIflytekCredentials(relative, file.readText())
        assertTrue(hits.size >= 3) { hits.joinToString("\n") { it.location() } }
        val messages = hits.joinToString("\n") { it.location() }
        assertFalse(messages.contains(appId))
        assertFalse(messages.contains(apiKey))
        assertFalse(messages.contains(apiSecret))
        hits.forEach { hit ->
            assertTrue(LOCATION_ONLY.matches(hit.location()))
            assertTrue(hit.location().startsWith("$relative:"))
        }
    }

    @Test
    fun iflytekMatcherFailsOnIflytekIdentifierAssignment(@TempDir dir: Path) {
        val secret = "syncred99"
        val relative = "src/IflytekConfig.kt"
        val file = dir.resolve("IflytekConfig.kt")
        file.writeText(
            """
            val iflytekAppId = "$secret"
            val iflytekApiKey = "$secret"
            val iflytekApiSecret = "$secret"
            """.trimIndent(),
        )
        val hits = findHardCodedIflytekCredentials(relative, file.readText())
        assertMatcherFiredWithLocationOnly(hits, relative, secret)
        assertTrue(hits.size >= 3) { hits.joinToString("\n") { it.location() } }
    }

    @Test
    fun iflytekMatcherAllowsKeystoreLookupKeyAssignments(@TempDir dir: Path) {
        val relative = "app/src/main/kotlin/com/novadrive/app/wake/WakeWordCredentials.kt"
        val file = dir.resolve("WakeWordCredentials.kt")
        file.writeText(
            """
            class WakeWordCredentials {
                companion object {
                    const val KEY_APP_ID = "iflytek_app_id"
                    const val KEY_API_KEY = "iflytek_api_key"
                    const val KEY_API_SECRET = "iflytek_api_secret"
                    const val iflytek_app_id = "iflytek_app_id"
                }
            }
            """.trimIndent(),
        )
        val hits = findHardCodedIflytekCredentials(relative, file.readText())
        assertTrue(hits.isEmpty()) { hits.joinToString("\n") { it.location() } }
    }

    private fun scannedRepoFiles(): List<File> {
        val files = linkedSetOf<File>()
        SRC_ROOTS.forEach { rel ->
            val dir = File(root, rel)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && it.extension in SRC_EXTENSIONS }
                .forEach { files += it }
        }
        gradleBuildFiles().forEach { files += it }
        return files.toList()
    }

    private fun gradleBuildFiles(): List<File> {
        val out = mutableListOf<File>()
        File(root, "build.gradle.kts").takeIf { it.isFile }?.let { out += it }
        File(root, "settings.gradle.kts").takeIf { it.isFile }?.let { out += it }
        root.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) {
                File(child, "build.gradle.kts").takeIf { it.isFile }?.let { out += it }
            }
        }
        return out
    }

    private fun repoRelative(file: File): String =
        file.relativeTo(root).path.replace('\\', '/')
}

private val SRC_ROOTS = listOf("app/src", "ingress/src", "contracts/src", "orchestration/src")
private val SRC_EXTENSIONS = setOf("kt", "xml", "properties", "kts", "json")
private val LOCATION_ONLY = Regex("^.+:\\d+$")
private val HEX32 = Regex("^[a-f0-9]{32}$")
private val SNAKE_CASE_IDENTIFIER = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)+$")
private val META_DATA_TAG = Regex("""<meta-data\b[^>]*/?>""", setOf(RegexOption.IGNORE_CASE))
private val XML_ATTR = { name: String ->
    Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
}
private val IFLYTEK_STRING_TAG = Regex(
    """<string\b[^>]*\bname\s*=\s*["'](appId|apiKey|apiSecret)["'][^>]*>(.*?)</string>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val IDENT_STRING_ASSIGN = Regex(
    """\b([A-Za-z_][\w]*)\s*(?::[^=\n"]+)?=\s*"([^"]*)"""",
)
private val INDEXED_STRING_ASSIGN = Regex(
    """\[["']([^"']+)["']\]\s*=\s*"([^"]*)"""",
)
private val APIKEY_NEAR_VALUE = Regex(
    """com\.amap\.api\.v2\.apikey["']\s*[,)\]]*\s*(?::\s*)?["']([^"']*)["']""",
    RegexOption.IGNORE_CASE,
)

internal data class SecretScanHit(val file: String, val line: Int) {
    fun location(): String = "$file:$line"
}

internal fun findHardCodedAmapAndroidKeys(file: String, content: String): List<SecretScanHit> {
    val hits = mutableListOf<SecretScanHit>()
    META_DATA_TAG.findAll(content).forEach { match ->
        val tag = match.value
        val name = XML_ATTR("android:name").find(tag)?.groupValues?.get(1).orEmpty()
        val value = XML_ATTR("android:value").find(tag)?.groupValues?.get(1)
        if (value == null) return@forEach
        val valueOffset = match.range.first + (XML_ATTR("android:value").find(tag)?.groups?.get(1)?.range?.first ?: 0)
        val line = lineNumberOf(content, valueOffset)
        val apikeyMeta = name.equals("com.amap.api.v2.apikey", ignoreCase = true)
        if (apikeyMeta && !isAllowedCredentialPlaceholder(value)) {
            hits += SecretScanHit(file, line)
        } else if (HEX32.matches(value.trim())) {
            hits += SecretScanHit(file, line)
        }
    }
    APIKEY_NEAR_VALUE.findAll(content).forEach { match ->
        val value = match.groupValues[1]
        if (!isAllowedCredentialPlaceholder(value)) {
            hits += SecretScanHit(file, lineNumberOf(content, match.groups[1]?.range?.first ?: match.range.first))
        }
    }
    IDENT_STRING_ASSIGN.findAll(content).forEach { match ->
        val ident = match.groupValues[1]
        val value = match.groupValues[2]
        if (ident.contains("amap", ignoreCase = true) && HEX32.matches(value.trim())) {
            hits += SecretScanHit(file, lineNumberOf(content, match.groups[2]?.range?.first ?: match.range.first))
        }
    }
    INDEXED_STRING_ASSIGN.findAll(content).forEach { match ->
        val ident = match.groupValues[1]
        val value = match.groupValues[2]
        if (ident.contains("amap", ignoreCase = true) && HEX32.matches(value.trim())) {
            hits += SecretScanHit(file, lineNumberOf(content, match.groups[2]?.range?.first ?: match.range.first))
        }
    }
    return hits.distinctBy { it.location() }
}

internal fun findHardCodedIflytekCredentials(file: String, content: String): List<SecretScanHit> {
    val hits = mutableListOf<SecretScanHit>()
    IFLYTEK_STRING_TAG.findAll(content).forEach { match ->
        val value = match.groupValues[2]
        if (!isAllowedCredentialPlaceholder(value)) {
            hits += SecretScanHit(file, lineNumberOf(content, match.groups[2]?.range?.first ?: match.range.first))
        }
    }
    fun considerAssignment(ident: String, value: String, valueOffset: Int) {
        if (!isIflytekCredentialIdentifier(ident)) return
        if (value.length < 8) return
        if (isAllowedCredentialPlaceholder(value)) return
        if (SNAKE_CASE_IDENTIFIER.matches(value.trim())) return
        hits += SecretScanHit(file, lineNumberOf(content, valueOffset))
    }
    IDENT_STRING_ASSIGN.findAll(content).forEach { match ->
        considerAssignment(
            match.groupValues[1],
            match.groupValues[2],
            match.groups[2]?.range?.first ?: match.range.first,
        )
    }
    INDEXED_STRING_ASSIGN.findAll(content).forEach { match ->
        considerAssignment(
            match.groupValues[1],
            match.groupValues[2],
            match.groups[2]?.range?.first ?: match.range.first,
        )
    }
    return hits.distinctBy { it.location() }
}

private fun isIflytekCredentialIdentifier(name: String): Boolean {
    val compact = name.lowercase().replace("_", "")
    if (!compact.contains("iflytek")) return false
    return compact.contains("appid") || compact.contains("apikey") || compact.contains("apisecret")
}

private fun isAllowedCredentialPlaceholder(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (value.startsWith("@")) return true
    if (value.contains("BuildConfig")) return true
    if ("\${" in value) return true
    if (value.startsWith("$") && value.length > 1 && value[1].isLetter()) return true
    val folded = value.uppercase()
    if ("YOUR_" in folded) return true
    if ("PLACEHOLDER" in folded) return true
    if ("TODO" in folded) return true
    if ("XXX" in folded) return true
    return false
}

private fun lineNumberOf(content: String, offset: Int): Int {
    val end = offset.coerceIn(0, content.length)
    var line = 1
    for (i in 0 until end) {
        if (content[i] == '\n') line++
    }
    return line
}

private fun assertMatcherFiredWithLocationOnly(
    hits: List<SecretScanHit>,
    expectedFile: String,
    secret: String,
) {
    assertTrue(hits.isNotEmpty())
    val messages = hits.joinToString("\n") { it.location() }
    assertFalse(messages.contains(secret))
    if (secret.length >= 8) {
        assertFalse(messages.contains(secret.take(8)))
        assertFalse(messages.contains(secret.takeLast(8)))
    }
    hits.forEach { hit ->
        val msg = hit.location()
        assertTrue(LOCATION_ONLY.matches(msg))
        assertTrue(msg == "$expectedFile:${hit.line}")
    }
}
