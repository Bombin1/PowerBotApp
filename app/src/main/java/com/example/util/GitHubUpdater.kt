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

    suspend fun fetchLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(RELEASES_API_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PowerBot-Android/${BuildConfig.VERSION_NAME}")
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val resp = connection.inputStream.bufferedReader().use { it.readText() }
                Result.success(parseReleaseJson(resp))
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Result.failure(Exception("Помилка GitHub API ($code): $err"))
            }
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
            var currentUrl = downloadUrl
            var redirects = 0
            while (redirects < 5) {
                connection?.disconnect()
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 30000
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
