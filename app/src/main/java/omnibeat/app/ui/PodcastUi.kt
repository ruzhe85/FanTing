package omnibeat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import omnibeat.app.R
import omnibeat.app.data.PodcastDownloadState
import omnibeat.app.model.PODCAST_FEED_URL_MAX_LENGTH
import omnibeat.app.model.PODCAST_IMAGE_URL_MAX_LENGTH
import omnibeat.app.model.PODCAST_TITLE_MAX_LENGTH
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastSubscription
import omnibeat.app.ui.appStringResource as stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 播客页 UI：订阅列表 + 期数列表 + 添加订阅弹窗。
 *
 * 视觉与电台列表保持一致（同一套 RadioSpacing / 颜色 token / 卡片写法），
 * 封面复用 StationArtwork（与电台共用同一份磁盘缓存）。
 */

/**
 * 播客 Tab 的内容分发：收藏列表 / 订阅列表 / 某个订阅的期数列表。
 * 期数页与收藏页都显示返回箭头，同时吞掉系统返回键（优先于外层抽屉/翻页的 BackHandler）。
 */
@Composable
fun PodcastTabContent(
    subscriptions: List<PodcastSubscription>,
    openSubscriptionUrl: String?,
    playingEpisodeId: String?,
    loading: Boolean,
    onOpenSubscription: (PodcastSubscription) -> Unit,
    onCloseSubscription: () -> Unit,
    onDeleteSubscription: (PodcastSubscription) -> Unit,
    onRefreshSubscription: (PodcastSubscription) -> Unit,
    onRefreshAll: () -> Unit = {},
    refreshingAll: Boolean = false,
    refreshProgress: Pair<Int, Int>? = null,
    favoriteEpisodes: List<Pair<PodcastSubscription, PodcastEpisode>> = emptyList(),
    favoritesOpen: Boolean = false,
    onOpenFavorites: () -> Unit = {},
    onCloseFavorites: () -> Unit = {},
    onToggleFavorite: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    downloadStates: Map<String, PodcastDownloadState> = emptyMap(),
    onDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onCancelDownload: (PodcastEpisode) -> Unit = {},
    onDeleteDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onExportDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onEditSubscription: (PodcastSubscription) -> Unit,
    onPlayLatest: (PodcastSubscription) -> Unit,
    onPlayEpisode: (PodcastSubscription, PodcastEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = subscriptions.firstOrNull { it.feedUrl == openSubscriptionUrl }
    when {
        // 收藏的节目：跨订阅聚合，优先于订阅列表
        favoritesOpen -> {
            BackHandler { onCloseFavorites() }
            FavoriteEpisodesPage(
                favorites = favoriteEpisodes,
                playingEpisodeId = playingEpisodeId,
                onBack = onCloseFavorites,
                onEpisodeClick = onPlayEpisode,
                onToggleFavorite = onToggleFavorite,
                downloadStates = downloadStates,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onDeleteDownload = onDeleteDownload,
                onExportDownload = onExportDownload,
                modifier = modifier,
            )
        }

        open != null -> {
            BackHandler { onCloseSubscription() }
            PodcastEpisodesPage(
                subscription = open,
                playingEpisodeId = playingEpisodeId,
                loading = loading,
                onBack = onCloseSubscription,
                onRefresh = { onRefreshSubscription(open) },
                onEpisodeClick = onPlayEpisode,
                onToggleFavorite = { onToggleFavorite(open, it) },
                downloadStates = downloadStates,
                // 下载相关回调是两参类型，页面内部自己会用它自己的 subscription 配对，
                // 这里直接透传（别再包一层，两参 lambda 没有隐式 it）
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onDeleteDownload = onDeleteDownload,
                onExportDownload = onExportDownload,
                modifier = modifier,
            )
        }

        else -> {
            PodcastSubscriptionsPage(
                subscriptions = subscriptions,
                onOpen = onOpenSubscription,
                onPlayLatest = onPlayLatest,
                onEdit = onEditSubscription,
                onDelete = onDeleteSubscription,
                onRefreshAll = onRefreshAll,
                refreshingAll = refreshingAll,
                refreshProgress = refreshProgress,
                favoriteCount = favoriteEpisodes.size,
                onOpenFavorites = onOpenFavorites,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun EmptyPodcastsState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(horizontal = RadioSpacing.page),
    ) {
        Text(
            text = stringResource(R.string.podcast_empty),
            color = RadioTextMuted,
            fontSize = RadioTextSizes.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun PodcastSubscriptionsPage(
    subscriptions: List<PodcastSubscription>,
    onOpen: (PodcastSubscription) -> Unit,
    onPlayLatest: (PodcastSubscription) -> Unit,
    onEdit: (PodcastSubscription) -> Unit,
    onDelete: (PodcastSubscription) -> Unit,
    onRefreshAll: () -> Unit,
    refreshingAll: Boolean,
    refreshProgress: Pair<Int, Int>?,
    favoriteCount: Int = 0,
    onOpenFavorites: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (subscriptions.isEmpty()) {
        EmptyPodcastsState(modifier.fillMaxSize())
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 收藏入口：有收藏的节目才显示。发现路径是期数行的收藏按钮——
            // 收藏了第一期，这行才出现，避免空数据时占着一个没内容的入口。
            if (favoriteCount > 0) {
                PodcastFavoritesEntryRow(
                    count = favoriteCount,
                    onClick = onOpenFavorites,
                    modifier = Modifier.padding(
                        start = RadioSpacing.medium,
                        end = RadioSpacing.medium,
                        top = RadioSpacing.small,
                        bottom = RadioSpacing.extraSmall,
                    ),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = RadioSpacing.medium,
                    end = RadioSpacing.medium,
                    top = RadioSpacing.small,
                    // 底部加大：避让右下角的一键刷新 FAB
                    bottom = RadioSizes.fabListClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                items(subscriptions, key = { it.feedUrl }) { subscription ->
                    PodcastSubscriptionRow(
                        subscription = subscription,
                        onClick = { onOpen(subscription) },
                        onPlayLatest = { onPlayLatest(subscription) },
                        onEdit = { onEdit(subscription) },
                        onDelete = { onDelete(subscription) },
                    )
                }
            }
        }

        // 刷新中在 FAB 上方显示「n/N」小胶囊：串行刷多个订阅时可感知进度
        if (refreshingAll && refreshProgress != null) {
            val (done, total) = refreshProgress
            Text(
                text = stringResource(R.string.podcast_refresh_progress, done, total),
                color = RadioText,
                fontSize = RadioTextSizes.badge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = RadioSpacing.page,
                        bottom = RadioSpacing.page + RadioSizes.fab + RadioSpacing.compact,
                    )
                    .clip(RoundedCornerShape(RadioCorners.pill))
                    .background(RadioSurfaceHigh)
                    .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.extraSmall),
            )
        }

        // 一键刷新全部订阅：刷新中图标位换成转圈，点击由 podcastLoading 互斥防重复
        FloatingActionButton(
            onClick = onRefreshAll,
            containerColor = RadioPrimary,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = RadioSpacing.page, bottom = RadioSpacing.page),
        ) {
            if (refreshingAll) {
                CircularProgressIndicator(
                    modifier = Modifier.size(RadioSizes.iconInline),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_sync),
                    contentDescription = stringResource(R.string.podcast_refresh_all),
                    modifier = Modifier.size(RadioSizes.icon),
                )
            }
        }
    }
}

/**
 * 订阅卡片：点击进入期数页；右侧播放按钮播最新一期；长按弹出「编辑 / 删除」菜单。
 * 删除不再常驻在卡片上（之前那个红色删除图标常驻很突兀）。
 */
@Composable
private fun PodcastSubscriptionRow(
    subscription: PodcastSubscription,
    onClick: () -> Unit,
    onPlayLatest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val updatedText = if (subscription.lastFetchedAtMs > 0L) {
        stringResource(R.string.podcast_last_updated, formatStamp(subscription.lastFetchedAtMs))
    } else {
        stringResource(R.string.podcast_never_updated)
    }
    val countText = stringResource(R.string.podcast_episode_count, subscription.episodes.size)
    // 有没听完的期就优先显示它，否则显示总期数（避免这一行太长被省略号吃掉）
    val unfinishedCount = subscription.episodes.count { !it.completed }
    val subtitle = if (unfinishedCount > 0) {
        stringResource(R.string.podcast_unfinished_count, unfinishedCount) + " · " + updatedText
    } else {
        countText + " · " + updatedText
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(RadioSurface, RoundedCornerShape(RadioCorners.medium))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                )
                .padding(horizontal = RadioSpacing.medium, vertical = RadioSpacing.small),
        ) {
            StationArtwork(imageUrl = subscription.imageUrl)
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.medium),
            ) {
                Text(
                    text = subscription.title,
                    color = RadioText,
                    fontSize = RadioTextSizes.stationTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 菜单锚点跟着右侧按钮：长按后从右边弹出（弹在贴左位置不符合习惯）
            Box {
                OmniIconButton(
                    painter = painterResource(R.drawable.ic_play_arrow),
                    onClick = onPlayLatest,
                    tint = RadioPrimary,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = RadioSurface,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.action_edit),
                                color = RadioText,
                                fontSize = RadioTextSizes.body,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = null,
                                tint = RadioTextMuted,
                                modifier = Modifier.size(RadioSizes.iconInline),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.action_delete),
                                color = RadioDanger,
                                fontSize = RadioTextSizes.body,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = RadioDanger,
                                modifier = Modifier.size(RadioSizes.iconInline),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodesPage(
    subscription: PodcastSubscription,
    playingEpisodeId: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEpisodeClick: (PodcastSubscription, PodcastEpisode) -> Unit,
    onToggleFavorite: (PodcastEpisode) -> Unit = {},
    downloadStates: Map<String, PodcastDownloadState> = emptyMap(),
    onDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onCancelDownload: (PodcastEpisode) -> Unit = {},
    onDeleteDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onExportDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.extraSmall,
                    end = RadioSpacing.medium,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.small,
                ),
        ) {
            OmniIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                onClick = onBack,
            )
            StationArtwork(imageUrl = subscription.imageUrl)
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.medium),
            ) {
                Text(
                    text = subscription.title,
                    color = RadioText,
                    fontSize = RadioTextSizes.subtitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.podcast_episode_count, subscription.episodes.size),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 下拉刷新。空态也放进 LazyColumn：没有可滚动容器时下拉手势收不到。
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = RadioSpacing.medium,
                    vertical = RadioSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                if (subscription.episodes.isEmpty()) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(horizontal = RadioSpacing.page),
                        ) {
                            Text(
                                text = if (loading) {
                                    stringResource(R.string.podcast_loading)
                                } else {
                                    stringResource(R.string.podcast_episodes_empty)
                                },
                                color = RadioTextMuted,
                                fontSize = RadioTextSizes.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(subscription.episodes, key = { it.guid }) { episode ->
                        PodcastEpisodeRow(
                            episode = episode,
                            playing = episode.guid == playingEpisodeId,
                            onClick = { onEpisodeClick(subscription, episode) },
                            onToggleFavorite = { onToggleFavorite(episode) },
                            downloadState = downloadStates[episode.guid],
                            onDownload = { onDownload(subscription, episode) },
                            onCancelDownload = { onCancelDownload(episode) },
                            onDeleteDownload = { onDeleteDownload(subscription, episode) },
                            onExportDownload = { onExportDownload(subscription, episode) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 收藏的节目：跨订阅聚合列表。
 * 数据是各订阅里 favorite = true 的期（OmniBeatApp 侧按发布时间倒序派发），
 * 纯本地数据，所以不做下拉刷新 —— 没有可刷的东西，避免转一个骗人的圈。
 */
@Composable
fun FavoriteEpisodesPage(
    favorites: List<Pair<PodcastSubscription, PodcastEpisode>>,
    playingEpisodeId: String?,
    onBack: () -> Unit,
    onEpisodeClick: (PodcastSubscription, PodcastEpisode) -> Unit,
    onToggleFavorite: (PodcastSubscription, PodcastEpisode) -> Unit,
    downloadStates: Map<String, PodcastDownloadState> = emptyMap(),
    onDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onCancelDownload: (PodcastEpisode) -> Unit = {},
    onDeleteDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onExportDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.extraSmall,
                    end = RadioSpacing.medium,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.small,
                ),
        ) {
            OmniIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                onClick = onBack,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.podcast_favorites_title),
                    color = RadioText,
                    fontSize = RadioTextSizes.subtitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.podcast_favorites_count, favorites.size),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    maxLines = 1,
                )
            }
        }

        if (favorites.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = RadioSpacing.page),
            ) {
                Text(
                    text = stringResource(R.string.podcast_favorites_empty),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = RadioSpacing.medium,
                    vertical = RadioSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                // key 带上 feedUrl：guid 只在同一订阅内保证唯一，跨订阅理论上会撞
                items(favorites, key = { it.first.feedUrl + "|" + it.second.guid }) { (subscription, episode) ->
                    PodcastEpisodeRow(
                        episode = episode,
                        playing = episode.guid == playingEpisodeId,
                        onClick = { onEpisodeClick(subscription, episode) },
                        onToggleFavorite = { onToggleFavorite(subscription, episode) },
                        downloadState = downloadStates[episode.guid],
                        onDownload = { onDownload(subscription, episode) },
                        onCancelDownload = { onCancelDownload(episode) },
                        onDeleteDownload = { onDeleteDownload(subscription, episode) },
                        onExportDownload = { onExportDownload(subscription, episode) },
                    )
                }
            }
        }
    }
}

