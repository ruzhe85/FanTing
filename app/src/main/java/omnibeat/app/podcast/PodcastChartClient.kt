package omnibeat.app.podcast

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import omnibeat.app.model.PODCAST_CHART_CACHE_TTL_MS
import omnibeat.app.model.PODCAST_CHART_LIMIT
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/** 榜单条目：只有发现页需要的字段；`feedUrl` 由批量 lookup 补全后随缓存落盘 */
data class PodcastChartEntry(
    val collectionId: Long,
    val title: String,
    val author: String,
    val artworkUrl: String? = null,
    val genres: List<String> = emptyList(),
    val feedUrl: String? = null,
)

/** 一份地区榜单 + 拉取时间（决定缓存是否还新鲜） */
data class PodcastChart(
    val country: String,
    val fetchedAtMs: Long,
    val entries: List<PodcastChartEntry>,
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean {
        return now - fetchedAtMs in 0L..PODCAST_CHART_CACHE_TTL_MS
    }
}

/**
 * Apple 官方热门播客榜单（零依赖：HttpURLConnection + org.json）。
 *
 * 接口本身不返回 `feedUrl`，这里只负责「榜单 + 按地区缓存」；
 * 点击条目后由 [ITunesPodcastClient.lookup] 用 `collectionId` 换订阅地址。
 *
 * 缓存文件：`cacheDir/podcast_chart/{country}.json`，结构 `{ savedAt, country, items[] }`。
 * 放 cacheDir 是因为它随时可重新拉取，被系统清掉也不影响正确性。
 */
class PodcastChartClient(context: Context) {
    private val cacheDirectory = File(context.applicationContext.cacheDir, "podcast_chart")

    /** 只读本地缓存，不联网；没有缓存或文件损坏时返回 null */
    suspend fun cached(countryCode: String): PodcastChart? = withContext(Dispatchers.IO) {
        readCache(normalizeCountry(countryCode))
    }

    /**
     * 联网拉取并写缓存。
     * 非 2xx、响应无法解析、或解析后没有任何条目时抛带可展示文案的 [IOException]。
     */
    suspend fun fetch(
        countryCode: String,
        limit: Int = PODCAST_CHART_LIMIT,
    ): PodcastChart = withContext(Dispatchers.IO) {
        val country = normalizeCountry(countryCode)
        val count = limit.coerceIn(1, MAX_LIMIT)
        val url = "$CHART_URL/$country/podcasts/top/$count/podcasts.json"
        val entries = decodeEntries(getJsonWithRetry(url))
        if (entries.isEmpty()) throw IOException("榜单暂时没有内容")
        val chart = PodcastChart(
            country = country,
            fetchedAtMs = System.currentTimeMillis(),
            entries = entries,
        )
        // 缓存只是加速下次打开：写失败（磁盘满等）不该让本次结果也失败
        runCatching { writeCache(chart) }
        chart
    }

    /** 把补全了 feedUrl 的榜单写回缓存；写失败不抛错，内存里仍可用 */
    suspend fun saveChart(chart: PodcastChart) {
        withContext(Dispatchers.IO) {
            runCatching { writeCache(chart) }
        }
    }

    /**
     * Apple 榜单首次连接偶发超时（用户实测重试即成功），这里静默重试一次。
     * 只重试超时：HTTP 非 2xx / 解析失败照常抛给页面，避免掩盖真实错误。
     */
    private suspend fun getJsonWithRetry(requestUrl: String): JSONObject {
        return try {
            getJson(requestUrl)
        } catch (timeout: SocketTimeoutException) {
            delay(RETRY_DELAY_MS)
            getJson(requestUrl)
        }
    }

