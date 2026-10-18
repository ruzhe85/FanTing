package omnibeat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import omnibeat.app.R
import omnibeat.app.model.PODCAST_CHART_REGIONS
import omnibeat.app.podcast.PodcastChartEntry
import omnibeat.app.ui.appStringResource as stringResource

/** 序号列固定宽度：1～3 位数都右对齐，避免标题起始位置左右跳 */
private val RANK_COLUMN_WIDTH = 30.dp

/** 地区名在页头最多占这么宽，俄语等长名称截断，避免把标题挤没 */
private val REGION_LABEL_MAX_WIDTH = 116.dp

/**
 * 热门发现页：Apple 官方榜单，列表为「序号 + 封面 + 名称 + 作者」。
 *
 * 榜单本身没有 feedUrl，外层会批量 lookup 补全后写入 [PodcastChartEntry.feedUrl]，
 * 这里据此显示「已订阅」；点击条目后由外层做 lookup 再进订阅确认页。
 */
@Composable
fun PodcastChartPage(
    entries: List<PodcastChartEntry>,
    region: String,
    subscribedIds: Set<Long>,
    listState: LazyListState,
    loading: Boolean,
    errorText: String?,
    resolvingId: Long?,
    onRegionChange: (String) -> Unit,
    onOpen: (PodcastChartEntry) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // 首次加载（列表为空）只在下方显示动画；已有列表的刷新才在右上角转圈
    val refreshing = loading && entries.isNotEmpty()

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
            OmniIconButton(
                painter = painterResource(R.drawable.ic_arrow_back),
                onClick = onBack,
            )
            Text(
                text = stringResource(R.string.podcast_chart_title),
                color = RadioText,
                fontSize = RadioTextSizes.subtitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = RadioSpacing.small),
            )
            Spacer(modifier = Modifier.weight(1f))
            PodcastChartRegionMenu(
                region = region,
                onRegionChange = onRegionChange,
            )
            if (refreshing) {
                // 刷新中就地换成转圈：不改变按钮占位，列表也不会跳动
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.sizeIn(
                        minWidth = RadioSizes.button,
                        minHeight = RadioSizes.button,
                    ),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(RadioSizes.iconInline),
                        color = RadioTextMuted,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                OmniIconButton(
                    painter = painterResource(R.drawable.ic_sync),
                    onClick = onRefresh,
                    enabled = !loading,
                )
            }
        }

        when {
            entries.isEmpty() && loading -> ChartLoading()
            entries.isEmpty() && errorText != null -> SearchError(
                message = stringResource(R.string.podcast_chart_failed, errorText),
                onRetry = onRetry,
            )
            entries.isEmpty() -> SearchStatus(text = stringResource(R.string.podcast_chart_empty))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = RadioSpacing.medium,
                    vertical = RadioSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                itemsIndexed(
                    items = entries,
                    key = { _, entry -> entry.collectionId },
                ) { index, entry ->
                    PodcastChartRow(
                        rank = index + 1,
                        entry = entry,
                        subscribed = entry.collectionId in subscribedIds,
                        resolving = resolvingId == entry.collectionId,
                        // lookup 一次只放行一条，避免连点并发请求
                        enabled = resolvingId == null,
                        onClick = { onOpen(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastChartRow(
    rank: Int,
    entry: PodcastChartEntry,
    subscribed: Boolean,
    resolving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioCorners.medium))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.rowVertical),
    ) {
        // 前 3 名用主色 + 加粗，其余弱化为辅助信息
        Text(
            text = rank.toString(),
            color = if (rank <= 3) RadioPrimary else RadioTextMuted,
            fontSize = RadioTextSizes.caption,
            fontWeight = if (rank <= 3) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(RANK_COLUMN_WIDTH),
        )
        Spacer(modifier = Modifier.width(RadioSpacing.small))
        StationArtwork(imageUrl = entry.artworkUrl)
        Column(
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
            modifier = Modifier
                .weight(1f)
                .padding(start = RadioSpacing.medium),
        ) {
            Text(
                text = entry.title,
                color = RadioText,
                fontSize = RadioTextSizes.stationTitle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                // 超长标题自动滚一遍；短标题不溢出时不会动
                modifier = Modifier.basicMarquee(
                    iterations = 1,
                    repeatDelayMillis = 1_500,
                ),
            )
            if (entry.author.isNotBlank()) {
                Text(
                    text = entry.author,
                    color = RadioTextMuted,
                    fontSize = RadioTextSizes.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            resolving -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = RadioSpacing.small)
                    .size(RadioSizes.iconInline),
                color = RadioTextMuted,
                strokeWidth = 2.dp,
            )
            subscribed -> Text(
                text = stringResource(R.string.podcast_search_subscribed),
                color = RadioPrimary,
                fontSize = RadioTextSizes.badge,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = RadioSpacing.small)
                    .clip(RoundedCornerShape(RadioCorners.pill))
                    .background(RadioPrimary.copy(alpha = 0.18f))
                    .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.extraSmall),
            )
        }
    }
}

/** 页头地区切换：6 个固定地区，名称复用设置页已有的本地化文案 */
@Composable
private fun PodcastChartRegionMenu(
    region: String,
    onRegionChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(RadioCorners.pill))
                .clickable { expanded = true }
                .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.extraSmall),
        ) {
            Text(
                text = chartRegionLabel(region),
                color = RadioPrimary,
                fontSize = RadioTextSizes.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = REGION_LABEL_MAX_WIDTH),
            )
            Icon(
                painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                contentDescription = null,
                tint = RadioPrimary,
                modifier = Modifier.size(RadioSizes.iconTiny),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RadioSurface,
            shape = RoundedCornerShape(RadioCorners.medium),
        ) {
            PODCAST_CHART_REGIONS.forEach { option ->
                val selected = option == region
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                    contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                    text = {
                        Text(
                            text = chartRegionLabel(option),
                            color = RadioText,
                        )
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = RadioPrimary,
                                modifier = Modifier.size(RadioSizes.icon),
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onRegionChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun chartRegionLabel(region: String): String {
    return stringResource(
        when (region) {
            "cn" -> R.string.settings_podcast_search_region_cn
            "hk" -> R.string.settings_podcast_search_region_hk
            "tw" -> R.string.settings_podcast_search_region_tw
            "jp" -> R.string.settings_podcast_search_region_jp
            "gb" -> R.string.settings_podcast_search_region_gb
            else -> R.string.settings_podcast_search_region_us
        }
    )
}

@Composable
private fun ChartLoading() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(RadioSizes.loadingIcon),
                color = RadioTextMuted,
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.podcast_chart_loading),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodyLarge,
            )
        }
    }
}
