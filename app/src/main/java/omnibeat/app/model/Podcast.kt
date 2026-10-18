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
 * 热门发现一次取多少条。
 * 实测 100 条在各地区都稳定；200 条会被 Apple 拒绝或超时，不要调高。
 */
const val PODCAST_CHART_LIMIT = 100

/** 热门榜单本地缓存有效期：6 小时。Apple 全天多次刷新，App 只在打开页面且过期时拉一次 */
const val PODCAST_CHART_CACHE_TTL_MS = 6 * 60 * 60 * 1000L

/**
 * 热门发现支持的地区，顺序即发现页地区菜单顺序。
 *
 * 只有 6 个：中/港/台/日/美/英；其余语言一律落到美国区——
 * 榜单只是发现入口，不追求覆盖所有 storefront；真有需求再加一个码即可。
 */
val PODCAST_CHART_REGIONS = listOf("cn", "hk", "tw", "jp", "us", "gb")

private const val PODCAST_CHART_FALLBACK_REGION = "us"

/** 地区白名单校验：DataStore 里的旧值、手写值都要过这一关 */
fun isSupportedChartRegion(region: String): Boolean = region in PODCAST_CHART_REGIONS

/**
 * 按界面语言决定榜单地区：
 *   - 中文：港/澳 → hk，台/繁体 → tw，其余简中 → cn
 *   - 日语 → jp；英语（英式）→ gb；其余英语 → us
 *   - 其它语言（俄/德/法/韩…）→ us
 *
 * [locale] 传 `resources.configuration.locales[0]`，即 App 内语言覆盖后的生效语言。
 */
fun podcastChartRegionFor(locale: Locale): String {
    val language = locale.language.lowercase(Locale.ROOT)
    val country = locale.country.uppercase(Locale.ROOT)
    return when (language) {
        "zh" -> when {
            country == "HK" || country == "MO" -> "hk"
            country == "TW" || locale.script == "Hant" -> "tw"
            else -> "cn"
        }
        "ja" -> "jp"
        "en" -> if (country == "GB") "gb" else PODCAST_CHART_FALLBACK_REGION
        else -> PODCAST_CHART_FALLBACK_REGION
    }
}

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
     * 播放状态（位置 / 听完 / 收藏）最后一次变更的时间。
     *
     * 备份合并时用它判断「哪一边更新」：本地今天听过、备份是昨天导出的，
     * 就以本地为准；反之亦然。旧数据没有这个字段时为 0，退回保守规则。
     */
    val stateUpdatedAtMs: Long = 0L,
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
)

/** 解析结果：订阅元数据 + 期数列表（顺序由解析层保证：按期数倒序） */
data class ParsedFeed(
    val title: String,
    val imageUrl: String?,
    val episodes: List<PodcastEpisode>,
)
