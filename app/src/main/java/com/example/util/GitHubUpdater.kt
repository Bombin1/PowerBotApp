package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val changelog: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkSizeBytes: Long
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateState()
    data class UpdateAvailable(val release: GitHubRelease) : UpdateState()
    data class Downloading(val release: GitHubRelease, val progressPercent: Int) : UpdateState()
    data class NeedsInstallPermission(val apkFile: File, val release: GitHubRelease) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val release: GitHubRelease) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object GitHubUpdater {

    const val RELEASES_API_URL = "https://api.github.com/repos/Bombin1/PowerBotApp/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/Bombin1/PowerBotApp/releases"
    const val RAW_BASE_URL = "https://raw.githubusercontent.com/Bombin1/PowerBotApp"
    const val MAIN_GRADLE_URL = "$RAW_BASE_URL/main/app/build.gradle.kts"

    /**
     * Parses version strings like "v3.5.1", "3.5.2", "v4.0" into integer segments.
     */
    fun parseVersionSegments(version: String): List<Int> {
        val cleaned = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
        if (cleaned.isBlank()) return listOf(0)
        return cleaned.split(".").map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    /**
     * Returns true if [remoteVersion] is strictly newer than [currentVersion].
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remote = parseVersionSegments(remoteVersion)
        val current = parseVersionSegments(currentVersion)
        val maxLen = maxOf(remote.size, current.size)
        for (i in 0 until maxLen) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun appendCacheBuster(urlString: String, timestampMs: Long = System.currentTimeMillis()): String {
        if (urlString.contains("X-Amz-", ignoreCase = true) || urlString.contains("_ts=")) {
            return urlString
        }
        val separator = if (urlString.contains("?")) "&" else "?"
        return "${urlString}${separator}_ts=$timestampMs"
    }

    fun buildFallbackApkCandidates(tagName: String, preferMain: Boolean = false): List<String> {
        val cleanTag = tagName.trim()
        val candidates = mutableListOf<String>()
        if (preferMain) {
            candidates.add("$RAW_BASE_URL/main/PowerBot.apk")
            candidates.add("$RAW_BASE_URL/main/app-debug.apk")
            if (cleanTag.isNotBlank()) {
                candidates.add("$RAW_BASE_URL/$cleanTag/PowerBot.apk")
                candidates.add("$RAW_BASE_URL/$cleanTag/app-debug.apk")
            }
        } else {
            if (cleanTag.isNotBlank()) {
                candidates.add("$RAW_BASE_URL/$cleanTag/PowerBot.apk")
                candidates.add("$RAW_BASE_URL/$cleanTag/app-debug.apk")
            }
            candidates.add("$RAW_BASE_URL/main/PowerBot.apk")
            candidates.add("$RAW_BASE_URL/main/app-debug.apk")
        }
        return candidates.distinct()
    }

    fun parseGradleVersionName(gradleContent: String): String? {
        val regex = Regex("""versionName\s*=\s*["']([^"']+)["']""")
        return regex.find(gradleContent)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun parseReleaseJson(jsonString: String): GitHubRelease {
        val json = JSONObject(jsonString)
        val tagName = json.optString("tag_name", "").trim()
        val versionName = tagName.removePrefix("v").removePrefix("V").trim()
        val releaseName = json.optString("name", tagName).ifBlank { tagName }
        val changelog = json.optString("body", "").trim()
        val htmlUrl = json.optString("html_url", RELEASES_PAGE_URL).ifBlank { RELEASES_PAGE_URL }

        var apkUrl: String? = null
        var apkName: String? = null
        var apkSize = 0L

        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                    apkName = name
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }

        return GitHubRelease(
            tagName = tagName,
            versionName = versionName,
            releaseName = releaseName,
            changelog = changelog,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSizeBytes = apkSize
        )
    }

    private fun probeApkUrl(urlString: String): Long? {
        val freshUrl = appendCacheBuster(urlString)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(freshUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "PowerBot-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code in 200..299) {
                val len = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: connection.contentLength.toLong()
                if (len <= 0L || len > 100_000L) return len.coerceAtLeast(0L)
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }

        // Fallback to ranged GET if CDN/host rejects HEAD
        var getConn: HttpURLConnection? = null
        return try {
            getConn = (URL(freshUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "PowerBot-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = getConn.responseCode
            if (code in 200..299) {
                val rangeTotal = getConn.getHeaderField("Content-Range")
                    ?.substringAfter("/", "")
                    ?.trim()
                    ?.toLongOrNull()
                val len = rangeTotal ?: (getConn.getHeaderField("Content-Length")?.toLongOrNull() ?: getConn.contentLength.toLong())
                if (len <= 0L || len > 100_000L) len.coerceAtLeast(0L) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            getConn?.disconnect()
        }
    }

    private fun resolveRepoApk(tagName: String, preferMain: Boolean = false): Triple<String, String, Long>? {
        for (candidate in buildFallbackApkCandidates(tagName, preferMain = preferMain)) {
            val size = probeApkUrl(candidate)
            if (size != null) {
                val verSuffix = tagName.ifBlank { "latest" }
                val fileName = if (candidate.endsWith("PowerBot.apk", ignoreCase = true)) {
                    "PowerBot-$verSuffix.apk"
                } else {
                    "PowerBot-$verSuffix-debug.apk"
                }
                return Triple(candidate, fileName, size)
            }
        }
        return null
    }

    private fun fetchMainBranchVersion(): String? {
        var connection: HttpURLConnection? = null
        return try {
            val freshUrl = appendCacheBuster(MAIN_GRADLE_URL)
            connection = (URL(freshUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "PowerBot-Android/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode in 200..299) {
                val content = connection.inputStream.bufferedReader().use { it.readText() }
                parseGradleVersionName(content)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun fetchLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(appendCacheBuster(RELEASES_API_URL))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "PowerBot-Android/${BuildConfig.VERSION_NAME}")
            }

            val code = connection.responseCode
            var release: GitHubRelease? = if (code in 200..299) {
                val resp = connection.inputStream.bufferedReader().use { it.readText() }
                parseReleaseJson(resp)
            } else {
                null
            }

            // Check main branch version; prioritize main/PowerBot.apk if main >= latest release
            val mainVersion = fetchMainBranchVersion()
            if (mainVersion != null && (release == null || isNewerVersion(mainVersion, release.versionName) || mainVersion == release.versionName)) {
                val targetTag = if (release != null && mainVersion == release.versionName && release.tagName.isNotBlank()) {
                    release.tagName
                } else {
                    "v$mainVersion"
                }
                val mainApk = resolveRepoApk(targetTag, preferMain = true)
                val isSameAsRelease = release != null && mainVersion == release.versionName
                release = GitHubRelease(
                    tagName = targetTag,
                    versionName = mainVersion,
                    releaseName = if (isSameAsRelease) release!!.releaseName else "PowerBot v$mainVersion",
                    changelog = release?.changelog?.takeIf { it.isNotBlank() } ?: "Оновлення додатка з гілки main на GitHub.",
                    htmlUrl = if (isSameAsRelease) release!!.htmlUrl else RELEASES_PAGE_URL,
                    apkDownloadUrl = mainApk?.first ?: if (isSameAsRelease) release?.apkDownloadUrl else null,
                    apkFileName = mainApk?.second ?: if (isSameAsRelease) release?.apkFileName else null,
                    apkSizeBytes = mainApk?.third ?: if (isSameAsRelease) (release?.apkSizeBytes ?: 0L) else 0L
                )
            }

            if (release == null) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                return@withContext Result.failure(Exception("Помилка GitHub API ($code): $err"))
            }

            // If GitHub release still has no attached .apk asset, automatically resolve PowerBot.apk / app-debug.apk from the repo
            if (release.apkDownloadUrl.isNullOrBlank()) {
                val resolved = resolveRepoApk(release.tagName, preferMain = false)
                if (resolved != null) {
                    release = release.copy(
                        apkDownloadUrl = resolved.first,
                        apkFileName = resolved.second,
                        apkSizeBytes = resolved.third
                    )
                }
            }

            Result.success(release)
        } catch (e: Exception) {
            Result.failure(Exception("Не вдалося перевірити оновлення: ${e.message ?: "немає мережі"}"))
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = appendCacheBuster(downloadUrl)
            var redirects = 0
            while (redirects < 5) {
                connection?.disconnect()
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = 20000
                    readTimeout = 30000
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "PowerBot-Android/${BuildConfig.VERSION_NAME}")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return@withContext Result.failure(Exception("Невірне перенаправлення при завантаженні APK"))
                    currentUrl = location
                    redirects++
                } else {
                    break
                }
            }

            val finalConn = connection ?: return@withContext Result.failure(Exception("Помилка з'єднання"))
            val code = finalConn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("Помилка завантаження APK (HTTP $code)"))
            }

            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.cacheDir, "updates").apply { mkdirs() }
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "PowerBot-update.apk" }
            val outFile = File(downloadsDir, safeName)
            if (outFile.exists()) outFile.delete()

            val totalBytes = finalConn.contentLength.toLong()
            var downloadedBytes = 0L
            var lastReported = -1

            finalConn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val pct = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }

            // Verify the downloaded file is a valid APK (ZIP archive starts with "PK")
            val isValidApk = outFile.exists() && outFile.length() > 100_000L && outFile.inputStream().use {
                val hdr = ByteArray(2)
                it.read(hdr) == 2 && hdr[0] == 0x50.toByte() && hdr[1] == 0x4B.toByte()
            }
            if (!isValidApk) {
                outFile.delete()
                return@withContext Result.failure(Exception("Завантажений файл не є коректним APK-пакетом"))
            }

            onProgress(100)
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(Exception("Помилка завантаження: ${e.message ?: "невідома помилка"}"))
        } finally {
            connection?.disconnect()
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    fun createInstallIntent(context: Context, apkFile: File): Intent {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
