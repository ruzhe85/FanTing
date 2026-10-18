package omnibeat.app.podcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * iTunes Search API 的播客搜索（零依赖：HttpURLConnection + org.json）。
 *
 * 只负责 [search]：按关键词搜播客，返回订阅所需的元数据（标题 / 作者 / feedUrl / 封面 / 类型 / 期数）。
 * 注意 iTunes 的 search 与 lookup 响应都**不包含简介**，简介由详情页从订阅源头部读取
 * （见 [RssParser.loadSummary]）。
 *
 * 订阅本身仍然走 [RssParser]：iTunes 只负责告诉你「有哪些播客、RSS 在哪」，
 * 期数与可播放地址以真实订阅源为准，避免搜索结果与实际内容不一致。
 */
data class ITunesPodcast(
    val collectionId: Long,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String? = null,
    val genre: String? = null,
    val trackCount: Int = 0,
    val description: String? = null,
)

object ITunesPodcastClient {
    private const val USER_AGENT = "OmniBeatP Android"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val SEARCH_URL = "https://itunes.apple.com/search"
    private const val LOOKUP_URL = "https://itunes.apple.com/lookup"
    private const val DEFAULT_COUNTRY = "us"
    private const val DEFAULT_LIMIT = 25
    private const val MAX_LIMIT = 200

    private object Query {
        const val ID = "id"
        const val TERM = "term"
        const val MEDIA = "media"
        const val ENTITY = "entity"
        const val COUNTRY = "country"
        const val LIMIT = "limit"
    }

    private object Value {
        const val PODCAST = "podcast"
    }

    private object Json {
        const val RESULTS = "results"
        const val COLLECTION_ID = "collectionId"
        const val COLLECTION_NAME = "collectionName"
        const val ARTIST_NAME = "artistName"
        const val FEED_URL = "feedUrl"
        const val ARTWORK_600 = "artworkUrl600"
        const val ARTWORK_100 = "artworkUrl100"
        const val ARTWORK_60 = "artworkUrl60"
        const val GENRE = "primaryGenreName"
        const val TRACK_COUNT = "trackCount"
        const val DESCRIPTION = "description"
    }

    /**
     * 关键词搜索。空关键词直接返回空列表（不浪费一次请求）。
     * [countryCode] 是 iTunes 商店地区（如 `us` / `cn`），空值回退美国区。
     */
    suspend fun search(
        term: String,
        countryCode: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<ITunesPodcast> {
        val keyword = term.trim()
        if (keyword.isEmpty()) return emptyList()
        val country = normalizeCountry(countryCode)
        val url = buildString {
            append(SEARCH_URL)
            append('?').append(Query.MEDIA).append('=').append(Value.PODCAST)
            append('&').append(Query.ENTITY).append('=').append(Value.PODCAST)
            append('&').append(Query.COUNTRY).append('=').append(urlEncode(country))
            append('&').append(Query.LIMIT).append('=').append(limit.coerceIn(1, MAX_LIMIT))
            append('&').append(Query.TERM).append('=').append(urlEncode(keyword))
        }
        return decodeResults(getJson(url))
    }

    /**
     * 用榜单里的 `collectionId` 换订阅所需的 `feedUrl`。
     *
     * 榜单接口不含 `feedUrl`，只能靠一次 lookup 补全；条目已下架或地区不匹配时
     * iTunes 会返回空 results，这里返回 null 由调用方提示。
     * [countryCode] 必须与榜单地区一致，否则可能查不到该条目的订阅源。
     */
    suspend fun lookup(collectionId: Long, countryCode: String): ITunesPodcast? {
        if (collectionId <= 0L) return null
        val country = normalizeCountry(countryCode)
        val url = buildString {
            append(LOOKUP_URL)
            append('?').append(Query.ID).append('=').append(collectionId)
            append('&').append(Query.COUNTRY).append('=').append(urlEncode(country))
        }
        return decodeResults(getJson(url)).firstOrNull()
    }

    /**
     * 批量 lookup：热门榜单补全 feedUrl 用。
     *
     * iTunes 支持 `id=1,2,3` 一次查多条，返回 `collectionId → feedUrl`；
     * 查不到的条目不会出现在结果里，由调用方当作「未补全」处理。
     */
    suspend fun lookupMany(collectionIds: List<Long>, countryCode: String): Map<Long, String> {
        val ids = collectionIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val country = normalizeCountry(countryCode)
        val url = buildString {
            append(LOOKUP_URL)
            append('?').append(Query.ID).append('=').append(ids.joinToString(","))
            append('&').append(Query.COUNTRY).append('=').append(urlEncode(country))
        }
        return decodeResults(getJson(url))
            .mapNotNull { podcast ->
                podcast.feedUrl.takeIf { it.isNotBlank() }?.let { podcast.collectionId to it }
            }
            .toMap()
    }

    /** 拉取 JSON；HTTP 非 2xx、响应不是 JSON 时抛带可展示文案的 [IOException] */
    private suspend fun getJson(requestUrl: String): JSONObject = withContext(Dispatchers.IO) {
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
                throw IOException("iTunes 返回 HTTP $code")
            }
            connection.inputStream.use { stream -> readFully(stream) }
        } finally {
            connection.disconnect()
        }
        try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw IOException("iTunes 返回的内容无法解析", error)
        }
    }

    /**
     * 分块读取响应体，块间检查协程取消：搜索页退出时不必等读超时（15s）结束。
     * 阻塞在单次 read 上时仍受 readTimeout 约束。
     */
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

    private fun decodeResults(json: JSONObject): List<ITunesPodcast> {
        val items = json.optJSONArray(Json.RESULTS) ?: return emptyList()
        return buildList {
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                decodePodcast(item)?.let(::add)
            }
        }
    }

    /** 缺 feedUrl 或标题的条目没有订阅价值，直接丢掉 */
    private fun decodePodcast(item: JSONObject): ITunesPodcast? {
        val title = item.stringOrEmpty(Json.COLLECTION_NAME)
        val feedUrl = item.stringOrEmpty(Json.FEED_URL)
        if (title.isEmpty() || feedUrl.isEmpty()) return null
        val artworkUrl = listOf(Json.ARTWORK_600, Json.ARTWORK_100, Json.ARTWORK_60)
            .map { item.stringOrEmpty(it) }
            .firstOrNull { it.isNotEmpty() }
        return ITunesPodcast(
            collectionId = item.optLong(Json.COLLECTION_ID, 0L),
            title = title,
            author = item.stringOrEmpty(Json.ARTIST_NAME),
            feedUrl = feedUrl,
            artworkUrl = artworkUrl,
            genre = item.stringOrEmpty(Json.GENRE).takeIf { it.isNotEmpty() },
            trackCount = item.optInt(Json.TRACK_COUNT, 0).coerceAtLeast(0),
            description = item.stringOrEmpty(Json.DESCRIPTION).takeIf { it.isNotEmpty() },
        )
    }

    /** JSON 里的 null 用 optString 取会得到字符串 "null"，这里统一成空串 */
    private fun JSONObject.stringOrEmpty(name: String): String {
        if (isNull(name)) return ""
        return optString(name).trim()
    }

    private fun normalizeCountry(countryCode: String): String {
        return countryCode.trim().lowercase(Locale.US).ifEmpty { DEFAULT_COUNTRY }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
