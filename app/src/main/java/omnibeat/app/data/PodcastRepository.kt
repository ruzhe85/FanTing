package omnibeat.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES
import omnibeat.app.model.PODCAST_DEFAULT_SEARCH_REGION
import omnibeat.app.model.PODCAST_REFRESH_FAILURE_REASON_MAX_LENGTH
import omnibeat.app.model.PODCAST_PLAYBACK_SPEEDS
import omnibeat.app.model.PODCAST_REFRESH_INTERVAL_OPTIONS
import omnibeat.app.model.PODCAST_SEARCH_REGIONS
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastListLayout
import omnibeat.app.model.PodcastSortMode
import omnibeat.app.model.PodcastSortState
import omnibeat.app.model.PodcastSubscription
import omnibeat.app.model.isSupportedChartRegion
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 播客订阅单独一个 DataStore 文件（`podcasts.preferences_pb`）。
 *
 * 为什么不复用 `StationRepository` 的 `stations`：一是键空间会互相干扰，二是电台库的
 * 「整表覆盖写 + 导入导出」语义面对「一个订阅含多期」会变得很难受。
 * 读写风格与 `StationRepository` 保持一致：手写 JSON、整表覆盖写、解码失败整体降级为空列表。
 *
 * 除了订阅本身，这里还存这些与播客有关的状态：
 *   - 每期的续播位置与听完标记（随订阅一起进 JSON）
 *   - 播放倍速（单独的 Float 键）
 *   - 刷新偏好：启动时 / 进入列表时自动刷新开关 + 刷新间隔
 * 因为 DataStore 每次写都是整文件重写，进度只在暂停 / 切集 / 退出时更新。
 */
private val Context.podcastDataStore by preferencesDataStore(name = "podcasts")
private val podcastsJsonKey = stringPreferencesKey("podcasts_json")
private val playbackSpeedKey = floatPreferencesKey("podcast_playback_speed")
private val autoRefreshOnStartKey = booleanPreferencesKey("podcast_auto_refresh_on_start")
private val autoRefreshOnEnterListKey = booleanPreferencesKey("podcast_auto_refresh_on_enter_list")
private val wifiOnlyDownloadKey = booleanPreferencesKey("podcast_wifi_only_download")
private val refreshIntervalMinutesKey = intPreferencesKey("podcast_refresh_interval_minutes")
private val searchRegionKey = stringPreferencesKey("podcast_search_region")
private val chartRegionKey = stringPreferencesKey("podcast_chart_region")
private val episodesOldestFirstKey = booleanPreferencesKey("podcast_episodes_oldest_first")
/** 旧版紧凑列表开关：只用于迁移，新键存在时忽略 */
private val compactListKey = booleanPreferencesKey("podcast_compact_list")
private val listLayoutKey = stringPreferencesKey("podcast_list_layout")
private val sortKey = stringPreferencesKey("podcast_sort")
private val customOrderKey = stringPreferencesKey("podcast_custom_order")

class PodcastRepository(private val context: Context) {
    val subscriptions: Flow<List<PodcastSubscription>> = context.podcastDataStore.data
        .map { preferences -> decodeSubscriptions(preferences[podcastsJsonKey].orEmpty()) }

    /** 订阅列表只展示未隐藏的；下载/收藏/播放仍使用包含 hidden 的全量列表 */
    val visibleSubscriptions: Flow<List<PodcastSubscription>> = subscriptions
        .map { list -> list.filterNot { it.hidden } }

    /** 单集播放倍速（默认 1.0×）；电台直播不使用这个值 */
    val playbackSpeed: Flow<Float> = context.podcastDataStore.data
        .map { preferences -> preferences[playbackSpeedKey] ?: PODCAST_DEFAULT_PLAYBACK_SPEED }

    /** 启动软件时自动刷新全部订阅（默认开） */
    val autoRefreshOnStart: Flow<Boolean> = context.podcastDataStore.data
        .map { preferences -> preferences[autoRefreshOnStartKey] ?: true }

    /** 进入播客列表页时自动刷新（默认开；按刷新间隔节流） */
    val autoRefreshOnEnterList: Flow<Boolean> = context.podcastDataStore.data
        .map { preferences -> preferences[autoRefreshOnEnterListKey] ?: true }

