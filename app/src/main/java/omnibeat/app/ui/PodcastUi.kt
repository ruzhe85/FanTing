package omnibeat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
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
    episodesOldestFirst: Boolean = false,
    /** 期数页的本地搜索：打开状态与关键词由外层持有，切订阅时一并重置 */
    episodeSearchOpen: Boolean = false,
    episodeSearchQuery: String = "",
    onEpisodeSearchOpenChange: (Boolean) -> Unit = {},
    onEpisodeSearchQueryChange: (String) -> Unit = {},
    onOpenSubscription: (PodcastSubscription) -> Unit,
    onCloseSubscription: () -> Unit,
    onDeleteSubscription: (PodcastSubscription) -> Unit,
    onToggleSubscriptionPinned: (PodcastSubscription) -> Unit = {},
    /** 订阅标题是否参与「标题滚动显示」；关掉时超长标题只截断 */
    marqueeTitles: Boolean = false,
    /** 订阅列表是否无封面的紧凑两行模式 */
    compactList: Boolean = false,
    onRefreshSubscription: (PodcastSubscription) -> Unit,
    onRefreshAll: () -> Unit = {},
    refreshingAll: Boolean = false,
    refreshProgress: Pair<Int, Int>? = null,
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
        open != null -> {
            BackHandler { onCloseSubscription() }
            PodcastEpisodesPage(
                subscription = open,
                playingEpisodeId = playingEpisodeId,
                loading = loading,
                oldestFirst = episodesOldestFirst,
                marqueeTitles = marqueeTitles,
                searchOpen = episodeSearchOpen,
                searchQuery = episodeSearchQuery,
                onSearchOpenChange = onEpisodeSearchOpenChange,
                onSearchQueryChange = onEpisodeSearchQueryChange,
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
                onTogglePinned = onToggleSubscriptionPinned,
                marqueeTitles = marqueeTitles,
                compact = compactList,
                onRefreshAll = onRefreshAll,
                refreshingAll = refreshingAll,
                refreshProgress = refreshProgress,
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
    onTogglePinned: (PodcastSubscription) -> Unit = {},
    marqueeTitles: Boolean = false,
    compact: Boolean = false,
    onRefreshAll: () -> Unit,
    refreshingAll: Boolean,
    refreshProgress: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    if (subscriptions.isEmpty()) {
        EmptyPodcastsState(modifier.fillMaxSize())
        return
    }

    // 置顶的订阅排在前面；sortedByDescending 是稳定排序，置顶组内保持原顺序
    val ordered = remember(subscriptions) {
        subscriptions.sortedByDescending { it.pinned }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                items(ordered, key = { it.feedUrl }) { subscription ->
                    if (compact) {
                        CompactSubscriptionRow(
                            subscription = subscription,
                            marqueeTitles = marqueeTitles,
                            onClick = { onOpen(subscription) },
                            onPlayLatest = { onPlayLatest(subscription) },
                            onEdit = { onEdit(subscription) },
                            onDelete = { onDelete(subscription) },
                            onTogglePinned = { onTogglePinned(subscription) },
                        )
                    } else {
                        PodcastSubscriptionRow(
                            subscription = subscription,
                            marqueeTitles = marqueeTitles,
                            onClick = { onOpen(subscription) },
                            onPlayLatest = { onPlayLatest(subscription) },
                            onEdit = { onEdit(subscription) },
                            onDelete = { onDelete(subscription) },
                            onTogglePinned = { onTogglePinned(subscription) },
                        )
                    }
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
            contentColor = RadioOnPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = RadioSpacing.page, bottom = RadioSpacing.page),
        ) {
            if (refreshingAll) {
                CircularProgressIndicator(
                    modifier = Modifier.size(RadioSizes.iconInline),
                    color = RadioOnPrimary,
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
 * 订阅卡片（标准模式）：封面点播最新一期（右下播放角标做暗示），
 * 点标题/文字进期数列表；长按弹出「置顶 / 编辑 / 删除」菜单。
 * 右侧不再常驻播放按钮，把宽度让给标题与副标题，避免「92 期未听 · 更新于…」被截断。
 */
@Composable
private fun PodcastSubscriptionRow(
    subscription: PodcastSubscription,
    marqueeTitles: Boolean,
    onClick: () -> Unit,
    onPlayLatest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val subtitle = subscriptionSubtitle(subscription)

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(RadioSurface, RoundedCornerShape(RadioCorners.medium))
                .padding(horizontal = RadioSpacing.medium, vertical = RadioSpacing.small),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    onClick = onPlayLatest,
                    onLongClick = { menuExpanded = true },
                ),
            ) {
                StationArtwork(imageUrl = subscription.imageUrl)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(PLAY_BADGE_SIZE)
                        .clip(CircleShape)
                        .background(RadioPrimary),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_arrow),
                        contentDescription = null,
                        tint = RadioOnPrimary,
                        modifier = Modifier.size(PLAY_BADGE_ICON_SIZE),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.medium)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                SubscriptionTitle(
                    title = subscription.title,
                    marqueeTitles = marqueeTitles,
                )
                Text(
                    text = subtitle,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 没有右侧按钮了，用 1dp 空白当菜单锚点，长按菜单仍从右边弹出
            Box(modifier = Modifier.size(1.dp)) {
                SubscriptionMenu(
                    expanded = menuExpanded,
                    pinned = subscription.pinned,
                    onDismiss = { menuExpanded = false },
                    onTogglePinned = onTogglePinned,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }
    }
}

/**
 * 紧凑订阅行：单行「播放 · 标题 · 期数与更新时间」，无封面。
 * 点标题进期数列表，点左侧播放图标播最新一期；长按出菜单（置顶 / 编辑 / 删除）。
 */
@Composable
private fun CompactSubscriptionRow(
    subscription: PodcastSubscription,
    marqueeTitles: Boolean,
    onClick: () -> Unit,
    onPlayLatest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val subtitle = subscriptionSubtitle(subscription)

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = COMPACT_ROW_MIN_HEIGHT)
                .background(RadioSurface, RoundedCornerShape(RadioCorners.medium))
                .padding(end = RadioSpacing.small),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(COMPACT_PLAY_TOUCH_SIZE)
                    .combinedClickable(
                        onClick = onPlayLatest,
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play_arrow),
                    contentDescription = stringResource(R.string.podcast_play_latest),
                    tint = RadioPrimary,
                    modifier = Modifier.size(RadioSizes.iconInline),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                SubscriptionTitle(
                    title = subscription.title,
                    marqueeTitles = marqueeTitles,
                    fontSize = RadioTextSizes.body,
                )
            }
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.badge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = RadioSpacing.small),
            )
            Box(modifier = Modifier.size(1.dp)) {
                SubscriptionMenu(
                    expanded = menuExpanded,
                    pinned = subscription.pinned,
                    onDismiss = { menuExpanded = false },
                    onTogglePinned = onTogglePinned,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }
    }
}

