package omnibeat.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import omnibeat.app.R
import omnibeat.app.data.PodcastDownloadManager
import omnibeat.app.data.PodcastDownloadState
import omnibeat.app.model.PODCAST_FEED_URL_MAX_LENGTH
import omnibeat.app.model.PODCAST_IMAGE_URL_MAX_LENGTH
import omnibeat.app.model.PODCAST_TITLE_MAX_LENGTH
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastListLayout
import omnibeat.app.model.PodcastSubscription
import omnibeat.app.ui.appStringResource as stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 播客页 UI：订阅列表 + 期数列表 + 添加订阅弹窗。
 *
 * 视觉与电台列表保持一致（同一套 RadioSpacing / 颜色 token / 卡片写法），
 * 封面复用 StationArtwork（与电台共用同一份磁盘缓存）。
 */

/**
 * 播客 Tab 的内容分发：期数列表 / 下载管理页 / 订阅列表。
 * 期数页与下载管理页都显示返回箭头，同时吞掉系统返回键（优先于外层抽屉/翻页的 BackHandler）。
 */
@Composable
fun PodcastTabContent(
    subscriptions: List<PodcastSubscription>,
    openSubscriptionUrl: String?,
    playingEpisodeId: String?,
    loading: Boolean,
    episodesOldestFirst: Boolean = false,
    onEpisodesOldestFirstChange: (Boolean) -> Unit = {},
    /** 期数页的本地搜索：打开状态与关键词由外层持有，切订阅时一并重置 */
    episodeSearchOpen: Boolean = false,
    episodeSearchQuery: String = "",
    onEpisodeSearchOpenChange: (Boolean) -> Unit = {},
    onEpisodeSearchQueryChange: (String) -> Unit = {},
    /** 顶栏 ⋮「多选」的触发计数器（ OmniBeatApp 持有，期数页监听进入多选） */
    episodeMultiSelectRequest: Int = 0,
    onOpenSubscription: (PodcastSubscription) -> Unit,
    onCloseSubscription: () -> Unit,
    onDeleteSubscription: (PodcastSubscription) -> Unit,
    /** 订阅列表拖动排序中：禁止点击与右键菜单，只允许拖动 */
    reordering: Boolean = false,
    onMoveSubscription: (Int, Int) -> Unit = { _, _ -> },
    /** 订阅标题是否参与「标题滚动显示」；关掉时超长标题只截断 */
    marqueeTitles: Boolean = false,
    /** 订阅列表显示方式：标准行 / 紧凑行 / 纯封面网格 */
    listLayout: PodcastListLayout = PodcastListLayout.Standard,
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
    /** 批量下载 / 批量已听未听：由外层统一处理 Wi-Fi 确认与落库 */
    onDownloadEpisodes: (PodcastSubscription, List<PodcastEpisode>) -> Unit = { _, _ -> },
    onMarkEpisodesPlayed: (PodcastSubscription, List<PodcastEpisode>, Boolean) -> Unit =
        { _, _, _ -> },
    /** 下载管理页列表（OmniBeatApp 合并：进行中/失败在前，已下载按发布时间在后） */
    downloads: List<Pair<PodcastSubscription, PodcastEpisode>> = emptyList(),
    downloadsOpen: Boolean = false,
    onOpenDownloads: () -> Unit = {},
    onCloseDownloads: () -> Unit = {},
    onDeleteAllDownloads: () -> Unit = {},
    onEditSubscription: (PodcastSubscription) -> Unit,
    onPlayLatest: (PodcastSubscription) -> Unit,
    onPlayEpisode: (PodcastSubscription, PodcastEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = subscriptions.firstOrNull { it.feedUrl == openSubscriptionUrl }
    when {
        open != null -> {
            BackHandler { onCloseSubscription() }
            PodcastEpisodesScreen(
                subscription = open,
                playingEpisodeId = playingEpisodeId,
                loading = loading,
                oldestFirst = episodesOldestFirst,
                onOldestFirstChange = onEpisodesOldestFirstChange,
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
                onEdit = { onEditSubscription(open) },
                onUnsubscribe = { onDeleteSubscription(open) },
                onDownloadMany = onDownloadEpisodes,
                onMarkManyPlayed = onMarkEpisodesPlayed,
                multiSelectRequest = episodeMultiSelectRequest,
                modifier = modifier,
            )
        }

        // 下载管理页：跨订阅聚合，优先于订阅列表
        downloadsOpen -> {
            BackHandler { onCloseDownloads() }
            DownloadsPage(
                downloads = downloads,
                playingEpisodeId = playingEpisodeId,
                onBack = onCloseDownloads,
                onEpisodeClick = onPlayEpisode,
                onToggleFavorite = onToggleFavorite,
                downloadStates = downloadStates,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onDeleteDownload = onDeleteDownload,
                onExportDownload = onExportDownload,
                onDeleteAll = onDeleteAllDownloads,
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
                reordering = reordering,
                onMove = onMoveSubscription,
                marqueeTitles = marqueeTitles,
                layout = listLayout,
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
    reordering: Boolean = false,
    onMove: (Int, Int) -> Unit = { _, _ -> },
    marqueeTitles: Boolean = false,
    layout: PodcastListLayout = PodcastListLayout.Standard,
    onRefreshAll: () -> Unit,
    refreshingAll: Boolean,
    refreshProgress: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    if (subscriptions.isEmpty()) {
        EmptyPodcastsState(modifier.fillMaxSize())
        return
    }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (layout == PodcastListLayout.Cover && !reordering) {
            PodcastCoverGrid(
                subscriptions = subscriptions,
                onClick = onOpen,
                onEdit = onEdit,
                onDelete = onDelete,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState,
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
                itemsIndexed(
                    items = subscriptions,
                    key = { _, subscription -> subscription.feedUrl },
                ) { index, subscription ->
                    ReorderableItem(
                        state = reorderableState,
                        key = subscription.feedUrl,
                        enabled = reordering,
                    ) { isDragging ->
                        val dragHandleModifier = if (reordering) {
                            Modifier.draggableHandle()
                        } else {
                            Modifier
                        }
                        if (layout == PodcastListLayout.Compact) {
                            CompactSubscriptionRow(
                                subscription = subscription,
                                marqueeTitles = marqueeTitles,
                                reordering = reordering,
                                dragging = isDragging,
                                dragHandleModifier = dragHandleModifier,
                                modifier = Modifier.animateItem(),
                                onClick = { onOpen(subscription) },
                                onPlayLatest = { onPlayLatest(subscription) },
                                onEdit = { onEdit(subscription) },
                                onDelete = { onDelete(subscription) },
                            )
                        } else {
                            PodcastSubscriptionRow(
                                subscription = subscription,
                                marqueeTitles = marqueeTitles,
                                reordering = reordering,
                                dragging = isDragging,
                                dragHandleModifier = dragHandleModifier,
                                modifier = Modifier.animateItem(),
                                onClick = { onOpen(subscription) },
                                onPlayLatest = { onPlayLatest(subscription) },
                                onEdit = { onEdit(subscription) },
                                onDelete = { onDelete(subscription) },
                            )
                        }
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
 * 点标题/文字进期数列表；长按弹出「编辑 / 删除」菜单。
 * 拖动排序时左侧出现拖动手柄，其余交互暂时禁用。
 */
@Composable
private fun PodcastSubscriptionRow(
    subscription: PodcastSubscription,
    marqueeTitles: Boolean,
    reordering: Boolean,
    dragging: Boolean,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayLatest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val subtitle = subscriptionSubtitle(subscription)

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(RadioSurface, RoundedCornerShape(RadioCorners.medium))
                .padding(horizontal = RadioSpacing.medium, vertical = RadioSpacing.small),
        ) {
            if (reordering) {
                SubscriptionDragHandle(
                    dragging = dragging,
                    modifier = dragHandleModifier,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.combinedClickable(
                    enabled = !reordering,
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
                        enabled = !reordering,
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
            if (!reordering) {
                Box(modifier = Modifier.size(1.dp)) {
                    SubscriptionMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

/** 拖动排序时订阅行左侧的拖动手柄，样式与电台列表一致 */
@Composable
private fun SubscriptionDragHandle(
    dragging: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = RadioSpacing.small)
            .size(RadioSizes.iconLarge)
            .then(modifier),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_drag_indicator),
            contentDescription = null,
            tint = if (dragging) RadioPrimary else RadioTextMuted,
            modifier = Modifier.size(RadioSizes.icon),
        )
    }
}

/**
 * 紧凑订阅行：单行「播放 · 标题 · 期数与更新时间」，无封面。
 * 点标题进期数列表，点左侧播放图标播最新一期；长按出菜单（编辑 / 删除）。
 */
@Composable
private fun CompactSubscriptionRow(
    subscription: PodcastSubscription,
    marqueeTitles: Boolean,
    reordering: Boolean,
    dragging: Boolean,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayLatest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val subtitle = subscriptionSubtitle(subscription)

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = COMPACT_ROW_MIN_HEIGHT)
                .background(RadioSurface, RoundedCornerShape(RadioCorners.medium))
                .padding(end = RadioSpacing.small),
        ) {
            if (reordering) {
                SubscriptionDragHandle(
                    dragging = dragging,
                    modifier = dragHandleModifier,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(COMPACT_PLAY_TOUCH_SIZE)
                    .combinedClickable(
                        enabled = !reordering,
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
                        enabled = !reordering,
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
            if (!reordering) {
                Box(modifier = Modifier.size(1.dp)) {
                    SubscriptionMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
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

/** 纯封面网格：自适应列数，标题叠在封面底部，点击进节目列表 */
@Composable
private fun PodcastCoverGrid(
    subscriptions: List<PodcastSubscription>,
    onClick: (PodcastSubscription) -> Unit,
    onEdit: (PodcastSubscription) -> Unit,
    onDelete: (PodcastSubscription) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = RadioSpacing.medium,
            end = RadioSpacing.medium,
            top = RadioSpacing.small,
            // 底部避让右下角的一键刷新 FAB
            bottom = RadioSizes.fabListClearance,
        ),
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.small),
        verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
    ) {
        gridItems(subscriptions, key = { it.feedUrl }) { subscription ->
            PodcastCoverTile(
                subscription = subscription,
                onClick = { onClick(subscription) },
                onEdit = { onEdit(subscription) },
                onDelete = { onDelete(subscription) },
            )
        }
    }
}

@Composable
private fun PodcastCoverTile(
    subscription: PodcastSubscription,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(RadioCorners.small))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            ),
    ) {
        StationArtworkFill(
            imageUrl = subscription.imageUrl,
            contentDescription = subscription.title,
            modifier = Modifier.fillMaxSize(),
        )
        // 底部渐变蒙层 + 最多两行标题：任何封面下都保证可读
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.compact),
        ) {
            Text(
                text = subscription.title,
                color = Color.White,
                fontSize = RadioTextSizes.caption,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SubscriptionMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }
}

/** 长按菜单：编辑 / 删除。标准与紧凑两种行共用 */
@Composable
private fun SubscriptionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
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

/**
 * 期数页（按设计稿）：状态 chips + 按月分组 + 页头 ⋮ 菜单 + 多选批量操作。
 *
 * 交互：
 * - 单击播放；长按打开「单集操作」底部弹层（收藏 / 下载 / 标记已听 / 复制链接）。
 * - 左滑任意一期直接进入多选并选中该期；也可以 ⋮ → 多选 进入。
 * - 多选中单击逐期切换，按住某行上下滑动可把经过的行连续选上。
 *
 * 批量动作只作用于**当前可见**的选中项：被 chips / 搜索过滤掉的不改，避免无感修改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastEpisodesScreen(
    subscription: PodcastSubscription,
    playingEpisodeId: String?,
    loading: Boolean,
    oldestFirst: Boolean = false,
    onOldestFirstChange: (Boolean) -> Unit = {},
    marqueeTitles: Boolean = false,
    searchOpen: Boolean = false,
    searchQuery: String = "",
    onSearchOpenChange: (Boolean) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onBack: () -> Unit,
    /** 下拉刷新；顶栏 ⋮ 的「刷新节目」由外层直接触发，不经本页 */
    onRefresh: () -> Unit,
    onEpisodeClick: (PodcastSubscription, PodcastEpisode) -> Unit,
    onToggleFavorite: (PodcastEpisode) -> Unit = {},
    downloadStates: Map<String, PodcastDownloadState> = emptyMap(),
    onDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onCancelDownload: (PodcastEpisode) -> Unit = {},
    onDeleteDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    onExportDownload: (PodcastSubscription, PodcastEpisode) -> Unit = { _, _ -> },
    /** 编辑 / 退订入口已移到顶栏 ⋮ 菜单（OmniBeatApp 直接调），这两个保留给兼容 */
    onEdit: () -> Unit = {},
    onUnsubscribe: () -> Unit = {},
    /** 多选批量下载 / 批量已听未听：Wi-Fi 确认与落库都由外层统一处理 */
    onDownloadMany: (PodcastSubscription, List<PodcastEpisode>) -> Unit = { _, _ -> },
    onMarkManyPlayed: (PodcastSubscription, List<PodcastEpisode>, Boolean) -> Unit =
        { _, _, _ -> },
    /**
     * 顶栏 ⋮「多选」的触发计数器：值变大即进入多选。
     * 菜单入口在全局 topbar（计数器由外层持有），多选状态仍在本页。
     */
    multiSelectRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    // 收听状态筛选（chips）：计数按全部期数算，搜索词只影响列表，不改数字
    var filter by remember(subscription.feedUrl) { mutableStateOf(EpisodeFilter.All) }
    // 多选状态跟着订阅走：换订阅必须从干净状态开始
    var selecting by remember(subscription.feedUrl) { mutableStateOf(false) }
    var selectedGuids by remember(subscription.feedUrl) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var selectAllMenuOpen by remember { mutableStateOf(false) }
    // 长按某一期打开的单集操作弹层
    var sheetEpisode by remember(subscription.feedUrl) { mutableStateOf<PodcastEpisode?>(null) }
    val context = LocalContext.current
    val linkCopiedText = stringResource(R.string.podcast_link_copied)

    fun exitSelection() {
        selecting = false
        selectedGuids = emptySet()
        selectAllMenuOpen = false
    }

    // 返回键先退多选、再关搜索，最后才交给外层退出这个订阅
    BackHandler(enabled = selecting) { exitSelection() }
    BackHandler(enabled = !selecting && searchOpen) { onSearchOpenChange(false) }

    // 顶栏 ⋮「多选」：计数器变化即进入（不预选任何一期，由点选 / 滑动连选补齐）
    LaunchedEffect(multiSelectRequest) {
        if (multiSelectRequest > 0) selecting = true
    }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) searchFocusRequester.requestFocus()
    }

    // 期数可能有几百条：排序只影响展示顺序，不改写订阅数据本身
    val orderedEpisodes = if (oldestFirst) {
        subscription.episodes.asReversed()
    } else {
        subscription.episodes
    }
    val unplayedCount = orderedEpisodes.count { !it.completed && it.positionMs <= 0L }
    val inProgressCount = orderedEpisodes.count { !it.completed && it.positionMs > 0L }
    val completedCount = orderedEpisodes.count { it.completed }
    val filteredByState = when (filter) {
        EpisodeFilter.All -> orderedEpisodes
        EpisodeFilter.Unplayed -> orderedEpisodes.filter { !it.completed && it.positionMs <= 0L }
        EpisodeFilter.InProgress -> orderedEpisodes.filter { !it.completed && it.positionMs > 0L }
        EpisodeFilter.Completed -> orderedEpisodes.filter { it.completed }
    }
    // 本地按标题过滤：一千多期时靠滑根本找不到，输入关键词即时缩小范围
    val query = searchQuery.trim()
    val searching = query.isNotEmpty()
    val episodes = if (searching) {
        filteredByState.filter { it.title.contains(query, ignoreCase = true) }
    } else {
        filteredByState
    }
    // 按月分组：几百期只靠日期后缀很难定位，分组头给出参照
    val listItems = remember(episodes) { buildEpisodeListItems(episodes) }
    // 批量操作只认当前可见的选中项：被过滤掉的不该被无感修改
    val selectedEpisodes = episodes.filter { it.guid in selectedGuids }

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
            if (selecting) {
                // 多选页头：× 退出、已选数量与总数、全选▾（全部 / 最近 N 期 / 全部未下载）
                IconButton(
                    onClick = { exitSelection() },
                    modifier = Modifier.size(HEADER_ICON_BUTTON_SIZE),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.podcast_selection_cancel),
                        tint = RadioText,
                        modifier = Modifier.size(RadioSizes.iconCompact),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.podcast_selection_count,
                        selectedEpisodes.size,
                    ),
                    color = RadioText,
                    fontSize = RadioTextSizes.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.width(RadioSpacing.extraSmall))
                Text(
                    text = stringResource(R.string.podcast_selection_total, episodes.size),
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.badge,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                // 预选开关：四个互斥预设，点哪项选中集就覆盖成哪项，
                // 再点一次同一项 = 全部取消（toggle）；按钮文案跟随当前生效的预设
                val allGuids = episodes.mapTo(mutableSetOf()) { it.guid }
                val recent10Guids = episodes.take(10).mapTo(mutableSetOf()) { it.guid }
                val recent25Guids = episodes.take(25).mapTo(mutableSetOf()) { it.guid }
                val undownloadedGuids = episodes
                    .filter { it.localFileName == null }
                    .mapTo(mutableSetOf()) { it.guid }
                val selectAllLabel = when {
                    selectedGuids.isEmpty() -> stringResource(R.string.podcast_selection_all)
                    selectedGuids == allGuids -> stringResource(R.string.podcast_select_all_items)
                    selectedGuids == recent10Guids ->
                        stringResource(R.string.podcast_select_recent, 10)

                    selectedGuids == recent25Guids ->
                        stringResource(R.string.podcast_select_recent, 25)

                    selectedGuids == undownloadedGuids ->
                        stringResource(R.string.podcast_select_undownloaded)

                    // 手动混选：不冒充任何预设名，回落到默认动作名
                    else -> stringResource(R.string.podcast_selection_all)
                }
                Box {
                    Text(
                        text = "$selectAllLabel ▾",
                        color = RadioPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(RadioPrimary.copy(alpha = 0.16f))
                            .clickable { selectAllMenuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    DropdownMenu(
                        expanded = selectAllMenuOpen,
                        onDismissRequest = { selectAllMenuOpen = false },
                        containerColor = RadioSurface,
                    ) {
                        // 当前生效的预设打勾，反选（再点一次）也能从勾选上看出来
                        @Composable
                        fun presetItem(label: String, target: Set<String>) {
                            val active = selectedGuids == target
                            DropdownMenuItem(
                                text = { Text(label) },
                                trailingIcon = if (active) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = RadioPrimary,
                                            modifier = Modifier.size(RadioSizes.iconInline),
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    selectAllMenuOpen = false
                                    selectedGuids = if (active) emptySet() else target
                                },
                            )
                        }
                        presetItem(
                            label = stringResource(R.string.podcast_select_all_items),
                            target = allGuids,
                        )
                        presetItem(
                            label = stringResource(R.string.podcast_select_recent, 10),
                            target = recent10Guids,
                        )
                        presetItem(
                            label = stringResource(R.string.podcast_select_recent, 25),
                            target = recent25Guids,
                        )
                        presetItem(
                            label = stringResource(R.string.podcast_select_undownloaded),
                            target = undownloadedGuids,
                        )
                    }
                }
            } else {
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
                StationArtwork(imageUrl = subscription.imageUrl, size = 52.dp)
                Column(
                    verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    // 标题必须单行：一旦换行，头部就比封面还高，把下面列表往下顶
                    SubscriptionTitle(
                        title = subscription.title,
                        marqueeTitles = marqueeTitles,
                        fontSize = RadioTextSizes.body,
                    )
                    Text(
                        text = subscriptionSubtitle(subscription),
                        color = RadioTextMuted,
                        fontSize = RadioTextSizes.badge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 搜索与 ⋮ 菜单（排序 / 刷新 / 编辑 / 多选 / 退订）已移到全局 topbar，
                // 节目名行只保留返回 + 封面 + 标题
            }
        }

        // 状态 chips：计数按全部期数，只筛列表不动数据
        if (!selecting) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            ) {
                EpisodeFilterChip(
                    label = stringResource(R.string.podcast_filter_all, orderedEpisodes.size),
                    selected = filter == EpisodeFilter.All,
                    onClick = { filter = EpisodeFilter.All },
                )
                EpisodeFilterChip(
                    label = stringResource(
                        R.string.podcast_filter_unplayed,
                        unplayedCount,
                    ),
                    selected = filter == EpisodeFilter.Unplayed,
                    onClick = { filter = EpisodeFilter.Unplayed },
                )
                EpisodeFilterChip(
                    label = stringResource(
                        R.string.podcast_filter_in_progress,
                        inProgressCount,
                    ),
                    selected = filter == EpisodeFilter.InProgress,
                    onClick = { filter = EpisodeFilter.InProgress },
                )
                EpisodeFilterChip(
                    label = stringResource(
                        R.string.podcast_filter_completed,
                        completedCount,
                    ),
                    selected = filter == EpisodeFilter.Completed,
                    onClick = { filter = EpisodeFilter.Completed },
                )
            }
        } else {
            // 多选提示：按住上下滑动可连续选择
            Text(
                text = stringResource(R.string.podcast_selection_hint),
                color = RadioTextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 7.dp),
            )
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

        val listState = rememberLazyListState()
        // 太短的列表不需要快速定位，保持原布局
        val showScrollbar = listItems.size > EPISODE_SCROLLBAR_MIN_COUNT
        var scrollbarIndex by remember { mutableStateOf<Int?>(null) }
        // 多选连选拖到屏幕上下边缘时自动滚屏：否则一屏之外的期永远选不到
        var selectionAutoScroll by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(selectionAutoScroll) {
            if (selectionAutoScroll == 0f) return@LaunchedEffect
            while (true) {
                listState.scrollBy(selectionAutoScroll * EPISODE_SELECT_SCROLL_SPEED)
                delay(SELECTION_AUTOSCROLL_FRAME_MS)
            }
        }
        // 切换排序 / 筛选 / 搜索词后回到顶部，否则停在原位置会让人以为列表没变
        LaunchedEffect(oldestFirst, filter, query) { listState.scrollToItem(0) }

        // 下拉刷新。空态也放进 LazyColumn：没有可滚动容器时下拉手势收不到。
        // 多选态不包 PullToRefreshBox：连选的长按拖动会被它误判成下拉刷新；
        // 同时禁用列表的手势滚动，把上下滑交给连选手势。
        // weight(1f)：给多选底部操作栏留出空间，否则 fillMaxSize 会把整列占满。
        val episodeListContent: @Composable () -> Unit = {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // 多选态禁用手势滚动：LazyColumn 的 scrollable 与连选手势同在列表节点，
                    // Main pass 更"叶"的 scrollable 会先消费拖动，导致长按连选失效、
                    // 事件继续冒泡触发下拉刷新。边缘自动滚屏是编程 scrollBy，不受影响。
                    userScrollEnabled = !selecting,
                    // 只让出 3dp 视觉空隙；滚动条触摸区会压住行尾少量区域，但按钮中心仍可点
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = if (showScrollbar) EPISODE_SCROLLBAR_INSET else 0.dp)
                        // 多选时按住某行上下滑动：把手指经过的行连续选上
                        .pointerInput(selecting, listItems) {
                            if (!selecting) return@pointerInput
                            // 上一步落点：只处理两步之间的增量区间，拖回去才能逐项还原
                            var lastIndex: Int? = null
                            val edgePx = EPISODE_SELECT_EDGE_ZONE.toPx()

                            fun guidAt(index: Int?): String? {
                                if (index == null) return null
                                val row = listItems.getOrNull(index) as? EpisodeListItem.Row
                                return row?.episode?.guid
                            }

                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val index = lazyItemIndexAt(listState, offset.y)
                                    lastIndex = index
                                    guidAt(index)?.let { guid ->
                                        selectedGuids = if (guid in selectedGuids) {
                                            selectedGuids - guid
                                        } else {
                                            selectedGuids + guid
                                        }
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val to = lazyItemIndexAt(listState, change.position.y)
                                    val from = lastIndex
                                    if (from != null && to != null && to != from) {
                                        val step = if (to > from) 1 else -1
                                        var i = from
                                        while (i != to) {
                                            i += step
                                            guidAt(i)?.let { guid ->
                                                // 常规划动选择：逐项看自己的状态翻转——
                                                // 未选→已选，已选→未选（与单击切换同一套语义）
                                                selectedGuids = if (guid in selectedGuids) {
                                                    selectedGuids - guid
                                                } else {
                                                    selectedGuids + guid
                                                }
                                            }
                                        }
                                    }
                                    lastIndex = to
                                    // 拖到上下边缘：交给自动滚屏循环处理
                                    selectionAutoScroll = when {
                                        change.position.y < edgePx -> -1f
                                        change.position.y > size.height - edgePx -> 1f
                                        else -> 0f
                                    }
                                },
                                onDragEnd = {
                                    lastIndex = null
                                    selectionAutoScroll = 0f
                                },
                                onDragCancel = {
                                    lastIndex = null
                                    selectionAutoScroll = 0f
                                },
                            )
                        },
                    contentPadding = PaddingValues(bottom = RadioSpacing.small),
                ) {
                    if (listItems.isEmpty()) {
                        item {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(horizontal = RadioSpacing.page),
                            ) {
                                Text(
                                    text = when {
                                        searching -> stringResource(
                                            R.string.podcast_episode_search_empty,
                                        )

                                        filter != EpisodeFilter.All ->
                                            stringResource(R.string.podcast_episodes_empty)

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
                        itemsIndexed(
                            items = listItems,
                            key = { _, item ->
                                when (item) {
                                    is EpisodeListItem.Month -> "m:${item.year}-${item.month}"
                                    is EpisodeListItem.Row -> "e:${item.episode.guid}"
                                }
                            },
                        ) { index, item ->
                            when (item) {
                                is EpisodeListItem.Month -> EpisodeMonthHeader(item)
                                is EpisodeListItem.Row -> {
                                    val episode = item.episode
                                    PodcastEpisodeListRow(
                                        episode = episode,
                                        playing = episode.guid == playingEpisodeId,
                                        selected = if (selecting) {
                                            episode.guid in selectedGuids
                                        } else {
                                            null
                                        },
                                        downloadState = downloadStates[episode.guid],
                                        // 分组头下方第一行不画分隔线
                                        showDivider = index > 0 &&
                                            listItems[index - 1] is EpisodeListItem.Row,
                                        marqueeTitle = marqueeTitles,
                                        onClick = {
                                            if (selecting) {
                                                val next = if (
                                                    episode.guid in selectedGuids
                                                ) {
                                                    selectedGuids - episode.guid
                                                } else {
                                                    selectedGuids + episode.guid
                                                }
                                                if (next.isEmpty()) {
                                                    exitSelection()
                                                } else {
                                                    selectedGuids = next
                                                }
                                            } else {
                                                onEpisodeClick(subscription, episode)
                                            }
                                        },
                                        // 多选中长按留给「上下滑动连续选择」，不再弹单集菜单
                                        onLongClick = if (selecting) {
                                            null
                                        } else {
                                            ({ sheetEpisode = episode })
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // 几百期时靠右侧拖动快速定位，拖动中在列表中央显示 x / 总数
                if (showScrollbar) {
                    OmniLazyScrollbar(
                        listState = listState,
                        itemCount = listItems.size,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        onDraggingIndexChange = { scrollbarIndex = it },
                    )
                }
                scrollbarIndex?.let { index ->
                    Text(
                        text = "${index + 1} / ${listItems.size}",
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selecting) {
                episodeListContent()
            } else {
                PullToRefreshBox(
                    isRefreshing = loading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    episodeListContent()
                }
            }
        }

        // 多选底部操作栏：信息行 + 三个批量按钮，Wi-Fi 确认与落库都在外层
        if (selecting) {
            HorizontalDivider(color = RadioOutline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
            ) {
                Text(
                    text = selectionSummary(selectedEpisodes),
                    color = RadioTextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val manyEnabled = selectedEpisodes.isNotEmpty()
                    EpisodeBatchBarButton(
                        text = stringResource(R.string.podcast_batch_mark_played),
                        primary = false,
                        enabled = manyEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // 先把选中项快照下来：exitSelection 会清空 selectedGuids
                            val targets = selectedEpisodes
                            exitSelection()
                            onMarkManyPlayed(subscription, targets, true)
                        },
                    )
                    EpisodeBatchBarButton(
                        text = stringResource(R.string.podcast_batch_mark_unplayed),
                        primary = false,
                        enabled = manyEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val targets = selectedEpisodes
                            exitSelection()
                            onMarkManyPlayed(subscription, targets, false)
                        },
                    )
                    EpisodeBatchBarButton(
                        text = stringResource(R.string.podcast_batch_download),
                        primary = true,
                        enabled = manyEnabled,
                        icon = painterResource(R.drawable.ic_download),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val targets = selectedEpisodes
                            exitSelection()
                            onDownloadMany(subscription, targets)
                        },
                    )
                }
            }
        }
    }

    // 长按某一期：单集操作（收藏 / 下载 / 标记已听 / 复制链接）
    sheetEpisode?.let { episode ->
        val episodeDownloadState = downloadStates[episode.guid]
        EpisodeActionsSheet(
            episodeTitle = episode.title,
            episode = episode,
            downloadState = episodeDownloadState,
            onDismiss = { sheetEpisode = null },
            onToggleFavorite = {
                sheetEpisode = null
                onToggleFavorite(episode)
            },
            onDownloadAction = {
                sheetEpisode = null
                when {
                    episodeDownloadState is PodcastDownloadState.Downloading ->
                        onCancelDownload(episode)

                    episode.localFileName != null -> onDeleteDownload(subscription, episode)
                    else -> onDownload(subscription, episode)
                }
            },
            onTogglePlayed = {
                sheetEpisode = null
                onMarkManyPlayed(subscription, listOf(episode), !episode.completed)
            },
            onCopyLink = {
                sheetEpisode = null
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                if (clipboard != null && episode.audioUrl.isNotBlank()) {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("episode", episode.audioUrl),
                    )
                    Toast.makeText(
                        context,
                        linkCopiedText,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
}

/** 单集操作弹层里的一个选项：图标 + 文案，整行可点 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeActionsSheet(
    episodeTitle: String,
    episode: PodcastEpisode,
    downloadState: PodcastDownloadState?,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloadAction: () -> Unit,
    onTogglePlayed: () -> Unit,
    onCopyLink: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = RadioSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.podcast_episode_actions),
                color = RadioText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = episodeTitle,
                color = RadioTextMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            EpisodeSheetOption(
                icon = painterResource(
                    if (episode.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                ),
                label = stringResource(
                    if (episode.favorite) R.string.podcast_favorite_remove
                    else R.string.podcast_favorite_add,
                ),
                iconTint = if (episode.favorite) RadioPrimary else RadioTextMuted,
                onClick = onToggleFavorite,
            )
            val downloading = downloadState is PodcastDownloadState.Downloading
            EpisodeSheetOption(
                icon = painterResource(
                    when {
                        downloading -> R.drawable.ic_close
                        episode.localFileName != null -> R.drawable.ic_delete
                        else -> R.drawable.ic_download
                    },
                ),
                label = stringResource(
                    when {
                        downloading -> R.string.podcast_download_cancel
                        episode.localFileName != null -> R.string.podcast_download_delete
                        else -> R.string.podcast_batch_download
                    },
                ),
                onClick = onDownloadAction,
            )
            EpisodeSheetOption(
                icon = painterResource(
                    if (episode.completed) {
                        R.drawable.ic_radio_button_unchecked
                    } else {
                        R.drawable.ic_check
                    },
                ),
                label = stringResource(
                    if (episode.completed) R.string.podcast_mark_unplayed
                    else R.string.podcast_mark_played,
                ),
                onClick = onTogglePlayed,
            )
            EpisodeSheetOption(
                icon = painterResource(R.drawable.ic_link),
                label = stringResource(R.string.podcast_copy_link),
                onClick = onCopyLink,
            )
        }
    }
}

@Composable
private fun EpisodeSheetOption(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    iconTint: Color = RadioTextMuted,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(RadioSizes.iconInline),
        )
        Text(text = label, color = RadioText, fontSize = RadioTextSizes.caption)
    }
}
/** 期数状态筛选（chips）：全部 / 未听 / 在听 / 听完 */
private enum class EpisodeFilter { All, Unplayed, InProgress, Completed }

/** 期数列表的一行：月份分组头或某一期 */
private sealed interface EpisodeListItem {
    data class Month(val year: Int, val month: Int) : EpisodeListItem
    data class Row(val episode: PodcastEpisode) : EpisodeListItem
}

/** 按展示顺序插入月份分组头；拿不到发布时间的期不分组 */
private fun buildEpisodeListItems(episodes: List<PodcastEpisode>): List<EpisodeListItem> {
    val items = ArrayList<EpisodeListItem>(episodes.size + 8)
    var lastYear = -1
    var lastMonth = -1
    episodes.forEach { episode ->
        val (year, month) = episodeYearMonth(episode.publishedAtMs)
        if (year > 0 && (year != lastYear || month != lastMonth)) {
            items += EpisodeListItem.Month(year, month)
            lastYear = year
            lastMonth = month
        }
        items += EpisodeListItem.Row(episode)
    }
    return items
}

private fun episodeYearMonth(epochMs: Long): Pair<Int, Int> {
    if (epochMs <= 0L) return 0 to 0
    return runCatching {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        date.year to date.monthValue
    }.getOrDefault(0 to 0)
}

/** 手指落点（相对列表顶部）落在第几个 lazy item 上：多选拖拽靠它定位行 */
private fun lazyItemIndexAt(listState: LazyListState, y: Float): Int? {
    return listState.layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?.index
}

/** 月份分组头：2026 年 10 月 + 一条淡分隔线 */
@Composable
private fun EpisodeMonthHeader(item: EpisodeListItem.Month) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 7.dp),
    ) {
        Text(
            text = stringResource(R.string.podcast_month_header, item.year, item.month),
            color = Color(0xFF928CA2),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = RadioOutline.copy(alpha = 0.6f))
    }
}

/**
 * 期数行（设计稿）：标题单行，meta 行内联日期 / 时长 / 进度 / 徽标 / 收藏，底部 2dp 进度条。
 * 行内不再放收藏与下载按钮——长按弹「单集操作」；多选从页头 ⋮ 菜单进入。
 */
@Composable
private fun PodcastEpisodeListRow(
    episode: PodcastEpisode,
    playing: Boolean,
    /** null = 不在多选；true / false = 是否选中 */
    selected: Boolean?,
    downloadState: PodcastDownloadState?,
    showDivider: Boolean,
    /** 超长标题滚动显示（跟随设置里的「标题滚动」开关）；false 维持截断 */
    marqueeTitle: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val primary = RadioPrimary
    val totalSec = episode.durationSec
    val listenedSec = (episode.positionMs / 1000L).toInt().coerceAtLeast(0)
    val inProgress = episode.positionMs > 0L && !episode.completed
    val percent = if (totalSec > 0 && episode.positionMs > 0L) {
        ((episode.positionMs * 100) / (totalSec * 1000L)).toInt().coerceIn(0, 100)
    } else {
        0
    }
    val downloaded = episode.localFileName != null
    val downloading = downloadState is PodcastDownloadState.Downloading

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(color = RadioOutline.copy(alpha = 0.55f), thickness = 1.dp)
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        if (playing) {
                            listOf(primary.copy(alpha = 0.11f), Color.Transparent)
                        } else {
                            listOf(Color.Transparent, Color.Transparent)
                        },
                    ),
                )
                .background(
                    if (selected == true) primary.copy(alpha = 0.07f) else Color.Transparent,
                )
                .drawBehind {
                    if (playing) {
                        val top = 9.dp.toPx()
                        drawRoundRect(
                            color = primary,
                            topLeft = Offset(0f, top),
                            size = Size(2.dp.toPx(), size.height - top * 2),
                            cornerRadius = CornerRadius(1.dp.toPx()),
                        )
                    }
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 13.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    color = if (playing) primary else RadioText,
                    fontSize = 15.sp,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    // 滚动模式下必须 Clip：Ellipsis 会抢在 marquee 之前把文本截断
                    overflow = if (marqueeTitle) {
                        TextOverflow.Clip
                    } else {
                        TextOverflow.Ellipsis
                    },
                    modifier = if (marqueeTitle) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1_500,
                        )
                    } else {
                        Modifier
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                ) {
                    val date = formatEpisodeDateShort(episode.publishedAtMs)
                    if (date.isNotEmpty()) {
                        Text(
                            text = date,
                            color = Color(0xFF9A94AA),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                        )
                        EpisodeMetaDot()
                    }
                    val duration = formatDuration(totalSec)
                    if (duration.isNotEmpty()) {
                        Text(
                            text = duration,
                            color = RadioTextMuted,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                        )
                        EpisodeMetaDot()
                    }
                    when {
                        // 在听：百分比 + 已听/总长（旧版写法，一眼看到听到哪儿还剩多少）；
                        // 总长缺省（RSS 没给）就只显示已听时长
                        inProgress -> Text(
                            text = if (percent > 0) {
                                stringResource(
                                    R.string.podcast_progress_ratio,
                                    percent,
                                    formatDuration(listenedSec),
                                    formatDuration(totalSec),
                                )
                            } else {
                                formatDuration(listenedSec)
                            },
                            color = primary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                        )

                        // 听完：pill「听完」——主色 + 半粗，与未听的中性 pill 拉开层级
                        episode.completed -> EpisodeBadge(
                            text = stringResource(R.string.podcast_completed),
                            color = RadioPrimary,
                            bold = true,
                        )

                        // 未听：pill「未听」——muted 中性色，默认态不抢眼
                        else -> EpisodeBadge(
                            text = stringResource(R.string.podcast_badge_unplayed),
                            color = RadioTextMuted,
                            bold = true,
                        )
                    }
                    when {
                        downloading -> EpisodeBadge(
                            text = stringResource(R.string.podcast_badge_downloading),
                            color = RadioTextMuted,
                        )

                        downloaded -> EpisodeBadge(
                            text = stringResource(R.string.podcast_download_downloaded),
                            color = RadioTextMuted,
                        )
                    }
                    // 听完不再挂「已听完」徽标：meta 里的 100% 已经说明状态
                    if (episode.favorite) {
                        Icon(
                            painter = painterResource(R.drawable.ic_favorite),
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            // 勾选框在行尾右侧：不挡标题，也与「行尾操作区」的视觉习惯一致
            if (selected != null) {
                EpisodeSelectBox(
                    checked = selected,
                    modifier = Modifier.padding(start = 9.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EpisodeMetaDot() {
    Box(
        modifier = Modifier
            .size(2.dp)
            .clip(CircleShape)
            .background(Color(0xFF6D677B)),
    )
}

/** 多选行首的方形勾选框（19dp，圆角 6dp） */
@Composable
private fun EpisodeSelectBox(checked: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(19.dp)
            .clip(shape)
            .background(if (checked) RadioPrimary else Color.Transparent)
            .border(
                width = 1.6.dp,
                color = if (checked) RadioPrimary else Color(0xFF5C5668),
                shape = shape,
            ),
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = RadioOnPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * meta 行的小徽标：已下载 / 未听 / 听完 / 下载中。
 * [bold] 给状态词（未听 / 听完）用：底色已经区分，字重再拉开一档。
 */
@Composable
private fun EpisodeBadge(text: String, color: Color, bold: Boolean = false) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 状态 chips：全部 / 未听 / 在听 / 听完，带数量 */
@Composable
private fun EpisodeFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) RadioOnPrimary else RadioTextMuted,
        fontSize = 11.5.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) RadioPrimary else RadioSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 多选底部按钮：已听 / 未听是次按钮，下载是主按钮 */
@Composable
private fun EpisodeBatchBarButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    val contentColor = when {
        !enabled -> RadioTextMuted
        primary -> RadioOnPrimary
        else -> RadioText
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (primary && enabled) RadioPrimary else RadioSurface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 多选信息行：已选数量 + 总时长（拿不到时长时只报数量） */
@Composable
private fun selectionSummary(episodes: List<PodcastEpisode>): String {
    val totalSec = episodes.sumOf { it.durationSec }
    if (totalSec <= 0) {
        return stringResource(R.string.podcast_selection_count, episodes.size)
    }
    val duration = if (totalSec >= 3600) {
        stringResource(
            R.string.podcast_duration_hours,
            String.format(Locale.US, "%.1f", totalSec / 3600f),
        )
    } else {
        stringResource(R.string.podcast_duration_minutes, totalSec / 60)
    }
    return stringResource(R.string.podcast_selection_summary, episodes.size, duration)
}

private val EPISODE_DATE_INLINE_FORMAT = DateTimeFormatter.ofPattern("MM-dd", Locale.ENGLISH)

/** meta 行的行内日期（MM-dd）：比完整日期省宽度 */
private fun formatEpisodeDateShort(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return runCatching {
        EPISODE_DATE_INLINE_FORMAT.withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
    }.getOrDefault("")
}

/** 期数超过这个数量才显示可拖动滚动条：太短的列表反而被它占掉右侧空间 */
private const val EPISODE_SCROLLBAR_MIN_COUNT = 20

/** 多选连选：拖到距屏幕上下边缘这个距离内开始自动滚屏 */
private val EPISODE_SELECT_EDGE_ZONE = 72.dp

/** 多选连选：自动滚屏每帧滚动的 px（16ms 一帧，合计约 840px/s） */
private const val EPISODE_SELECT_SCROLL_SPEED = 14f

/** 多选连选：自动滚屏的帧间隔 */
private const val SELECTION_AUTOSCROLL_FRAME_MS = 16L

/** 期数页头部的轻量图标按钮（返回 / 搜索 / ⋮）：40dp，比 53dp 的顶栏按钮小一档，少挤标题宽度 */
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

/**
 * 下载管理页（第二阶段·范围 A）：跨订阅的已下载单集列表。
 *
 * 纯本地数据（localFileName 非空的期），所以**不做下拉刷新**——没有可刷的东西。
 * 头部给总数与磁盘占用，右上角「全部删除」（确认弹窗）；列表复用期数行，
 * meta 行带订阅名且过长滚动（跨订阅时分不清是谁家的节目）。
 */
@Composable
fun DownloadsPage(
    downloads: List<Pair<PodcastSubscription, PodcastEpisode>>,
    playingEpisodeId: String?,
    onBack: () -> Unit,
    onEpisodeClick: (PodcastSubscription, PodcastEpisode) -> Unit,
    onToggleFavorite: (PodcastSubscription, PodcastEpisode) -> Unit,
    downloadStates: Map<String, PodcastDownloadState>,
    onDownload: (PodcastSubscription, PodcastEpisode) -> Unit,
    onCancelDownload: (PodcastEpisode) -> Unit,
    onDeleteDownload: (PodcastSubscription, PodcastEpisode) -> Unit,
    onExportDownload: (PodcastSubscription, PodcastEpisode) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 占用是文件系统实时值：按当前列表算一次；删除/新增经 DataStore 回流触发重组才重算。
    // 进行中的项还没落盘（localFileName 为 null），只统计已下载的部分。
    val completedCount = downloads.count { it.second.localFileName != null }
    val totalBytes = remember(downloads) {
        downloads.sumOf { (_, episode) ->
            episode.localFileName?.let { name ->
                PodcastDownloadManager.episodeDownloadedBytes(context, name)
            } ?: 0L
        }
    }
    // 副标题：有已下载才报「N 个节目 · 占用 XX」；只有进行中项时别说「0 个节目」
    val subtitle = if (completedCount > 0) {
        stringResource(R.string.downloads_summary, completedCount, formatFileSize(totalBytes))
    } else {
        stringResource(R.string.downloads_in_progress, downloads.size)
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
            // 轻量返回键：与期数页同款，把宽度让给标题
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
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.downloads_title),
                    color = RadioText,
                    fontSize = RadioTextSizes.stationTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.caption,
                    maxLines = 1,
                )
            }
            // 全部删除：危险操作，确认弹窗后执行。只有进行中项时隐藏（删了也是空转）
            if (completedCount > 0) {
                IconButton(
                    onClick = { confirmDeleteAll = true },
                    modifier = Modifier.size(HEADER_ICON_BUTTON_SIZE),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.downloads_delete_all),
                        tint = Color(0xFFFF5C6C),
                        modifier = Modifier.size(RadioSizes.iconCompact),
                    )
                }
            }
        }

        if (downloads.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = RadioSpacing.page),
            ) {
                Text(
                    text = stringResource(R.string.downloads_empty),
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
                items(downloads, key = { it.first.feedUrl + "|" + it.second.guid }) { (subscription, episode) ->
                    PodcastEpisodeRow(
                        episode = episode,
                        playing = episode.guid == playingEpisodeId,
                        onClick = { onEpisodeClick(subscription, episode) },
                        onToggleFavorite = { onToggleFavorite(subscription, episode) },
                        // 跨订阅列表：meta 行带订阅名，过长滚动只一行
                        metaPrefix = subscription.title,
                        marqueeMeta = true,
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

    if (confirmDeleteAll) {
        OmniConfirmDialog(
            title = stringResource(R.string.dialog_delete_all_downloads_title),
            text = stringResource(
                R.string.dialog_delete_all_downloads_text,
                downloads.size,
                formatFileSize(totalBytes),
            ),
            confirmText = stringResource(R.string.downloads_delete_all),
            destructive = true,
            onDismiss = { confirmDeleteAll = false },
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAll()
            },
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
    /** 下载管理页用：meta 行最前拼接订阅名（跨订阅列表分清是谁家的节目） */
    metaPrefix: String? = null,
    /** meta 行过长时跑马灯滚动（仍只一行）；false 维持省略号 */
    marqueeMeta: Boolean = false,
) {
    // 进度文案与期数页统一：在听「百分比 已听/总长」、听完「听完」、未听「未听」。
    // 这里是单行 Text 拿不到 pill 底色，状态词用纯文字，靠文案本身区分。
    val currentSec = (episode.positionMs / 1000L).toInt()
    val totalSec = episode.durationSec
    val progressHasTotal = episode.positionMs > 0L && !episode.completed && totalSec > 0
    val progressText = when {
        episode.positionMs > 0L && !episode.completed -> {
            val percent = if (totalSec > 0) {
                (episode.positionMs * 100 / (totalSec * 1000L)).toInt().coerceIn(0, 100)
            } else {
                0
            }
            if (percent > 0) {
                stringResource(
                    R.string.podcast_progress_ratio,
                    percent,
                    formatDuration(currentSec),
                    formatDuration(totalSec),
                )
            } else {
                formatDuration(currentSec)
            }
        }

        episode.completed -> stringResource(R.string.podcast_completed)
        else -> stringResource(R.string.podcast_badge_unplayed)
    }
    // 在听且进度文案已含总长时，不再单独显示一份，避免「40:13 · 13% 5:56/40:13」重复
    val meta = listOfNotNull(
        metaPrefix?.takeIf { it.isNotBlank() },
        formatEpisodeDate(episode.publishedAtMs),
        if (progressHasTotal) "" else formatDuration(totalSec),
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
                    // 下载管理页的 meta 带订阅名，容易超长：开启跑马灯滚动而不是截断
                    overflow = if (marqueeMeta) TextOverflow.Clip else TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = if (marqueeMeta) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            repeatDelayMillis = 1_500,
                        )
                    } else {
                        Modifier
                    },
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
                    // HLS 源的下载物是「播放列表 + 分片目录」：单独导出 playlist 是残废的
                    // （分片没带走，出去放不了），所以只给删除；直文件源才给导出
                    val hlsDownload = episode.localFileName?.endsWith(".m3u8") == true
                    if (!hlsDownload) {
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
                    }
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

/**
 * 文件大小的人读格式（下载管理页占用用）：B / KB / MB / GB，一位小数。
 * 小于 1KB 按整数 B 显示（"512 B" 比 "512.0 B" 干净）。
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024
    return String.format(Locale.US, "%.1f GB", gb)
}
