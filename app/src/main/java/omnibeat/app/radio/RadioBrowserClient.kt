package omnibeat.app.radio

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import omnibeat.app.model.nameComparator
import kotlin.time.Duration.Companion.days

data class RadioBrowserStation(
    val stationUuid: String,
    val title: String,
    val streamUrl: String,
    val tags: List<String>,
    val countryCode: String,
    val codec: String,
    val bitrate: Int,
    val imageUrl: String?,
)

fun RadioBrowserStation.stationTags(): List<String> {
    return buildList {
        countryCode.takeIf { it.isNotBlank() }?.let(::add)
        codec.takeIf { it.isNotBlank() }?.let(::add)
        bitrate.takeIf { it > 0 }?.let { add("$it kbps") }
        addAll(tags.take(4))
    }.distinctBy { it.lowercase() }
}

data class RadioBrowserFilterOption(
    val name: String,
    val code: String = "",
)

/** 一次封面查找的结果：查到可用图 / 查到电台但没图 / 根本没匹配到电台 */
sealed interface ArtworkLookup {
    data class Found(val imageUrl: String) : ArtworkLookup
    data class NoArtwork(val stationName: String) : ArtworkLookup
    data object NotFound : ArtworkLookup
}

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

enum class RadioBrowserSort(val apiValue: String) {
    Clicks(RadioBrowserApi.Sort.CLICK_COUNT),
    Votes(RadioBrowserApi.Sort.VOTES),
    Name(RadioBrowserApi.Sort.NAME),
    Bitrate(RadioBrowserApi.Sort.BITRATE),
    Country(RadioBrowserApi.Sort.COUNTRY),
    Random(RadioBrowserApi.Sort.RANDOM),
}