/**
 * 收藏入口行：常驻订阅列表顶部（有收藏才显示）。
 * 视觉与订阅卡片同语言（圆角底面 + 左图标 + 右侧计数），但用主色实心爱心
 * 和 surfaceHigh 底色，和订阅卡片区分开。
 */
@Composable
private fun PodcastFavoritesEntryRow(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(RadioSurfaceHigh, RoundedCornerShape(RadioCorners.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = RadioSpacing.medium, vertical = RadioSpacing.rowVertical),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_favorite),
            contentDescription = null,
            tint = RadioPrimary,
            modifier = Modifier.size(RadioSizes.iconInline),
        )
        Text(
            text = stringResource(R.string.podcast_favorites_title),
            color = RadioText,
            fontSize = RadioTextSizes.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = RadioSpacing.medium),
        )
        Text(
            text = stringResource(R.string.podcast_favorites_count, count),
            color = RadioTextMuted,
            fontSize = RadioTextSizes.caption,
            maxLines = 1,
        )
    }
}

@Composable
private fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    playing: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    downloadState: PodcastDownloadState? = null,
    onDownload: (() -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    onExportDownload: (() -> Unit)? = null,
) {
    // 进度标记：有续播位置就显示听到哪；听完且位置已归零则显示已听完
    val progressText = when {
        episode.positionMs > 0L -> stringResource(
            R.string.podcast_listened_progress,
            formatDuration((episode.positionMs / 1000L).toInt()),
        )

        episode.completed -> stringResource(R.string.podcast_completed)
        else -> ""
    }
    val meta = listOf(
        formatEpisodeDate(episode.publishedAtMs),
        formatDuration(episode.durationSec),
        progressText,
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (playing) RadioSurfaceHigh else RadioSurface,
                shape = RoundedCornerShape(RadioCorners.medium),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = RadioSpacing.medium, vertical = RadioSpacing.rowVertical),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = episode.title,
                color = if (playing) RadioPrimary else RadioText,
                fontSize = RadioTextSizes.body,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 收藏与下载两个辅助按钮：38dp 小圆钮（比 53dp 正牌按钮小一档），
        // 并排也不抢标题宽度；按钮自己吃掉点击，不会冒泡到整行的播放。
        if (onToggleFavorite != null) {
            EpisodeRowAuxButton(
                icon = painterResource(
                    if (episode.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                ),
                onClick = onToggleFavorite,
                tint = if (episode.favorite) RadioPrimary else RadioTextMuted,
                contentDescription = stringResource(
                    if (episode.favorite) R.string.podcast_favorite_remove
                    else R.string.podcast_favorite_add,
                ),
            )
        }
        if (onDownload != null) {
            EpisodeDownloadButton(
                episode = episode,
                downloadState = downloadState,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload ?: {},
                onDeleteDownload = onDeleteDownload ?: {},
                onExportDownload = onExportDownload ?: {},
            )
        }
    }
}

