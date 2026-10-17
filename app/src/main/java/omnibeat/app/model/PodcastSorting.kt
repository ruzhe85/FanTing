package omnibeat.app.model

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

fun sortedPodcasts(
    source: List<PodcastSubscription>,
    sortState: PodcastSortState,
    customOrder: List<String>,
): List<PodcastSubscription> {
    return when (sortState.mode) {
        PodcastSortMode.Custom -> customSortedPodcasts(source, customOrder)
        PodcastSortMode.LastUpdated -> if (sortState.ascending) {
            source.sortedBy { it.lastFetchedAtMs }
        } else {
            source.sortedByDescending { it.lastFetchedAtMs }
        }
        PodcastSortMode.Title -> {
            val sorted = source.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            if (sortState.ascending) sorted else sorted.asReversed()
        }
    }
}
