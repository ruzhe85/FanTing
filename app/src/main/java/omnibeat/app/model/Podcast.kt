package omnibeat.app.model

const val PODCAST_FEED_URL_MAX_LENGTH = 2048
const val PODCAST_TITLE_MAX_LENGTH = 200

/** 单集倍速的可选项（面板菜单按此顺序展示） */
val PODCAST_PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

const val PODCAST_DEFAULT_PLAYBACK_SPEED = 1.0f

/** 自动刷新间隔的可选项（分钟）：10 分钟 / 1 小时 / 6 小时 / 每天，设置下拉按此顺序展示 */
val PODCAST_REFRESH_INTERVAL_OPTIONS = listOf(10, 60, 360, 1440)

/** 自动刷新间隔默认值（分钟），与旧版硬编码的 10 分钟一致 */
const val PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES = 10

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
    /** 断点续播位置（毫秒）。听完的期会归零，下次点开即从头（符合"重听"直觉） */
    val positionMs: Long = 0L,
    /** 是否已听到结尾 */
    val completed: Boolean = false,
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
