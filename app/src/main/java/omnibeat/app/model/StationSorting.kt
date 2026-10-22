package omnibeat.app.model

import java.util.Locale

fun customSortedStations(
    source: List<Station>,
    customOrder: List<String>,
): List<Station> {
    if (customOrder.isEmpty()) {
        return source
    }
    val sourceById = source.associateBy { it.id }
    val orderedStations = customOrder.mapNotNull(sourceById::get)
    val orderedIds = orderedStations.map { it.id }.toSet()
    return orderedStations + source.filterNot { it.id in orderedIds }
}

/**
 * [locale] 只用于名称排序：按该语言的常规排序法比较（简中拼音 / 繁中笔画 / 其它语言默认），
 * 传 `resources.configuration.locales[0]`，即 App 内语言覆盖后的生效语言。
 */
fun sortedStations(
    source: List<Station>,
    page: MainPage,
    sortState: StationSortState,
    customStationOrder: List<String>,
    customFavoriteOrder: List<String>,
    locale: Locale,
): List<Station> {
    return when (sortState.mode) {
        StationSortMode.Custom -> customSortedStations(
            source = source,
            customOrder = if (page == MainPage.Favorites) customFavoriteOrder else customStationOrder,
        )
        StationSortMode.DateAdded -> if (sortState.ascending) {
            source.sortedBy { it.dateAdded }
        } else {
            source.sortedByDescending { it.dateAdded }
        }
        StationSortMode.StationTitle -> {
            // 按语言习惯比较：中文拼音 / 繁中笔画，不再按 UTF-16 码点
            val sorted = source.sortedWith(compareBy(nameComparator(locale)) { it.title })
            if (sortState.ascending) sorted else sorted.asReversed()
        }
        StationSortMode.FavoritesFirst -> if (sortState.ascending) {
            source.sortedWith(
                compareBy<Station> { it.isFavorite }
                    .thenBy { it.dateAdded },
            )
        } else {
            source.sortedWith(
                compareByDescending<Station> { it.isFavorite }
                    .thenByDescending { it.dateAdded },
            )
        }
    }
}
