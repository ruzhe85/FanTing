package omnibeat.app.podcast

import android.util.Xml
import omnibeat.app.model.ParsedFeed
import omnibeat.app.model.PodcastEpisode
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 播客 RSS 解析（零依赖：平台的 XmlPullParser + HttpURLConnection）。
 *
 * 只解析播客订阅真正需要的字段，不做「兼容全世界」的通用解析：
 * 订阅源无非两类 —— 本项目的自建服务（RSS 2.0 + `itunes:` + 自定义 `gdtv:`），
 * 以及标准播客。两类都用同一条代码路径，区别只在音源地址的挑选上：
 *
 *   优先 `<gdtv:hls>`：自建服务提供的合并 VOD 播放列表（分片是 CDN 绝对地址），
 *                     播放器拿到完整时间轴 → **进度可拖动**，且音频不经过服务端。
 *   兜底 `<enclosure>`：标准播客的单集文件（mp3/m4a，真文件、有长度）同样可拖；
 *                     自建服务的 enclosure 是现剥现推的裸音频流，没有长度也不支持
 *                     Range，Media3 建不出 seek map → 拖不动，所以只在没有 hls 时用。
 */
object RssParser {
    private const val USER_AGENT = "OmniBeatP Android"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    private val PUB_DATE_FORMATTERS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm Z", Locale.US),
    )

    /** 拉取并解析；失败抛出带可展示文案的异常，由 UI 提示 */
    suspend fun load(feedUrl: String): ParsedFeed {
        val normalized = feedUrl.trim()
        if (normalized.isEmpty()) {
            throw IOException("请填写订阅地址")
        }
        val connection = open(normalized)
        val body = try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("服务器返回 HTTP $code")
            }
            connection.inputStream.use { stream ->
                stream.readBytes().toString(charsetOf(connection.contentType))
            }
        } finally {
            connection.disconnect()
        }
        return parse(body, normalized)
    }

    /** 纯解析，便于离线验证 */
    fun parse(xml: String, baseUrl: String): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedImage: String? = null
        val episodes = mutableListOf<PodcastEpisode>()

        var inItem = false
        var inImageTag = false
        var itemTitle = ""
        var itemGuid = ""
        var itemPubDate = ""
        var itemDuration = ""
        var itemEnclosure: String? = null
        var itemHls: String? = null

        fun flushItem() {
            val audioUrl = (itemHls ?: itemEnclosure)?.let { absolutize(it, baseUrl) }
            val title = itemTitle.trim()
            if (!audioUrl.isNullOrBlank() && title.isNotEmpty()) {
                episodes += PodcastEpisode(
                    guid = itemGuid.trim().ifEmpty { audioUrl },
                    title = title,
                    audioUrl = audioUrl,
                    publishedAtMs = parsePubDateMillis(itemPubDate),
                    durationSec = parseDurationSeconds(itemDuration),
                )
            }
            itemTitle = ""
            itemGuid = ""
            itemPubDate = ""
            itemDuration = ""
            itemEnclosure = null
            itemHls = null
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (localName(parser.name)) {
                        "item" -> {
                            inItem = true
                            itemTitle = ""
                            itemGuid = ""
                            itemPubDate = ""
                            itemDuration = ""
                            itemEnclosure = null
                            itemHls = null
                        }

                        "title" -> {
                            val text = parser.nextText()
                            if (inItem) itemTitle = text else if (feedTitle.isEmpty()) feedTitle = text.trim()
                        }

                        "guid" -> if (inItem) itemGuid = parser.nextText()

                        "pubDate" -> if (inItem) itemPubDate = parser.nextText()

                        "duration" -> if (inItem) itemDuration = parser.nextText()

                        "enclosure" -> if (inItem) {
                            itemEnclosure = itemEnclosure
                                ?: parser.getAttributeValue(null, "url")?.takeIf { it.isNotBlank() }
                        }

                        // gdtv:hls（自建服务的可拖动地址）；标准播客里不存在这个标签
                        "hls" -> if (inItem) {
                            itemHls = itemHls
                                ?: parser.getAttributeValue(null, "url")?.takeIf { it.isNotBlank() }
                        }

                        "image" -> if (!inItem) {
                            val href = parser.getAttributeValue(null, "href") ?: parser.getAttributeValue(ITUNES_NS, "href")
                            if (!href.isNullOrBlank()) {
                                if (feedImage == null) feedImage = href.trim()
                            } else {
                                inImageTag = true
                            }
                        }

                        "url" -> if (!inItem && inImageTag) {
                            val text = parser.nextText().trim()
                            if (feedImage == null && text.isNotEmpty()) feedImage = text
                            inImageTag = false
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (localName(parser.name)) {
                    "item" -> {
                        if (inItem) flushItem()
                        inItem = false
                    }

                    "image" -> inImageTag = false
                }
            }
            event = parser.next()
        }

        return ParsedFeed(
            title = feedTitle.ifEmpty { baseUrl },
            imageUrl = feedImage,
            // 倒序：最新一期在最上面
            episodes = episodes.sortedByDescending { it.publishedAtMs },
        )
    }

    private fun open(feedUrl: String): HttpURLConnection {
        val connection = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
        }
        return connection
    }

    private fun charsetOf(contentType: String?): Charset {
        val match = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?: return Charsets.UTF_8
        return runCatching { Charset.forName(match) }.getOrDefault(Charsets.UTF_8)
    }

    private fun localName(name: String?): String {
        val raw = name.orEmpty()
        val colon = raw.lastIndexOf(':')
        return if (colon >= 0) raw.substring(colon + 1) else raw
    }

    /** 相对地址补全（部分播客的 enclosure 是相对路径） */
    private fun absolutize(url: String, baseUrl: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        return runCatching { URI(baseUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    /** `itunes:duration` 三种写法：纯秒 / MM:SS / HH:MM:SS（可能有小数） */
    private fun parseDurationSeconds(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        if (!text.contains(':')) {
            return text.toDoubleOrNull()?.toInt() ?: 0
        }
        val parts = text.split(':')
        val seconds = parts.map { it.trim().toDoubleOrNull() ?: 0.0 }
        return when (seconds.size) {
            2 -> (seconds[0] * 60 + seconds[1]).toInt()
            3 -> (seconds[0] * 3600 + seconds[1] * 60 + seconds[2]).toInt()
            else -> 0
        }
    }

    /** pubDate 是 RFC 822（如 `Thu, 15 Oct 2026 08:00:00 GMT`），解析不了就记 0 */
    private fun parsePubDateMillis(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0L
        PUB_DATE_FORMATTERS.forEach { formatter ->
            runCatching { return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli() }
        }
        return 0L
    }
}