/** 订阅行标题：单行，开启「标题滚动显示」时滚动一次，否则只截断 */
@Composable
private fun SubscriptionTitle(
    title: String,
    marqueeTitles: Boolean,
    fontSize: TextUnit = RadioTextSizes.stationTitle,
) {
    Text(
        text = title,
        color = RadioText,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = if (marqueeTitles) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = false,
        modifier = if (marqueeTitles) {
            Modifier.basicMarquee(
                iterations = 1,
                repeatDelayMillis = 1_500,
            )
        } else {
            Modifier
        },
    )
}

/** 订阅副标题：期数 · 月-日 时:分 更新；一次都没更新过就显示「尚未更新」 */
@Composable
private fun subscriptionSubtitle(subscription: PodcastSubscription): String {
    if (subscription.lastFetchedAtMs <= 0L) {
        return stringResource(R.string.podcast_never_updated)
    }
    return stringResource(
        R.string.podcast_list_subtitle_all,
        subscription.episodes.size,
        formatStamp(subscription.lastFetchedAtMs),
    )
}

/** 长按菜单：置顶 / 编辑 / 删除。标准与紧凑两种行共用 */
@Composable
private fun SubscriptionMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePinned: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = RadioSurface,
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(
                        if (pinned) R.string.podcast_unpin else R.string.podcast_pin,
                    ),
                    color = RadioText,
                    fontSize = RadioTextSizes.body,
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(
                        if (pinned) R.drawable.ic_keyboard_arrow_down
                        else R.drawable.ic_keyboard_arrow_up,
                    ),
                    contentDescription = null,
                    tint = RadioTextMuted,
                    modifier = Modifier.size(RadioSizes.iconInline),
                )
            },
            onClick = {
                onDismiss()
                onTogglePinned()
            },
        )
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
                onDismiss()
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
                onDismiss()
                onDelete()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodesPage(
    subscription: PodcastSubscription,
    playingEpisodeId: String?,
    loading: Boolean,
    oldestFirst: Boolean = false,
    marqueeTitles: Boolean = false,
    searchOpen: Boolean = false,
    searchQuery: String = "",
    onSearchOpenChange: (Boolean) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
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
    // 搜索打开时，返回键先关搜索，而不是直接退出这个订阅
    BackHandler(enabled = searchOpen) { onSearchOpenChange(false) }
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) searchFocusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.extraSmall,
                    end = RadioSpacing.small,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.small,
                ),
        ) {
            // 轻量返回键：不再用 53dp 的大按钮，把宽度让给标题
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(HEADER_ICON_BUTTON_SIZE),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                    tint = RadioText,
                    modifier = Modifier.size(RadioSizes.iconCompact),
                )
            }
            StationArtwork(imageUrl = subscription.imageUrl)
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.medium),
            ) {
                // 标题必须单行：一旦换行，头部就比封面还高，把下面列表往下顶
                SubscriptionTitle(
                    title = subscription.title,
                    marqueeTitles = marqueeTitles,
                    fontSize = RadioTextSizes.stationTitle,
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

        if (searchOpen) {
            EpisodeSearchField(
                query = searchQuery,
                focusRequester = searchFocusRequester,
                onQueryChange = onSearchQueryChange,
                onClose = {
                    onSearchQueryChange("")
                    onSearchOpenChange(false)
                },
                modifier = Modifier.padding(
                    start = RadioSpacing.page,
                    end = RadioSpacing.page,
                    bottom = RadioSpacing.small,
                ),
            )
        }

        // 期数可能有几百条：排序只影响展示顺序，不改写订阅数据本身
        val orderedEpisodes = if (oldestFirst) {
            subscription.episodes.asReversed()
        } else {
            subscription.episodes
        }
        // 本地按标题过滤：一千多期时靠滑根本找不到，输入关键词即时缩小范围
        val query = searchQuery.trim()
        val searching = query.isNotEmpty()
        val episodes = if (searching) {
            orderedEpisodes.filter { it.title.contains(query, ignoreCase = true) }
        } else {
            orderedEpisodes
        }
        val listState = rememberLazyListState()
        // 太短的列表不需要快速定位，保持原布局
        val showScrollbar = episodes.size > EPISODE_SCROLLBAR_MIN_COUNT
        var scrollbarIndex by remember { mutableStateOf<Int?>(null) }
        // 切换排序或搜索词后回到顶部，否则停在原位置会让人以为列表没变
        LaunchedEffect(oldestFirst, query) { listState.scrollToItem(0) }

        // 下拉刷新。空态也放进 LazyColumn：没有可滚动容器时下拉手势收不到。
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // 只让出 3dp 视觉空隙；滚动条触摸区会压住行尾少量区域，但按钮中心仍可点
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = if (showScrollbar) EPISODE_SCROLLBAR_INSET else 0.dp),
                    contentPadding = PaddingValues(
                        horizontal = RadioSpacing.medium,
                        vertical = RadioSpacing.small,
                    ),
                    verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
                ) {
                    if (episodes.isEmpty()) {
                        item {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(horizontal = RadioSpacing.page),
                            ) {
                                Text(
                                    text = when {
                                        searching -> stringResource(R.string.podcast_episode_search_empty)
                                        loading -> stringResource(R.string.podcast_loading)
                                        else -> stringResource(R.string.podcast_episodes_empty)
                                    },
                                    color = RadioTextMuted,
                                    fontSize = RadioTextSizes.bodyLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        items(episodes, key = { it.guid }) { episode ->
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
                // 几百期时靠右侧拖动快速定位，拖动中在列表中央显示 x / 总数
                if (showScrollbar) {
                    OmniLazyScrollbar(
                        listState = listState,
                        itemCount = episodes.size,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        onDraggingIndexChange = { scrollbarIndex = it },
                    )
                }
                scrollbarIndex?.let { index ->
                    Text(
                        text = "${index + 1} / ${episodes.size}",
                        color = RadioText,
                        fontSize = RadioTextSizes.caption,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(RadioCorners.pill))
                            .background(RadioSurfaceHigh)
                            .padding(
                                horizontal = RadioSpacing.compact,
                                vertical = RadioSpacing.extraSmall,
                            ),
                    )
                }
            }
        }
    }
}