/** 期数行尾部辅助按钮（收藏 / 下载）的直径：比 53dp 正牌按钮小一档，两个并排不抢标题宽度 */
private val EPISODE_ROW_AUX_BUTTON_SIZE = 38.dp

/**
 * 期数行尾部的小圆钮。[progress] 非空时切转圈模式（>=0  determinate、<0   indeterminate，
 * 用于下载中），此时 [icon] 忽略。
 */
@Composable
private fun EpisodeRowAuxButton(
    onClick: () -> Unit,
    tint: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    progress: Float? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(EPISODE_ROW_AUX_BUTTON_SIZE)
            .clip(CircleShape)
            .background(RadioSurfaceHigh)
            .clickable(onClick = onClick),
    ) {
        when {
            progress != null && progress >= 0f -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(RadioSizes.iconInline),
                color = tint,
                strokeWidth = 2.dp,
            )

            progress != null -> CircularProgressIndicator(
                modifier = Modifier.size(RadioSizes.iconInline),
                color = tint,
                strokeWidth = 2.dp,
            )

            icon != null -> Icon(
                painter = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(RadioSizes.iconInline),
            )
        }
    }
}

/**
 * 期数行的下载按钮，四态：
 * - 下载中：进度圈（点了取消）
 * - 已下载：主色实心勾（点开小菜单：导出文件 / 删除下载）
 * - 失败：主色下载图标（点了重试）
 * - 未下载：弱色下载图标（点了开始）
 */