    /** 是否仅 Wi-Fi 下载（默认开）：移动数据下手动下载前先弹窗询问 */
    val wifiOnlyDownload: Flow<Boolean> = context.podcastDataStore.data
        .map { preferences -> preferences[wifiOnlyDownloadKey] ?: true }

    /** 自动刷新间隔（分钟，默认 10）；越界值在保存时夹回可选项 */
    val refreshIntervalMinutes: Flow<Int> = context.podcastDataStore.data
        .map { preferences ->
            preferences[refreshIntervalMinutesKey] ?: PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES
        }

    /** 在线搜索使用的 iTunes 商店地区（白名单之外回退美国区） */
    val searchRegion: Flow<String> = context.podcastDataStore.data
        .map { preferences -> normalizeSearchRegion(preferences[searchRegionKey]) }

    /** 热门发现上次手动选择的地区；null = 从未选过，由界面语言决定 */
    val chartRegion: Flow<String?> = context.podcastDataStore.data
        .map { preferences -> preferences[chartRegionKey]?.takeIf(::isSupportedChartRegion) }

    /** 期数列表是否「最旧在上」（默认 false：最新在上） */
    val episodesOldestFirst: Flow<Boolean> = context.podcastDataStore.data
        .map { preferences -> preferences[episodesOldestFirstKey] ?: false }

    /** 订阅列表显示方式；旧版紧凑开关作为迁移回退 */
    val listLayout: Flow<PodcastListLayout> = context.podcastDataStore.data
        .map { preferences ->
            when (preferences[listLayoutKey]) {
                PodcastListLayout.Compact.name -> PodcastListLayout.Compact
                PodcastListLayout.Cover.name -> PodcastListLayout.Cover
                PodcastListLayout.Standard.name -> PodcastListLayout.Standard
                else -> if (preferences[compactListKey] == true) {
                    PodcastListLayout.Compact
                } else {
                    PodcastListLayout.Standard
                }
            }
        }

    /** 订阅列表排序方式（默认按最近更新倒序） */
    val sortState: Flow<PodcastSortState> = context.podcastDataStore.data
        .map { preferences -> decodePodcastSortState(preferences[sortKey]) }

    /** 自定义拖动排序的 feedUrl 列表 */
    val customOrder: Flow<List<String>> = context.podcastDataStore.data
        .map { preferences -> decodeStringList(preferences[customOrderKey]) }

