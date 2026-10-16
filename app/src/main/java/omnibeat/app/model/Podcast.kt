package omnibeat.app.model

const val PODCAST_FEED_URL_MAX_LENGTH = 2048
const val PODCAST_TITLE_MAX_LENGTH = 200

/**
 * 一个播客单集。
 *
 * [audioUrl] 是**已经选好的可播放地址**：解析时优先取自建源的 `gdtv:hls`（合并 VOD 播放列表，
 * 可拖动且不占服务端带宽），拿不到才退回标准 `enclosure`。播放链路不需要再判断来源。
 */
data class PodcastEpisode(
    val guid: String,
    val title: String,
    val audioUrl: String,
    val publishedAtMs: Long,
    val durationSec: Int,
)

/**
 * 一个播客订阅（含最近一次拉取到的期数列表）。
 *
 * 与 [Station] 分开存放：播客是「一个订阅含多期」，塞进电台库会污染电台列表、
 * 排序、自定义顺序与导入导出。
 */
data class PodcastSubscription(
    val feedUrl: String,
    val title: String,
    val imageUrl: String? = null,
    val lastFetchedAtMs: Long = 0L,
    val episodes: List<PodcastEpisode> = emptyList(),
)

/** 解析结果：订阅元数据 + 期数列表（顺序由解析层保证：按期数倒序） */
data class ParsedFeed(
    val title: String,
    val imageUrl: String?,
    val episodes: List<PodcastEpisode>,
)