@Composable
private fun EpisodeDownloadButton(
    episode: PodcastEpisode,
    downloadState: PodcastDownloadState?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onExportDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        when {
            downloadState is PodcastDownloadState.Downloading -> EpisodeRowAuxButton(
                onClick = onCancelDownload,
                tint = RadioTextMuted,
                contentDescription = stringResource(R.string.podcast_download_cancel),
                progress = downloadState.progress,
            )

            episode.localFileName != null -> {
                EpisodeRowAuxButton(
                    onClick = { menuExpanded = true },
                    tint = RadioPrimary,
                    contentDescription = stringResource(R.string.podcast_download_downloaded),
                    icon = painterResource(R.drawable.ic_check),
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = RadioSurface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.podcast_download_export)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_file_export),
                                contentDescription = null,
                                tint = RadioTextMuted,
                                modifier = Modifier.size(RadioSizes.iconInline),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onExportDownload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.podcast_download_delete)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = RadioTextMuted,
                                modifier = Modifier.size(RadioSizes.iconInline),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteDownload()
                        },
                    )
                }
            }

            else -> {
                // 失败态用主色：提示「可以重点重试」，和未下载的弱色区分
                val failed = downloadState is PodcastDownloadState.Failed
                EpisodeRowAuxButton(
                    onClick = onDownload,
                    tint = if (failed) RadioPrimary else RadioTextMuted,
                    contentDescription = stringResource(
                        if (failed) R.string.podcast_download_retry
                        else R.string.podcast_download,
                    ),
                    icon = painterResource(R.drawable.ic_download),
                )
            }
        }
    }
}

