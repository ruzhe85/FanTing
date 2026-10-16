package omnibeat.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 */
private val Context.podcastDataStore by preferencesDataStore(name = "podcasts")
private val podcastsJsonKey = stringPreferencesKey("podcasts_json")

class PodcastRepository(private val context: Context) {
    val subscriptions: Flow<List<PodcastSubscription>> = context.podcastDataStore.data
        .map { preferences -> decodeSubscriptions(preferences[podcastsJsonKey].orEmpty()) }

    /** 新增或覆盖（按 feedUrl 去重）：添加订阅与刷新后都用它写回 */
    suspend fun upsertSubscription(subscription: PodcastSubscription) {
        context.podcastDataStore.edit { preferences ->
            val current = decodeSubscriptions(preferences[podcastsJsonKey].orEmpty())
            val index = current.indexOfFirst { it.feedUrl == subscription.feedUrl }
            val next = if (index == -1) {
                current + subscription
            } else {
                current.toMutableList().also { it[index] = subscription }
            }
            preferences[podcastsJsonKey] = encodeSubscriptions(next)
        }
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
                        .put("durationSec", episode.durationSec),
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
                        ),
                    )
                }
            }
        }
    }
}