    /** 拉取 JSON；HTTP 非 2xx、响应不是 JSON 时抛带可展示文案的 [IOException] */
    private suspend fun getJson(requestUrl: String): JSONObject {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val body = try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("榜单返回 HTTP $code")
            }
            connection.inputStream.use { stream -> readFully(stream) }
        } finally {
            connection.disconnect()
        }
        return try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw IOException("榜单返回的内容无法解析", error)
        }
    }

    /** 分块读取，块间检查协程取消：退出页面时不必等读超时（15s）结束 */
    private suspend fun readFully(stream: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(chunk)
            if (read < 0) break
            bytes.write(chunk, 0, read)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun decodeEntries(json: JSONObject): List<PodcastChartEntry> {
        val feed = json.optJSONObject(Json.FEED) ?: return emptyList()
        val results = feed.optJSONArray(Json.RESULTS) ?: return emptyList()
        return buildList {
            repeat(results.length()) { index ->
                val item = results.optJSONObject(index) ?: return@repeat
                decodeEntry(item)?.let(::add)
            }
        }
    }

    /** 缺 id 或名称的条目没有订阅价值，直接丢掉 */
    private fun decodeEntry(item: JSONObject): PodcastChartEntry? {
        val collectionId = item.stringOrEmpty(Json.ID).toLongOrNull() ?: return null
        val title = item.stringOrEmpty(Json.NAME)
        if (collectionId <= 0L || title.isEmpty()) return null
        return PodcastChartEntry(
            collectionId = collectionId,
            title = title,
            author = item.stringOrEmpty(Json.ARTIST_NAME),
            artworkUrl = item.stringOrEmpty(Json.ARTWORK_100)
                .takeIf { it.isNotEmpty() }
                ?.let(::upgradeArtwork),
            genres = decodeGenres(item.optJSONArray(Json.GENRES)),
            // 榜单接口本身没有 feedUrl；这里读到的是批量 lookup 补全后写进缓存的
            feedUrl = item.stringOrEmpty(Json.FEED_URL).takeIf { it.isNotEmpty() },
        )
    }

    private fun decodeGenres(genres: JSONArray?): List<String> {
        if (genres == null) return emptyList()
        return buildList {
            repeat(genres.length()) { index ->
                val name = genres.optJSONObject(index)
                    ?.stringOrEmpty(Json.GENRE_NAME)
                    .orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }
    }

    /**
     * 榜单只给 100×100 封面，列表行够用但详情页会糊。
     * mzstatic 的缩略图 URL 支持任意尺寸，把 100x100bb 换成 600x600bb 即可。
     */
    private fun upgradeArtwork(url: String): String {
        return url.replace(ARTWORK_SMALL, ARTWORK_LARGE)
    }

    private fun readCache(country: String): PodcastChart? {
        val file = AtomicFile(File(cacheDirectory, "$country.json"))
        if (!file.baseFile.exists()) return null
        return try {
            val text = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val items = json.optJSONArray(Json.ITEMS) ?: return null
            val entries = buildList {
                repeat(items.length()) { index ->
                    val item = items.optJSONObject(index) ?: return@repeat
                    decodeEntry(item)?.let(::add)
                }
            }
            if (entries.isEmpty()) {
                null
            } else {
                PodcastChart(
                    country = country,
                    fetchedAtMs = json.optLong(Json.SAVED_AT, 0L),
                    entries = entries,
                )
            }
        } catch (_: Exception) {
            // 缓存损坏按未命中处理，下一次请求会重建
            null
        }
    }

    private fun writeCache(chart: PodcastChart) {
        cacheDirectory.mkdirs()
        val items = JSONArray()
        chart.entries.forEach { entry ->
            items.put(
                JSONObject()
                    .put(Json.ID, entry.collectionId.toString())
                    .put(Json.NAME, entry.title)
                    .put(Json.ARTIST_NAME, entry.author)
                    .put(Json.ARTWORK_100, entry.artworkUrl.orEmpty())
                    .put(Json.FEED_URL, entry.feedUrl.orEmpty())
                    // 与接口结构保持一致（genres 是 [{name}]），读缓存时复用同一套解析
                    .put(
                        Json.GENRES,
                        JSONArray().apply {
                            entry.genres.forEach { name -> put(JSONObject().put(Json.GENRE_NAME, name)) }
                        },
                    ),
            )
        }
        val bytes = JSONObject()
            .put(Json.SAVED_AT, chart.fetchedAtMs)
            .put(Json.COUNTRY, chart.country)
            .put(Json.ITEMS, items)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val file = AtomicFile(File(cacheDirectory, "${chart.country}.json"))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    /** 只接受两位字母地区码；其它值一律回退美国区，避免拼出奇怪的文件名/URL */
    private fun normalizeCountry(countryCode: String): String {
        val code = countryCode.trim().lowercase(Locale.ROOT)
        return if (code.length == 2 && code.all { it in 'a'..'z' }) code else FALLBACK_COUNTRY
    }

    /** JSON 里的 null 用 optString 取会得到字符串 "null"，这里统一成空串 */
    private fun JSONObject.stringOrEmpty(name: String): String {
        if (isNull(name)) return ""
        return optString(name).trim()
    }

    private object Json {
        const val FEED = "feed"
        const val RESULTS = "results"
        const val ID = "id"
        const val NAME = "name"
        const val ARTIST_NAME = "artistName"
        const val ARTWORK_100 = "artworkUrl100"
        const val FEED_URL = "feedUrl"
        const val GENRES = "genres"
        const val GENRE_NAME = "name"
        const val ITEMS = "items"
        const val SAVED_AT = "savedAt"
        const val COUNTRY = "country"
    }

    private companion object {
        const val CHART_URL = "https://rss.marketingtools.apple.com/api/v2"
        const val USER_AGENT = "OmniBeatP Android"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val RETRY_DELAY_MS = 600L
        const val MAX_LIMIT = 200
        const val FALLBACK_COUNTRY = "us"
        const val ARTWORK_SMALL = "100x100bb"
        const val ARTWORK_LARGE = "600x600bb"
    }
}