/**
 * 添加订阅：填 RSS 地址 → 拉取解析 → 成功才写入订阅。
 * 失败（地址为空 / 拉不到 / 解析不出单集）时原样显示错误，不写库。
 */
@Composable
fun AddPodcastDialog(
    loading: Boolean,
    errorText: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var feedUrl by remember { mutableStateOf("") }
    var showEmptyError by remember { mutableStateOf(false) }
    val textFieldColors = omniTextFieldColors()

    AlertDialog(
        // 请求进行中也要能关：调用方会取消在途请求，卡住的网络不该锁住返回键
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RadioSpacing.large)
            .widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.podcast_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium)) {
                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = {
                        feedUrl = it.take(PODCAST_FEED_URL_MAX_LENGTH)
                        showEmptyError = false
                    },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.podcast_feed_hint)) },
                    placeholder = { Text("https://...") },
                    isError = showEmptyError || errorText != null,
                    supportingText = when {
                        showEmptyError -> {
                            { Text(stringResource(R.string.podcast_error_empty_url)) }
                        }

                        errorText != null -> {
                            { Text(errorText) }
                        }

                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                    modifier = Modifier
                        .heightIn(min = RadioSizes.textFieldMinHeight)
                        .fillMaxWidth(),
                )
                if (loading) {
                    Text(
                        text = stringResource(R.string.podcast_loading),
                        color = RadioTextMuted,
                        fontSize = RadioTextSizes.caption,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OmniSecondaryButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.weight(1f))
                OmniPrimaryButton(
                    text = stringResource(R.string.podcast_subscribe),
                    onClick = {
                        val trimmed = feedUrl.trim()
                        if (trimmed.isEmpty()) {
                            showEmptyError = true
                        } else {
                            onSubmit(trimmed)
                        }
                    },
                    enabled = !loading,
                )
            }
        },
        dismissButton = {},
        containerColor = RadioSurface,
        titleContentColor = RadioText,
        textContentColor = RadioTextMuted,
    )
}

