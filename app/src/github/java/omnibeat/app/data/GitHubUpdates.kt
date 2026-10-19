package omnibeat.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class GitHubRelease(val version: String, val apkUrl: String, val apkName: String)

class GitHubUpdates {
    suspend fun newerRelease(installedVersion: String): GitHubRelease? = withContext(Dispatchers.IO) {
        val connection = URI("$REPO_API/releases/latest")
            .toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "FanTing/$installedVersion")
            val status = connection.responseCode
            // 还没有正式版（Latest）时 GitHub 返回 404，这是「没有更新」而不是错误
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub: HTTP $status ${connection.responseMessage}")
            }
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val version = release.getString("tag_name").removePrefix("v")
            if (release.getBoolean("draft") || release.getBoolean("prerelease") ||
                compareVersions(version, installedVersion) <= 0
            ) return@withContext null

            val assets = release.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
            val apk = apks.filter { it.getString("name").contains("github", ignoreCase = true) }
                .singleOrNull() ?: apks.singleOrNull()
                ?: throw IOException("GitHub release $version must contain one GitHub APK")
            val url = apk.getString("browser_download_url")
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "github.com" ||
                !uri.path.startsWith("/$REPO_SLUG/releases/download/")
            ) throw IOException("Invalid GitHub APK URL")
            GitHubRelease(version, url, apk.getString("name"))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 按数字段逐段比较，段数可以不同：`0.9` 与 `0.10`、`0.9` 与 `0.9.1` 都能正确判断。
     * 缺省的段按 0 处理，所以 `0.9` == `0.9.0`。
     */
    private fun compareVersions(first: String, second: String): Int {
        fun parts(version: String): List<Int> {
            if (!version.matches(Regex("[0-9]+(\\.[0-9]+)*"))) {
                throw IOException("Unsupported version: $version")
            }
            return version.split('.').map(String::toInt)
        }
        val a = parts(first)
        val b = parts(second)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    private companion object {
        /** 更新检查只认这个仓库的 Latest 发布 */
        const val REPO_SLUG = "ruzhe85/FanTing"
        const val REPO_API = "https://api.github.com/repos/ruzhe85/FanTing"
    }
}