    /** 新增或覆盖（按 feedUrl 去重）：添加订阅与刷新后都用它写回 */
    suspend fun upsertSubscription(subscription: PodcastSubscription) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == subscription.feedUrl }
            val next = if (index == -1) {
                current + subscription
            } else {
                // 刷新时解析结果会整批换掉期数，而解析结果里没有用户状态：
                // 按 guid 把已存的续播位置、听完标记与收藏搬回来，否则每次刷新都会丢。
                val merged = subscription.withPreservedEpisodeState(current[index].episodes)
                current.toMutableList().also { it[index] = merged }
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 记录一次自动刷新失败的时间，作为节流基准的一部分。
     *
     * 只写这个字段，不动 [PodcastSubscription.lastFetchedAtMs]——那个是「上次拉取成功」，
     * 节流与备份合并都按它算。按 feedUrl 在库里定位，
     * 不用调用方的订阅快照整体覆盖，避免把刷新期间写入的播放进度写回旧值。
     */
    suspend fun markRefreshFailed(feedUrl: String, reason: String) {
        val now = System.currentTimeMillis()
        val reasonText = reason.trim()
            .take(PODCAST_REFRESH_FAILURE_REASON_MAX_LENGTH)
            .takeIf { it.isNotBlank() }
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == feedUrl }
            if (index == -1) return@edit
            val next = current.toMutableList().also {
                it[index] = it[index].copy(
                    lastRefreshFailedAtMs = now,
                    lastRefreshFailureReason = reasonText,
                )
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 用户点掉失败窄条：记下已阅时间，这次失败就不再出现
     * （重启、重新进入期数页都不出现；再次刷新失败会更新失败时间，重新提示）。
     */
    suspend fun dismissRefreshFailure(feedUrl: String) {
        val now = System.currentTimeMillis()
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == feedUrl }
            if (index == -1) return@edit
            val next = current.toMutableList().also {
                it[index] = it[index].copy(lastRefreshFailureSeenAtMs = now)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 一次性替换某个订阅（用于改标题 / 换订阅地址）。
     * 按 [previousFeedUrl] 定位，替换后**保持它在列表中的位置**，并沿用原有的播放进度
     * （按 guid 合并，因此换源后若 guid 相同，断点仍然有效）。
     */
    suspend fun replaceSubscription(previousFeedUrl: String, subscription: PodcastSubscription) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == previousFeedUrl }
            val previous = current.getOrNull(index)
            val merged = if (previous == null) {
                subscription
            } else {
                subscription.withPreservedEpisodeState(previous.episodes)
            }
            val next = if (index == -1) {
                current + merged
            } else {
                current.toMutableList().also { it[index] = merged }
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /** 保存单集播放倍速（越界值夹回可选项范围） */
    suspend fun savePlaybackSpeed(speed: Float) {
        val clamped = PODCAST_PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - speed) }
            ?: PODCAST_DEFAULT_PLAYBACK_SPEED
        context.podcastDataStore.edit { preferences ->
            if (preferences[playbackSpeedKey] != clamped) {
                preferences[playbackSpeedKey] = clamped
            }
        }
    }

    /** 启动软件时自动刷新全部订阅的开关 */
    suspend fun saveAutoRefreshOnStart(enabled: Boolean) {
        context.podcastDataStore.edit { preferences ->
            preferences[autoRefreshOnStartKey] = enabled
        }
    }

    /** 进入播客列表时自动刷新的开关 */
    suspend fun saveAutoRefreshOnEnterList(enabled: Boolean) {
        context.podcastDataStore.edit { preferences ->
            preferences[autoRefreshOnEnterListKey] = enabled
        }
    }

    /** 仅 Wi-Fi 下载开关 */
    suspend fun saveWifiOnlyDownload(enabled: Boolean) {
        context.podcastDataStore.edit { preferences ->
            preferences[wifiOnlyDownloadKey] = enabled
        }
    }

    /** 保存自动刷新间隔（分钟，越界值夹回最近的可选项） */
    suspend fun saveRefreshIntervalMinutes(minutes: Int) {
        val clamped = PODCAST_REFRESH_INTERVAL_OPTIONS.minByOrNull { kotlin.math.abs(it - minutes) }
            ?: PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES
        context.podcastDataStore.edit { preferences ->
            if (preferences[refreshIntervalMinutesKey] != clamped) {
                preferences[refreshIntervalMinutesKey] = clamped
            }
        }
    }

    /** 保存期数列表排序（false = 最新在上） */
    suspend fun saveEpisodesOldestFirst(oldestFirst: Boolean) {
        context.podcastDataStore.edit { preferences ->
            preferences[episodesOldestFirstKey] = oldestFirst
        }
    }

    /** 保存订阅列表显示方式 */
    suspend fun saveListLayout(layout: PodcastListLayout) {
        context.podcastDataStore.edit { preferences ->
            if (preferences[listLayoutKey] != layout.name) {
                preferences[listLayoutKey] = layout.name
            }
        }
    }

    /** 保存订阅列表排序方式 */
    suspend fun saveSortState(state: PodcastSortState) {
        context.podcastDataStore.edit { preferences ->
            preferences[sortKey] = "${state.mode.name}:${state.ascending}"
        }
    }

    /** 保存自定义拖动排序 */
    suspend fun saveCustomOrder(order: List<String>) {
        context.podcastDataStore.edit { preferences ->
            preferences[customOrderKey] = JSONArray(order).toString()
        }
    }

    /** 保存在线搜索的商店地区（只接受白名单值，非法值回退美国区） */
    suspend fun saveSearchRegion(region: String) {
        val normalized = normalizeSearchRegion(region)
        context.podcastDataStore.edit { preferences ->
            if (preferences[searchRegionKey] != normalized) {
                preferences[searchRegionKey] = normalized
            }
        }
    }

    /** 保存热门发现地区（只接受 6 个白名单值） */
    suspend fun saveChartRegion(region: String) {
        val normalized = region.trim().lowercase(Locale.ROOT)
        if (!isSupportedChartRegion(normalized)) return
        context.podcastDataStore.edit { preferences ->
            if (preferences[chartRegionKey] != normalized) {
                preferences[chartRegionKey] = normalized
            }
        }
    }

    /**
     * 记录某一期的播放进度。
     *
     * 只在**暂停 / 切集 / 退出**时调用：DataStore 每次写都是整文件重写，
     * 绝不能挂到每秒的进度上报上。听完（[completed]）时把位置归零，
     * 下次点开即从头开始（符合"重听"直觉）。
     */
    suspend fun updateEpisodeProgress(
        feedUrl: String,
        guid: String,
        positionMs: Long,
        completed: Boolean,
    ) {
        val normalizedPosition = if (completed) 0L else positionMs.coerceAtLeast(0L)
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val subscriptionIndex = current.indexOfFirst { it.feedUrl == feedUrl }
            if (subscriptionIndex == -1) return@edit
            val subscription = current[subscriptionIndex]
            val episodeIndex = subscription.episodes.indexOfFirst { it.guid == guid }
            if (episodeIndex == -1) return@edit
            val episode = subscription.episodes[episodeIndex]
            // 值没变化就不整表重写
            if (episode.positionMs == normalizedPosition && episode.completed == completed) {
                return@edit
            }
            val updatedEpisodes = subscription.episodes.toMutableList().also {
                it[episodeIndex] = episode.copy(
                    positionMs = normalizedPosition,
                    // 听过结尾就不再撤销：重听途中暂停只更新位置，不掉"已听完"标记
                    completed = completed || episode.completed,
                    // 备份合并靠这个时间戳判断哪边更新
                    stateUpdatedAtMs = System.currentTimeMillis(),
                )
            }
            val next = current.toMutableList().also {
                it[subscriptionIndex] = subscription.copy(episodes = updatedEpisodes)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 记录 / 清除某个节目的本地下载文件（下载完成与删除下载时调用）。
     * 与 [updateEpisodeProgress] 同一套写法：值没变化就不整表重写。
     */
    suspend fun setEpisodeLocalFile(
        feedUrl: String,
        guid: String,
        localFileName: String?,
    ) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val subscriptionIndex = current.indexOfFirst { it.feedUrl == feedUrl }
            if (subscriptionIndex == -1) return@edit
            val subscription = current[subscriptionIndex]
            val episodeIndex = subscription.episodes.indexOfFirst { it.guid == guid }
            if (episodeIndex == -1) return@edit
            val episode = subscription.episodes[episodeIndex]
            if (episode.localFileName == localFileName) return@edit
            val updatedEpisodes = subscription.episodes.toMutableList().also {
                it[episodeIndex] = episode.copy(localFileName = localFileName)
            }
            val next = current.toMutableList().also {
                it[subscriptionIndex] = subscription.copy(episodes = updatedEpisodes)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 清掉所有单集的 localFileName（「全部删除下载」用）。
     * 单次 edit 一次搞定——循环调 [setEpisodeLocalFile] 会是 N 次整文件重写。
     * 没有任何下载标记时提前返回，不写盘。
     */
    suspend fun clearAllLocalFiles() {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            if (current.none { subscription -> subscription.episodes.any { it.localFileName != null } }) {
                return@edit
            }
            val next = current.map { subscription ->
                if (subscription.episodes.none { it.localFileName != null }) {
                    subscription
                } else {
                    subscription.copy(
                        episodes = subscription.episodes.map { it.copy(localFileName = null) },
                    )
                }
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 收藏 / 取消收藏某一期。
     * 与 [updateEpisodeProgress] 同一套写法：值没变化就不整表重写。
     */
    suspend fun setEpisodeFavorite(
        feedUrl: String,
        guid: String,
        favorite: Boolean,
    ) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val subscriptionIndex = current.indexOfFirst { it.feedUrl == feedUrl }
            if (subscriptionIndex == -1) return@edit
            val subscription = current[subscriptionIndex]
            val episodeIndex = subscription.episodes.indexOfFirst { it.guid == guid }
            if (episodeIndex == -1) return@edit
            val episode = subscription.episodes[episodeIndex]
            if (episode.favorite == favorite) return@edit
            val updatedEpisodes = subscription.episodes.toMutableList().also {
                it[episodeIndex] = episode.copy(
                    favorite = favorite,
                    stateUpdatedAtMs = System.currentTimeMillis(),
                )
            }
            val next = current.toMutableList().also {
                it[subscriptionIndex] = subscription.copy(episodes = updatedEpisodes)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /**
     * 把 [previous] 里同 guid 的期状态搬到本期数上（新出现的期保持默认值）。
     * 保留四样：续播位置、听完标记、收藏、下载文件名 —— 刷新换掉的只是期数列表，
     * 这些是用户行为产生的，不能因为重新拉了一次 feed 就丢。
     */
    private fun PodcastSubscription.withPreservedEpisodeState(
        previous: List<PodcastEpisode>,
    ): PodcastSubscription {
        if (episodes.isEmpty() || previous.isEmpty()) return this
        val previousByGuid = previous.associateBy { it.guid }
        return copy(
            episodes = episodes.map { episode ->
                val saved = previousByGuid[episode.guid] ?: return@map episode
                val hasState = saved.positionMs != 0L || saved.completed ||
                    saved.favorite || saved.localFileName != null
                if (hasState) {
                    episode.copy(
                        positionMs = saved.positionMs,
                        completed = saved.completed,
                        favorite = saved.favorite,
                        stateUpdatedAtMs = saved.stateUpdatedAtMs,
                        localFileName = saved.localFileName,
                    )
                } else {
                    episode
                }
            },
        )
    }

    /**
     * 退订 = 隐藏 + 裁剪，不删除音频文件。
     *
     * 只保留有进度 / 完成 / 收藏 / 已下载的期数，隐藏记录因此只有几十 KB，
     * 不会让每次进度写盘都背上整份 1000 期 JSON。
     * [keepGuids] 用于保留仍在下载中的期，下载完成后还能写回 localFileName。
     */
    suspend fun hideSubscription(feedUrl: String, keepGuids: Set<String> = emptySet()) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == feedUrl }
            if (index == -1) return@edit
            val subscription = current[index]
            val keptEpisodes = subscription.episodes.filter { episode ->
                episode.positionMs > 0L ||
                    episode.completed ||
                    episode.favorite ||
                    episode.localFileName != null ||
                    episode.guid in keepGuids
            }
            val next = current.toMutableList().also {
                it[index] = subscription.copy(hidden = true, episodes = keptEpisodes)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /** 批量标记已听 / 未听：一次 edit 更新整组，进度归零 */
    suspend fun setEpisodesCompleted(feedUrl: String, guids: Set<String>, completed: Boolean) {
        if (guids.isEmpty()) return
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == feedUrl }
            if (index == -1) return@edit
            val subscription = current[index]
            val now = System.currentTimeMillis()
            val updatedEpisodes = subscription.episodes.map { episode ->
                if (episode.guid in guids) {
                    episode.copy(
                        positionMs = 0L,
                        completed = completed,
                        stateUpdatedAtMs = now,
                    )
                } else {
                    episode
                }
            }
            val next = current.toMutableList().also {
                it[index] = subscription.copy(episodes = updatedEpisodes)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    private fun encodeSubscriptions(subscriptions: List<PodcastSubscription>): String {
        return encodePodcastSubscriptions(subscriptions)
    }

    private fun decodeSubscriptions(subscriptionsJson: String): List<PodcastSubscription> {
        return decodePodcastSubscriptions(subscriptionsJson)
    }

    /** 备份恢复用：整表覆盖（调用方负责先算好合并 / 替换结果） */
    suspend fun restoreAll(subscriptions: List<PodcastSubscription>) {
        context.podcastDataStore.edit { preferences ->
            preferences[podcastsJsonKey] = encodeSubscriptions(subscriptions)
        }
    }
}

/** "Mode:ascending" ⇄ PodcastSortState；非法或缺失值回落默认（最近更新倒序） */
internal fun decodePodcastSortState(raw: String?): PodcastSortState {
    val parts = raw.orEmpty().split(':', limit = 2)
    val mode = PodcastSortMode.entries.firstOrNull { it.name == parts.getOrNull(0) }
        ?: PodcastSortMode.LastUpdated
    val ascending = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false
    return PodcastSortState(mode = mode, ascending = ascending)
}

private fun decodeStringList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val items = JSONArray(raw)
        buildList {
            repeat(items.length()) { index ->
                items.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())
}

/** 播客订阅列表 ⇄ JSON 文本；备份编解码与 DataStore 读写共用同一套格式 */
internal fun encodePodcastSubscriptions(subscriptions: List<PodcastSubscription>): String {
    val items = JSONArray()
    subscriptions.forEach { subscription ->
        val episodes = JSONArray()
        subscription.episodes.forEach { episode ->
            episodes.put(
                JSONObject()
                    .put("guid", episode.guid)
                    .put("title", episode.title)
                    .put("audioUrl", episode.audioUrl)
                    .put("publishedAtMs", episode.publishedAtMs)
                    .put("durationSec", episode.durationSec)
                    .put("positionMs", episode.positionMs)
                    .put("completed", episode.completed)
                    .put("favorite", episode.favorite)
                    .put("stateUpdatedAtMs", episode.stateUpdatedAtMs)
                    .put("localFileName", episode.localFileName.orEmpty()),
            )
        }
        items.put(
            JSONObject()
                .put("feedUrl", subscription.feedUrl)
                .put("title", subscription.title)
                .put("imageUrl", subscription.imageUrl.orEmpty())
                .put("imageUrlManual", subscription.imageUrlManual)
                .put("lastFetchedAtMs", subscription.lastFetchedAtMs)
                .put("lastRefreshFailedAtMs", subscription.lastRefreshFailedAtMs)
                .put("lastRefreshFailureReason", subscription.lastRefreshFailureReason.orEmpty())
                .put("lastRefreshFailureSeenAtMs", subscription.lastRefreshFailureSeenAtMs)
                .put("hidden", subscription.hidden)
                .put("episodes", episodes),
        )
    }
    return items.toString()
}

internal fun decodePodcastSubscriptions(subscriptionsJson: String): List<PodcastSubscription> {
    if (subscriptionsJson.isBlank()) {
        return emptyList()
    }
    return runCatching {
        val items = JSONArray(subscriptionsJson)
        buildList {
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                val feedUrl = item.optString("feedUrl").trim()
                val title = item.optString("title").trim()
                // 地址或标题为空视为坏数据：跳过这一条而不是整个订阅列表报废
                if (feedUrl.isNotEmpty() && title.isNotEmpty()) {
                    add(
                        PodcastSubscription(
                            feedUrl = feedUrl,
                            title = title,
                            imageUrl = item.optString("imageUrl").trim().takeIf { it.isNotBlank() },
                            imageUrlManual = item.optBoolean("imageUrlManual", false),
                            lastFetchedAtMs = item.optLong("lastFetchedAtMs", 0L),
                            // 旧数据没有这些字段：0 / null 表示从未失败过
                            lastRefreshFailedAtMs = item.optLong("lastRefreshFailedAtMs", 0L),
                            lastRefreshFailureReason = item.optString("lastRefreshFailureReason")
                                .trim().takeIf { it.isNotBlank() },
                            lastRefreshFailureSeenAtMs = item.optLong("lastRefreshFailureSeenAtMs", 0L),
                            hidden = item.optBoolean("hidden", false),
                            episodes = decodePodcastEpisodes(item.optJSONArray("episodes")),
                        ),
                    )
                }
            }
        }
    }.getOrElse {
        emptyList()
    }
}

internal fun decodePodcastEpisodes(episodesJson: JSONArray?): List<PodcastEpisode> {
    if (episodesJson == null) {
        return emptyList()
    }
    return buildList {
        repeat(episodesJson.length()) { index ->
            val item = episodesJson.optJSONObject(index) ?: return@repeat
            val audioUrl = item.optString("audioUrl").trim()
            val title = item.optString("title").trim()
            val guid = item.optString("guid").trim()
            // 没有可播地址的单集直接丢弃：点开也只会报错
            if (audioUrl.isNotEmpty() && title.isNotEmpty()) {
                add(
                    PodcastEpisode(
                        guid = guid.ifEmpty { audioUrl },
                        title = title,
                        audioUrl = audioUrl,
                        publishedAtMs = item.optLong("publishedAtMs", 0L),
                        durationSec = item.optInt("durationSec", 0),
                        // 旧数据没有这些字段：走默认值（无进度、未听完、无时间戳）
                        positionMs = item.optLong("positionMs", 0L),
                        completed = item.optBoolean("completed", false),
                        favorite = item.optBoolean("favorite", false),
                        stateUpdatedAtMs = item.optLong("stateUpdatedAtMs", 0L),
                        localFileName = item.optString("localFileName").trim()
                            .takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }
}

/** 地区只认白名单；大小写/空白差异归一到小写，其余一律退回默认区 */
private fun normalizeSearchRegion(region: String?): String {
    return region
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it in PODCAST_SEARCH_REGIONS }
        ?: PODCAST_DEFAULT_SEARCH_REGION
}
