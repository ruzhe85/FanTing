package omnibeat.app.model

import java.util.Locale

/** 播客订阅列表的排序方式；「自定义」进入拖动排序，顺序存于 podcastCustomOrder */
enum class PodcastSortMode {
    LastUpdated,
    Title,
    Custom,
}

data class PodcastSortState(
    val mode: PodcastSortMode = PodcastSortMode.LastUpdated,
    val ascending: Boolean = false,
)

fun customSortedPodcasts(
    source: List<PodcastSubscription>,
    customOrder: List<String>,
): List<PodcastSubscription> {
    if (customOrder.isEmpty()) {
        return source
    }
    val sourceByFeedUrl = source.associateBy { it.feedUrl }
    val ordered = customOrder.mapNotNull(sourceByFeedUrl::get)
    val orderedKeys = ordered.mapTo(mutableSetOf()) { it.feedUrl }
    return ordered + source.filter { it.feedUrl !in orderedKeys }
}

/**
 * [locale] 只用于名称排序：按该语言的常规排序法比较（简中拼音 / 繁中笔画 / 其它语言默认），
 * 传 `resources.configuration.locales[0]`，即 App 内语言覆盖后的生效语言。
 */
fun sortedPodcasts(
    source: List<PodcastSubscription>,
    sortState: PodcastSortState,
    customOrder: List<String>,
    locale: Locale,
): List<PodcastSubscription> {
    return when (sortState.mode) {
        PodcastSortMode.Custom -> customSortedPodcasts(source, customOrder)
        PodcastSortMode.LastUpdated -> {
            // 按「最新一期发布时间」排，而不是本地检查时间：后者每次自动刷新都会变，
            // 排序就成了「谁先被检查到谁在前」，没有意义。
            // 先给每个订阅算一次再比较，避免排序过程中反复遍历整份期数列表。
            val updatedAt = source.associate { it.feedUrl to it.latestUpdatedAtMs }
            fun updatedAtOf(subscription: PodcastSubscription): Long =
                updatedAt[subscription.feedUrl] ?: 0L
            if (sortState.ascending) {
                source.sortedBy { updatedAtOf(it) }
            } else {
                source.sortedByDescending { updatedAtOf(it) }
            }
        }
        PodcastSortMode.Title -> {
            // 按语言习惯比较：中文拼音 / 繁中笔画，不再按 UTF-16 码点
            val sorted = source.sortedWith(compareBy(nameComparator(locale)) { it.title })
            if (sortState.ascending) sorted else sorted.asReversed()
        }
    }
}
