package omnibeat.app.model

import java.util.Locale

const val PODCAST_FEED_URL_MAX_LENGTH = 2048
const val PODCAST_TITLE_MAX_LENGTH = 200
const val PODCAST_IMAGE_URL_MAX_LENGTH = 2048

/** 单集倍速的可选项（面板菜单按此顺序展示） */
val PODCAST_PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

const val PODCAST_DEFAULT_PLAYBACK_SPEED = 1.0f

/** 自动刷新间隔的可选项（分钟）：10 分钟 / 1 小时 / 6 小时 / 每天，设置下拉按此顺序展示 */
val PODCAST_REFRESH_INTERVAL_OPTIONS = listOf(10, 60, 360, 1440)

/** 自动刷新间隔默认值（分钟），与旧版硬编码的 10 分钟一致 */
const val PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES = 10

/**
 * 在线搜索的 iTunes 商店地区（只保留常用大区，避免设置里一长串）。
 * 值直接作为 iTunes Search API 的 `country` 参数；顺序即设置菜单顺序。
 */
val PODCAST_SEARCH_REGIONS = listOf("us", "cn", "tw", "hk", "jp", "kr", "gb", "de")

/** 在线搜索默认使用美国区：目录最全，中文播客也能搜到 */
const val PODCAST_DEFAULT_SEARCH_REGION = "us"

/**
 * 在线搜索一次最多取多少条（iTunes 上限 200）。
 * iTunes 不支持分页（offset 无效），所以首屏一次取全，再在本地分批展开。
 */
const val PODCAST_SEARCH_FETCH_LIMIT = 200

/** 在线搜索每次「加载更多」展开的条数 */
const val PODCAST_SEARCH_PAGE_SIZE = 50

/**
 * 订阅地址的归一化 key，用于判断「搜索到的播客是否已订阅」。
 *
 * iTunes 给出的 feedUrl 常与用户当初粘贴的只差协议或结尾斜杠，
 * 按原串比较会把已订阅误判成未订阅，所以这里统一去掉协议、结尾斜杠并忽略大小写。
 */
fun podcastFeedKey(feedUrl: String): String {
    return feedUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .lowercase(Locale.ROOT)
}

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
    /** 是否收藏：刷新换期数时按 guid 保留，删订阅才随之消失 */
    val favorite: Boolean = false,
    /**
     * 已下载的本地文件名（null = 未下载）。
     * 目录固定为 `filesDir/podcast_audio/`，只存文件名不存全路径——
     * 换设备/清数据后文件不在，播放与导出处按存在性兜底回网络。
     */
    val localFileName: String? = null,
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
    /** true = 封面是用户手动填的，刷新订阅源时不得覆盖 */
    val imageUrlManual: Boolean = false,
    /** 是否置顶了这个订阅（与单集收藏分开：这里只控制订阅列表排序） */
    val pinned: Boolean = false,
)

/** 解析结果：订阅元数据 + 期数列表（顺序由解析层保证：按期数倒序） */
data class ParsedFeed(
    val title: String,
    val imageUrl: String?,
    val episodes: List<PodcastEpisode>,
)
