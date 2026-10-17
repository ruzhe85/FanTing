package omnibeat.app.podcast

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import omnibeat.app.model.ParsedFeed
import omnibeat.app.model.PodcastEpisode
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    /** 详情页取简介只读头部这么多字节：频道信息通常在前几 KB，64KB 足够覆盖超长简介与多语言声明 */
    private const val SUMMARY_MAX_BYTES = 64 * 1024

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
        // 网络与解析都在 IO 线程：feed 可能几百 KB，别占主线程
        return withContext(Dispatchers.IO) {
            val connection = open(normalized)
            val body = try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("服务器返回 HTTP $code")
                }
                val charset = charsetOf(connection.contentType)
                connection.inputStream.use { stream -> readFully(stream, charset) }
            } finally {
                connection.disconnect()
            }
            parse(body, normalized)
        }
    }

    /**
     * 只拉订阅源开头的 [maxBytes] 字节，提取频道简介（`itunes:summary`，回退 `description`）。
     *
     * 为什么要单独做：iTunes 的 search / lookup 响应里已经没有简介字段，而详情页为了看简介
     * 去下载整本 feed 太重（机核这类节目一本 2.7MB）。频道简介一定在第一个 `<item>` 之前，
     * 取头部一小段即可；拿不到就返回 null，由 UI 显示「暂无简介」。
     */
    suspend fun loadSummary(feedUrl: String, maxBytes: Int = SUMMARY_MAX_BYTES): String? {
        val normalized = feedUrl.trim()
        if (normalized.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val connection = open(normalized)
            // 只取头部：支持 Range 的服务器返回 206 且只传一小段；
            // 不支持的服务器会返回全量，但读到 maxBytes 就主动断开，不会把整本下完
            connection.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            val body = try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IOException("服务器返回 HTTP $code")
                }
                val contentType = connection.contentType
                val raw = connection.inputStream.use { stream -> readLimited(stream, maxBytes) }
                raw.toString(charsetOf(contentType, raw))
            } finally {
                connection.disconnect()
            }
            extractChannelSummary(body)
        }
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

    /**
     * 分块读响应体，每块之间检查协程是否已取消：用户在「添加播客」里点取消后
     * 不必等这次读超时（20s）结束才退出。阻塞在单次 read 上时仍受 readTimeout 约束。
     */
    private suspend fun readFully(stream: InputStream, charset: Charset): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(chunk)
            if (read < 0) break
            bytes.write(chunk, 0, read)
        }
        return bytes.toString(charset.name())
    }

    /** 读最多 [maxBytes] 字节就停：服务器忽略 Range 时也不会把整本 feed 下完 */
    private suspend fun readLimited(stream: InputStream, maxBytes: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            bytes.write(chunk, 0, read)
            remaining -= read
        }
        return bytes.toByteArray()
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

    private fun charsetOf(contentType: String?, preview: ByteArray? = null): Charset {
        Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?.let { return it }
        // HTTP 头没写 charset 时退到 XML 声明：GBK 之类的老源只能从这里看出来
        if (preview != null && preview.isNotEmpty()) {
            val head = String(preview, 0, minOf(preview.size, 256), Charsets.ISO_8859_1)
            Regex("encoding=[\"']([A-Za-z0-9_\\-]+)[\"']", RegexOption.IGNORE_CASE)
                .find(head)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
                ?.let { return it }
        }
        return Charsets.UTF_8
    }

    /** 从 feed 头部取频道简介：只看第一个 `<item>` 之前，避免抓到单集描述 */
    private fun extractChannelSummary(xml: String): String? {
        val channelStart = xml.indexOf("<channel", ignoreCase = true).coerceAtLeast(0)
        val itemStart = xml.indexOf("<item", channelStart, ignoreCase = true)
            .let { if (it < 0) xml.length else it }
        if (itemStart <= channelStart) return null
        val head = xml.substring(channelStart, itemStart)
        val raw = tagText(head, "itunes:summary") ?: tagText(head, "description") ?: return null
        return cleanFeedText(raw).takeIf { it.isNotBlank() }
    }

    private fun tagText(xml: String, tag: String): String? {
        val pattern = Regex(
            "<$tag(?:\\s[^>]*)?>(.*?)</$tag\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.find(xml)?.groupValues?.getOrNull(1)
    }

    /** 去 CDATA、去 HTML 标签、还原常见实体，合并多余空白 */
    private fun cleanFeedText(raw: String): String {
        val withoutCdata = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
            .replace(raw) { match -> match.groupValues[1] }
        return withoutCdata
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
                codePointText(match.groupValues[1].toIntOrNull(16))
            }
            .replace(Regex("&#(\\d+);")) { match ->
                codePointText(match.groupValues[1].toIntOrNull())
            }
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun codePointText(codePoint: Int?): String {
        if (codePoint == null || codePoint <= 0) return ""
        return runCatching { String(Character.toChars(codePoint)) }.getOrDefault("")
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