/** 期数超过这个数量才显示可拖动滚动条：太短的列表反而被它占掉右侧空间 */
private const val EPISODE_SCROLLBAR_MIN_COUNT = 20

/** 期数页头部的轻量图标按钮（返回 / 排序）：40dp，比 53dp 的顶栏按钮小一档，少挤标题宽度 */
private val HEADER_ICON_BUTTON_SIZE = 40.dp

/** 期数页的本地搜索框：输入即过滤，× 清空并关闭 */
@Composable
private fun EpisodeSearchField(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(stringResource(R.string.podcast_episode_search_hint)) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = RadioTextMuted,
                modifier = Modifier.size(RadioSizes.iconInline),
            )
        },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = RadioTextMuted,
                    modifier = Modifier.size(RadioSizes.iconInline),
                )
            }
        },
        colors = omniTextFieldColors(),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

/**
 * 收藏的节目列表：跨订阅聚合，供收藏 tab 的「节目」分段使用。
 * 数据是各订阅里 favorite = true 的期（OmniBeatApp 侧按发布时间倒序派发），纯本地数据。
 */
@Composable
fun FavoriteEpisodesList(
    favorites: List<Pair<PodcastSubscription, PodcastEpisode>>,
    playingEpisodeId: String?,
    onEpisodeClick: (PodcastSubscription, PodcastEpisode) -> Unit,
    onToggleFavorite: (PodcastSubscription, PodcastEpisode) -> Unit,
    downloadStates: Map<String, PodcastDownloadState> = emptyMap(),
    onDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onCancelDownload: (PodcastEpisode) -> Unit = {},
    onDeleteDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onExportDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
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
            modifier = modifier.fillMaxSize(),
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

/** 标准订阅行封面右下角的播放角标：18dp 圆底 + 12dp 三角，暗示点封面可以播最新一期 */
private val PLAY_BADGE_SIZE = 18.dp
private val PLAY_BADGE_ICON_SIZE = 12.dp

/** 紧凑订阅行左侧播放按钮的触摸区：视觉是 20dp 图标，触摸区 36dp */
private val COMPACT_PLAY_TOUCH_SIZE = 36.dp

/** 紧凑订阅行最小高度：单行文字 + 上下留白 */
private val COMPACT_ROW_MIN_HEIGHT = 44.dp

/** 期数列表右侧给滚动条留的视觉空隙：滚动条触摸区会压在行尾上，但视觉只让出 3dp */
private val EPISODE_SCROLLBAR_INSET = 3.dp

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
// 月-日 时:分：比 "17 Oct 13:54" 省一大截宽度，副标题不容易被截断
private val UPDATE_STAMP_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ENGLISH)

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