/**
 * 编辑订阅：可改标题与订阅地址。
 *
 * 地址变化时由调用方先用新地址拉取解析、成功才替换；失败把错误回显在这里（不落库）。
 */
@Composable
fun EditPodcastDialog(
    subscription: PodcastSubscription,
    loading: Boolean,
    errorText: String?,
    onSave: (title: String, feedUrl: String, imageUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(subscription.feedUrl) { mutableStateOf(subscription.title) }
    var feedUrl by remember(subscription.feedUrl) { mutableStateOf(subscription.feedUrl) }
    var imageUrl by remember(subscription.feedUrl) { mutableStateOf(subscription.imageUrl.orEmpty()) }
    var titleError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }
    var artworkError by remember { mutableStateOf(false) }
    val textFieldColors = omniTextFieldColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RadioSpacing.large)
            .widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.podcast_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it.take(PODCAST_TITLE_MAX_LENGTH)
                        titleError = false
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.podcast_title_hint)) },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text(stringResource(R.string.podcast_error_empty_title)) }
                    } else {
                        null
                    },
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = {
                        feedUrl = it.take(PODCAST_FEED_URL_MAX_LENGTH)
                        urlError = false
                    },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    label = { Text(stringResource(R.string.podcast_feed_hint)) },
                    isError = urlError || errorText != null,
                    supportingText = when {
                        urlError -> {
                            { Text(stringResource(R.string.podcast_error_empty_url)) }
                        }

                        errorText != null -> {
                            { Text(errorText) }
                        }

                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                    modifier = Modifier
                        .heightIn(min = RadioSizes.textFieldMinHeight)
                        .fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = {
                        imageUrl = it.take(PODCAST_IMAGE_URL_MAX_LENGTH)
                        artworkError = false
                    },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    label = { Text(stringResource(R.string.podcast_artwork_label)) },
                    placeholder = { Text("https://...") },
                    isError = artworkError,
                    supportingText = {
                        Text(
                            if (artworkError) {
                                stringResource(R.string.podcast_artwork_error)
                            } else {
                                stringResource(R.string.podcast_artwork_hint)
                            },
                        )
                    },
                    trailingIcon = if (imageUrl.isNotBlank()) {
                        {
                            OmniIconButton(
                                painter = painterResource(R.drawable.ic_close),
                                onClick = { imageUrl = "" },
                            )
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = textFieldColors,
                    modifier = Modifier
                        .heightIn(min = RadioSizes.textFieldMinHeight)
                        .fillMaxWidth(),
                )
                if (loading) {
                    Text(
                        text = stringResource(R.string.podcast_loading),
                        color = RadioTextMuted,
                        fontSize = RadioTextSizes.caption,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OmniSecondaryButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.weight(1f))
                OmniPrimaryButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        val trimmedTitle = title.trim()
                        val trimmedFeedUrl = feedUrl.trim()
                        val trimmedImageUrl = imageUrl.trim()
                        when {
                            trimmedTitle.isEmpty() -> titleError = true
                            trimmedFeedUrl.isEmpty() -> urlError = true
                            trimmedImageUrl.isNotEmpty() && !isValidArtworkUrl(trimmedImageUrl) ->
                                artworkError = true

                            else -> onSave(trimmedTitle, trimmedFeedUrl, trimmedImageUrl)
                        }
                    },
                    enabled = !loading,
                )
            }
        },
        dismissButton = {},
        containerColor = RadioSurface,
        titleContentColor = RadioText,
        textContentColor = RadioTextMuted,
    )
}

private val EPISODE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

private fun isValidArtworkUrl(imageUrl: String): Boolean {
    val uri = imageUrl.toUri()
    return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}
private val UPDATE_STAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH)

private fun formatEpisodeDate(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        EPISODE_DATE_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    }.getOrDefault("")
}

private fun formatStamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        UPDATE_STAMP_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    }.getOrDefault("")
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}
