package omnibeat.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_PLAYBACK_SPEEDS
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastSubscription
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播客订阅单独一个 DataStore 文件（`podcasts.preferences_pb`）。
 *
 * 为什么不复用 `StationRepository` 的 `stations`：一是键空间会互相干扰，二是电台库的
 * 「整表覆盖写 + 导入导出」语义面对「一个订阅含多期」会变得很难受。
 * 读写风格与 `StationRepository` 保持一致：手写 JSON、整表覆盖写、解码失败整体降级为空列表。
 *
 * 除了订阅本身，这里还存两样与单集播放有关的状态：
 *   - 每期的续播位置与听完标记（随订阅一起进 JSON）
 *   - 播放倍速（单独的 Float 键）
 * 因为 DataStore 每次写都是整文件重写，进度只在暂停 / 切集 / 退出时更新。
 */
private val Context.podcastDataStore by preferencesDataStore(name = "podcasts")
private val podcastsJsonKey = stringPreferencesKey("podcasts_json")
private val playbackSpeedKey = floatPreferencesKey("podcast_playback_speed")

class PodcastRepository(private val context: Context) {
    val subscriptions: Flow<List<PodcastSubscription>> = context.podcastDataStore.data
        .map { preferences -> decodeSubscriptions(preferences[podcastsJsonKey].orEmpty()) }

    /** 单集播放倍速（默认 1.0×）；电台直播不使用这个值 */
    val playbackSpeed: Flow<Float> = context.podcastDataStore.data
        .map { preferences -> preferences[playbackSpeedKey] ?: PODCAST_DEFAULT_PLAYBACK_SPEED }

    /** 新增或覆盖（按 feedUrl 去重）：添加订阅与刷新后都用它写回 */
    suspend fun upsertSubscription(subscription: PodcastSubscription) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == subscription.feedUrl }
            val next = if (index == -1) {
                current + subscription
            } else {
                // 刷新时解析结果会整批换掉期数，而解析结果里没有进度：
                // 按 guid 把已存的续播位置与听完标记搬回来，否则每次刷新都会丢进度。
                val merged = subscription.withPreservedProgress(current[index].episodes)
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
                )
            }
            val next = current.toMutableList().also {
                it[subscriptionIndex] = subscription.copy(episodes = updatedEpisodes)
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
    }

    /** 把 [previous] 里同 guid 的进度搬到本期数上（新出现的期保持默认值） */
    private fun PodcastSubscription.withPreservedProgress(
        previous: List<PodcastEpisode>,
    ): PodcastSubscription {
        if (episodes.isEmpty() || previous.isEmpty()) return this
        val progressByGuid = previous.associateBy { it.guid }
        return copy(
            episodes = episodes.map { episode ->
                val saved = progressByGuid[episode.guid] ?: return@map episode
                if (saved.positionMs == 0L && !saved.completed) {
                    episode
                } else {
                    episode.copy(positionMs = saved.positionMs, completed = saved.completed)
                }
            },
        )
    }

    suspend fun removeSubscription(feedUrl: String) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            preferences[podcastsJsonKey] =
                encodeSubscriptions(current.filterNot { it.feedUrl == feedUrl })
        }
    }

    private fun encodeSubscriptions(subscriptions: List<PodcastSubscription>): String {
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
                        .put("completed", episode.completed),
                )
            }
            items.put(
                JSONObject()
                    .put("feedUrl", subscription.feedUrl)
                    .put("title", subscription.title)
                    .put("imageUrl", subscription.imageUrl.orEmpty())
                    .put("lastFetchedAtMs", subscription.lastFetchedAtMs)
                    .put("episodes", episodes),
            )
        }
        return items.toString()
    }

    private fun decodeSubscriptions(subscriptionsJson: String): List<PodcastSubscription> {
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
                                lastFetchedAtMs = item.optLong("lastFetchedAtMs", 0L),
                                episodes = decodeEpisodes(item.optJSONArray("episodes")),
                            ),
                        )
                    }
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun decodeEpisodes(episodesJson: JSONArray?): List<PodcastEpisode> {
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
                            // 旧数据没有这两个字段：走默认值（无进度、未听完）
                            positionMs = item.optLong("positionMs", 0L),
                            completed = item.optBoolean("completed", false),
                        ),
                    )
                }
            }
        }
    }
}
