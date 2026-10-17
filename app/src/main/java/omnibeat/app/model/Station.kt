package omnibeat.app.model

const val STATION_TITLE_MAX_LENGTH = 100
const val STATION_STREAM_URL_MAX_LENGTH = 2048
const val STATION_TAGS_INPUT_MAX_LENGTH = 200
const val STATION_IMAGE_URL_MAX_LENGTH = 2048

data class Station(
    val id: String,
    val title: String,
    val streamUrl: String,
    val tags: List<String>,
    val imageUrl: String? = null,
    val isFavorite: Boolean = false,
    val dateAdded: String,
    /** true = 封面的地址是用户手动填的，自动查找/流内封面都不得覆盖它 */
    val imageUrlManual: Boolean = false,
    /** 通过在线搜索添加时记下的 Radio Browser stationuuid，用于按 uuid 精确回查封面 */
    val radioBrowserUuid: String? = null,
)

enum class StationSortMode {
    DateAdded,
    StationTitle,
    FavoritesFirst,
    Custom,
}

data class StationSortState(
    val mode: StationSortMode = StationSortMode.DateAdded,
    val ascending: Boolean = false,
)

/** 收藏 tab 的分段：电台收藏 / 播客单集收藏 */
enum class FavoritesSection { Stations, Episodes }

enum class MainPage {
    Stations,
    Favorites,
    Podcasts,
    ExportImport,
    Translation,
    SearchOnline,
    Settings,
    About;

    companion object {
        /**
         * 「电台区」的页：抽屉里「电台」入口的高亮范围（保持原语义，不含播客）。
         */
        val tabPages = listOf(Stations, Favorites)

        /**
         * 顶部 Tab 的全集（含播客），顺序即顶栏的顺序：收藏排在最后。
         * 实际可见性由 OmniBeatApp 过滤：收藏页可被设置隐藏，播客页始终显示。
         */
        val allTabPages = listOf(Stations, Podcasts, Favorites)
    }
}

data class StationReorderDraft(
    val page: MainPage,
    val stations: List<Station>,
)

data class StationEditorState(
    val stationIndex: Int?,
    val title: String,
    val streamUrl: String,
    val tags: String,
    val dateAdded: String?,
    val imageUrl: String? = null,
)