data class RadioBrowserSearchParams(
    val name: String,
    val tags: String,
    val country: RadioBrowserFilterOption?,
    val language: RadioBrowserFilterOption?,
    val sort: RadioBrowserSort,
    val bitrateMin: Int?,
    val bitrateMax: Int?,
    val reverse: Boolean,
    val includeBroken: Boolean,
    val offset: Int = 0,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

private object RadioBrowserApi {
    object Path {
        const val SERVERS = "/json/servers"
        const val STATIONS_SEARCH = "/json/stations/search"
        const val STATIONS_BY_URL = "/json/stations/byurl"
        const val STATIONS_BY_UUID = "/json/stations/byuuid"
        const val COUNTRIES = "/json/countries"
        const val LANGUAGES = "/json/languages"
    }

    object Query {
        const val URL = "url"
        const val UUIDS = "uuids"
        const val NAME = "name"
        const val TAG_LIST = "tagList"
        const val COUNTRY = "country"
        const val COUNTRY_CODE = "countrycode"
        const val LANGUAGE = "language"
        const val ORDER = "order"
        const val REVERSE = "reverse"
        const val HIDE_BROKEN = "hidebroken"
        const val BITRATE_MIN = "bitrateMin"
        const val BITRATE_MAX = "bitrateMax"
        const val LIMIT = "limit"
        const val OFFSET = "offset"
    }

    object Sort {
        const val CLICK_COUNT = "clickcount"
        const val VOTES = "votes"
        const val NAME = "name"
        const val BITRATE = "bitrate"
        const val COUNTRY = "country"
        const val RANDOM = "random"
        const val STATION_COUNT = "stationcount"
    }

    object Json {
        const val NAME = "name"
        const val URL_RESOLVED = "url_resolved"
        const val URL = "url"
        const val STATION_UUID = "stationuuid"
        const val TAGS = "tags"
        const val COUNTRY_CODE = "countrycode"
        const val CODEC = "codec"
        const val BITRATE = "bitrate"
        const val FAVICON = "favicon"
        const val ISO_3166_1 = "iso_3166_1"
    }

    object Value {
        const val TRUE = "true"
    }
}

class RadioBrowserClient(context: Context) {
    private val appContext = context.applicationContext
    private var cachedBaseUrl: String? = null
    private val filterCacheDirectory = File(appContext.cacheDir, "radio_browser_filters")

    private data class FilterCache(val savedAt: Long, val options: List<RadioBrowserFilterOption>) {
        val isFresh: Boolean
            get() = System.currentTimeMillis() - savedAt in 0 until 3.days.inWholeMilliseconds
    }

    suspend fun searchStations(params: RadioBrowserSearchParams): List<RadioBrowserStation> = withContext(Dispatchers.IO) {
        val query = buildQuery(
            listOfNotNull(
                RadioBrowserApi.Query.NAME to params.name.trim(),
                params.tags.trim().takeIf { it.isNotBlank() }?.let { tags ->
                    RadioBrowserApi.Query.TAG_LIST to tags.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(",")
                },
                params.country?.let { country ->
                    if (country.code.isNotBlank()) {
                        RadioBrowserApi.Query.COUNTRY_CODE to country.code
                    } else {
                        RadioBrowserApi.Query.COUNTRY to country.name
                    }
                },
                params.language?.let { RadioBrowserApi.Query.LANGUAGE to it.name },
                RadioBrowserApi.Query.ORDER to params.sort.apiValue,
                RadioBrowserApi.Query.REVERSE to params.reverse.toString(),
                RadioBrowserApi.Query.HIDE_BROKEN to (!params.includeBroken).toString(),
                params.bitrateMin?.let { RadioBrowserApi.Query.BITRATE_MIN to it.toString() },
                params.bitrateMax?.let { RadioBrowserApi.Query.BITRATE_MAX to it.toString() },
                RadioBrowserApi.Query.OFFSET to params.offset.coerceAtLeast(0).toString(),
                RadioBrowserApi.Query.LIMIT to params.limit.coerceAtLeast(1).toString(),
            ).filter { it.second.isNotBlank() },
        )
        decodeStations(readRadioBrowserText("${RadioBrowserApi.Path.STATIONS_SEARCH}?$query"))
    }

    /**
     * 按「uuid → 流地址（含等价写法）→ 名称」三级顺序找封面。
     *
     * 只靠 byurl 是不够的：它是**精确匹配 `url` 字段**，而同一个流在库里常有 http/https 两种协议、
     * 蜻蜓的 qingting.fm / qtfm.cn / lhttp-hw.qtfm.cn 三套域名，央广的 ngcdn001/002；国内直连源
     * （例如 satellitepull.cnr.cn 的卫星转发）则根本没有入库，只能靠名称匹配。
     */
    suspend fun resolveArtwork(
        title: String,
        streamUrl: String,
        stationUuid: String?,
    ): ArtworkLookup = withContext(Dispatchers.IO) {
        val candidates = LinkedHashMap<String, RadioBrowserStation>()
        fun collect(stations: List<RadioBrowserStation>) {
            stations.forEach { station ->
                candidates[station.stationUuid.ifBlank { station.streamUrl }] = station
            }
        }

        // 有图的优先：库里同名电台经常是「票数最高的那条没图、票数低的有图」
        fun bestWithArtwork(): RadioBrowserStation? = candidates.values
            .filter { !it.imageUrl.isNullOrBlank() }
            .maxByOrNull { score(it, title, streamUrl) }

        // 1. 在线搜索添加的电台会记下 stationuuid，按 uuid 查最准
        stationUuid?.takeIf { UUID_PATTERN.matches(it) }?.let { uuid ->
            collect(findStationsByUuid(uuid))
        }
        bestWithArtwork()?.let { return@withContext ArtworkLookup.Found(requireNotNull(it.imageUrl)) }

        // 2. 按流地址的等价写法逐个精确查
        for (variant in urlVariants(streamUrl)) {
            collect(findStationsByUrl(variant))
            bestWithArtwork()?.let { return@withContext ArtworkLookup.Found(requireNotNull(it.imageUrl)) }
        }

        // 3. 按名称模糊搜索，并用归一化标题过滤掉不相干的同名候选
        val normalizedTitle = normalizeStationName(title)
        if (normalizedTitle.isNotEmpty()) {
            collect(
                searchByName(title.trim()).filter { station ->
                    val normalizedCandidate = normalizeStationName(station.title)
                    normalizedCandidate.isNotEmpty() && (
                        normalizedCandidate == normalizedTitle ||
                            normalizedCandidate.contains(normalizedTitle) ||
                            normalizedTitle.contains(normalizedCandidate)
                        )
                },
            )
        }
        bestWithArtwork()?.let { return@withContext ArtworkLookup.Found(requireNotNull(it.imageUrl)) }

        val matched = candidates.values.maxByOrNull { score(it, title, streamUrl) }
        if (matched != null) ArtworkLookup.NoArtwork(matched.title) else ArtworkLookup.NotFound
    }

    private fun findStationsByUrl(url: String): List<RadioBrowserStation> {
        val query = buildQuery(listOf(RadioBrowserApi.Query.URL to url.trim()))
        return decodeStations(readRadioBrowserText("${RadioBrowserApi.Path.STATIONS_BY_URL}?$query"))
    }

    private fun findStationsByUuid(uuid: String): List<RadioBrowserStation> {
        val query = buildQuery(listOf(RadioBrowserApi.Query.UUIDS to uuid.trim()))
        return decodeStations(readRadioBrowserText("${RadioBrowserApi.Path.STATIONS_BY_UUID}?$query"))
    }

    private fun searchByName(name: String, limit: Int = 20): List<RadioBrowserStation> {
        if (name.isBlank()) return emptyList()
        val query = buildQuery(
            listOf(
                RadioBrowserApi.Query.NAME to name,
                RadioBrowserApi.Query.HIDE_BROKEN to RadioBrowserApi.Value.TRUE,
                RadioBrowserApi.Query.ORDER to RadioBrowserApi.Sort.VOTES,
                RadioBrowserApi.Query.REVERSE to RadioBrowserApi.Value.TRUE,
                RadioBrowserApi.Query.LIMIT to limit.coerceAtLeast(1).toString(),
            ),
        )
        return runCatching {
            decodeStations(readRadioBrowserText("${RadioBrowserApi.Path.STATIONS_SEARCH}?$query"))
        }.getOrDefault(emptyList())
    }

    /**
     * 同一个流在库里的等价写法：协议互换、同族域名互换，最后再补一个位率写法
     * （`/live/<id>/64k.mp3` 与 `128k.mp3` 在库里是混着用的）。
     */
    private fun urlVariants(rawUrl: String): List<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return emptyList()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return listOf(trimmed)
        val scheme = uri.scheme?.lowercase() ?: return listOf(trimmed)
        if (scheme != "http" && scheme != "https") return listOf(trimmed)
        val host = uri.host?.lowercase() ?: return listOf(trimmed)
        val tail = buildString {
            val path = uri.rawPath.orEmpty()
            if (path.isNotEmpty()) append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
        val alternateScheme = if (scheme == "http") "https" else "http"
        val aliases = HOST_ALIASES[host].orEmpty()
        val variants = LinkedHashSet<String>()
        variants += "$scheme://$host$tail"
        variants += "$alternateScheme://$host$tail"
        aliases.filter { it != host }.forEach { variants += "$scheme://$it$tail" }
        aliases.filter { it != host }.forEach { variants += "$alternateScheme://$it$tail" }
        BITRATE_URL_REGEX.find("$scheme://$host$tail")?.let { match ->
            val prefix = match.groupValues[1]
            val other = if (match.groupValues[2] == "64") "128" else "64"
            variants += "$prefix${other}k.mp3"
        }
        return variants.take(MAX_URL_VARIANTS)
    }

    private fun score(candidate: RadioBrowserStation, title: String, streamUrl: String): Int {
        var total = 0
        if (candidate.streamUrl.equals(streamUrl, ignoreCase = true)) total += 100
        val channelId = channelIdOf(streamUrl)
        if (channelId != null && channelId == channelIdOf(candidate.streamUrl)) total += 60
        val host = hostOf(streamUrl)
        if (host != null && host == hostOf(candidate.streamUrl)) total += 40
        val normalizedTitle = normalizeStationName(title)
        if (normalizedTitle.isNotEmpty()) {
            val normalizedCandidate = normalizeStationName(candidate.title)
            when {
                normalizedCandidate.isEmpty() -> Unit
                normalizedCandidate == normalizedTitle -> total += 50
                normalizedCandidate.startsWith(normalizedTitle) ||
                    normalizedTitle.startsWith(normalizedCandidate) -> total += 25
                normalizedCandidate.contains(normalizedTitle) ||
                    normalizedTitle.contains(normalizedCandidate) -> total += 10
            }
        }
        return total
    }

    /** 去掉 `CNR-1`、`卫星`、`(FM91.4)` 这类前后缀，只留下可比的台名 */
    private fun normalizeStationName(raw: String): String {
        var text = raw.trim()
        text = NAME_BRACKETS_REGEX.replace(text, " ")
        text = NAME_FREQUENCY_REGEX.replace(text, " ")
        text = NAME_PREFIX_REGEX.replace(text, "")
        text = text.replace("卫星", " ")
        text = NAME_SEPARATOR_REGEX.replace(text, "")
        return text.lowercase()
    }

    private fun channelIdOf(url: String): String? = CHANNEL_ID_REGEX.find(url)?.groupValues?.get(1)

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    suspend fun countries(): List<RadioBrowserFilterOption> = withContext(Dispatchers.IO) {
        readFilterCache("countries")?.takeIf { it.isFresh }?.let {
            return@withContext it.options.sortedByName()
        }
        val query = buildDefaultFilterQuery()
        decodeFilterOptions(
            responseText = readRadioBrowserText("${RadioBrowserApi.Path.COUNTRIES}?$query"),
            codeKeys = listOf(RadioBrowserApi.Json.ISO_3166_1, RadioBrowserApi.Json.COUNTRY_CODE),
        ).sortedByName().also { writeFilterCache("countries", it) }
    }

    suspend fun languages(): List<RadioBrowserFilterOption> = withContext(Dispatchers.IO) {
        readFilterCache("languages")?.takeIf { it.isFresh }?.let {
            return@withContext it.options.sortedByName()
        }
        val query = buildDefaultFilterQuery()
        decodeFilterOptions(
            responseText = readRadioBrowserText("${RadioBrowserApi.Path.LANGUAGES}?$query"),
            codeKeys = emptyList(),
        ).sortedByName().also { writeFilterCache("languages", it) }
    }

    suspend fun cachedCountries(): List<RadioBrowserFilterOption>? = withContext(Dispatchers.IO) {
        readFilterCache("countries")?.options?.sortedByName()
    }

    suspend fun cachedLanguages(): List<RadioBrowserFilterOption>? = withContext(Dispatchers.IO) {
        readFilterCache("languages")?.options?.sortedByName()
    }

    private fun readFilterCache(name: String): FilterCache? {
        val file = AtomicFile(File(filterCacheDirectory, "$name.json"))
        if (!file.baseFile.exists()) return null
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        val items = json.getJSONArray("options")
        val options = buildList {
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                add(RadioBrowserFilterOption(item.getString("name"), item.getString("code")))
            }
        }
        return FilterCache(json.getLong("savedAt"), options)
    }

    private fun writeFilterCache(name: String, options: List<RadioBrowserFilterOption>) {
        val items = JSONArray()
        options.forEach { option ->
            items.put(JSONObject().put("name", option.name).put("code", option.code))
        }
        val bytes = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("options", items)
            .toString().toByteArray(Charsets.UTF_8)
        val file = AtomicFile(File(filterCacheDirectory, "$name.json"))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun readRadioBrowserText(path: String): String {
        val errors = mutableListOf<String>()
        for (baseUrl in resolveBaseUrlCandidates()) {
            val result = runCatching { readText(URL("$baseUrl$path")) }
            if (result.isSuccess) {
                cachedBaseUrl = baseUrl
                return result.getOrThrow()
            }
            errors += "$baseUrl: ${result.exceptionOrNull()?.message ?: "request failed"}"
        }
        error("Radio Browser request failed: ${errors.joinToString("; ")}")
    }

    private fun resolveBaseUrlCandidates(): List<String> {
        cachedBaseUrl?.let { return listOf(it) + FALLBACK_BASE_URLS.filterNot { fallback -> fallback == it } }
        val indexServer = runCatching {
            val servers = JSONArray(
                readText(
                    URL("$SERVER_INDEX_URL${RadioBrowserApi.Path.SERVERS}"),
                    connectTimeout = 4_000,
                    readTimeout = 4_000,
                ),
            )
            servers.optJSONObject(0)
                ?.optString(RadioBrowserApi.Json.NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { "https://$it" }
        }.getOrNull()
        return (listOfNotNull(indexServer) + FALLBACK_BASE_URLS).distinct()
    }

    private fun readText(
        requestUrl: URL,
        connectTimeout: Int = 8_000,
        readTimeout: Int = 8_000,
    ): String {
        val connection = requestUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "FanTing Android")

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("Radio Browser returned HTTP $responseCode")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeStations(responseText: String): List<RadioBrowserStation> {
        val items = JSONArray(responseText)
        return buildList {
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                val title = item.optString(RadioBrowserApi.Json.NAME).trim()
                val streamUrl = item.optString(RadioBrowserApi.Json.URL_RESOLVED)
                    .trim()
                    .ifBlank { item.optString(RadioBrowserApi.Json.URL).trim() }
                if (title.isBlank() || streamUrl.isBlank()) {
                    return@repeat
                }
                add(
                    RadioBrowserStation(
                        stationUuid = item.optString(RadioBrowserApi.Json.STATION_UUID).trim(),
                        title = title,
                        streamUrl = streamUrl,
                        tags = item.optString(RadioBrowserApi.Json.TAGS)
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase() },
                        countryCode = item.optString(RadioBrowserApi.Json.COUNTRY_CODE).trim(),
                        codec = item.optString(RadioBrowserApi.Json.CODEC).trim(),
                        bitrate = item.optInt(RadioBrowserApi.Json.BITRATE, 0),
                        imageUrl = item.optString(RadioBrowserApi.Json.FAVICON).trim().takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun decodeFilterOptions(
        responseText: String,
        codeKeys: List<String>,
    ): List<RadioBrowserFilterOption> {
        val items = JSONArray(responseText)
        return buildList {
            repeat(items.length()) { index ->
                val item = items.optJSONObject(index) ?: return@repeat
                val name = item.optString(RadioBrowserApi.Json.NAME).trim()
                if (name.isBlank()) return@repeat
                add(
                    RadioBrowserFilterOption(
                        name = name,
                        code = codeKeys.firstNotNullOfOrNull { key ->
                            item.optString(key).trim().takeIf { it.isNotBlank() }
                        }.orEmpty(),
                    ),
                )
            }
        }
    }

    /**
     * 按 App 内生效语言的常规顺序排名称（简中拼音 / 繁中笔画 / 其它语言默认）。
     *
     * 缓存里存的是上次排序结果，语言切过或规则改过就不对了，所以读取路径上也排一次；
     * 列表规模只有几十到几百条，代价可忽略。
     */
    private fun List<RadioBrowserFilterOption>.sortedByName(): List<RadioBrowserFilterOption> {
        val comparator = nameComparator(appContext.resources.configuration.locales[0])
        return sortedWith(compareBy(comparator) { it.name })
    }

    private fun buildQuery(params: List<Pair<String, String>>): String {
        return params.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private fun buildDefaultFilterQuery(): String {
        return buildQuery(
            listOf(
                RadioBrowserApi.Query.HIDE_BROKEN to RadioBrowserApi.Value.TRUE,
                RadioBrowserApi.Query.ORDER to RadioBrowserApi.Sort.STATION_COUNT,
                RadioBrowserApi.Query.REVERSE to RadioBrowserApi.Value.TRUE,
            ),
        )
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private companion object {
        const val SERVER_INDEX_URL = "https://all.api.radio-browser.info"
        const val MAX_URL_VARIANTS = 8
        val FALLBACK_BASE_URLS = listOf(
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
        )

        /** 同一路流在国内常见的多套域名（只列已知同源，避免瞎猜） */
        val HOST_ALIASES = mapOf(
            "lhttp.qingting.fm" to listOf("lhttp.qingting.fm", "lhttp.qtfm.cn", "lhttp-hw.qtfm.cn"),
            "lhttp.qtfm.cn" to listOf("lhttp.qtfm.cn", "lhttp.qingting.fm", "lhttp-hw.qtfm.cn"),
            "lhttp-hw.qtfm.cn" to listOf("lhttp-hw.qtfm.cn", "lhttp.qtfm.cn", "lhttp.qingting.fm"),
            "ngcdn001.cnr.cn" to listOf("ngcdn001.cnr.cn", "ngcdn002.cnr.cn"),
            "ngcdn002.cnr.cn" to listOf("ngcdn002.cnr.cn", "ngcdn001.cnr.cn"),
        )

        val BITRATE_URL_REGEX = Regex("(?i)^(.*/live/\\d+/)(64|128)k\\.mp3$")
        val CHANNEL_ID_REGEX = Regex("/live/(\\d+)(?:/|$)")
        val NAME_BRACKETS_REGEX = Regex("[（(\\[【].*?[）)\\]】]")
        val NAME_FREQUENCY_REGEX = Regex("(?i)(fm|am)\\s*\\d+(\\.\\d+)?")
        val NAME_PREFIX_REGEX =
            Regex("(?i)^(cnr[\\s_-]*\\d*|cnn[\\s_-]*\\d*|中央广播电视总台|中国国际广播电台)\\s*")
        val NAME_SEPARATOR_REGEX = Regex("[\\s\\-_·•/\\\\,，。.、:：]+")
    }
}
