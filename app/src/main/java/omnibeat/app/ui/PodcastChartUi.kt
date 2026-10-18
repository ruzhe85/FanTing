package omnibeat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import omnibeat.app.R
import omnibeat.app.podcast.PodcastChartEntry
import omnibeat.app.ui.appStringResource as stringResource

/**
 * 热门发现页：Apple 官方榜单，极简列表（封面 + 名称 + 作者）。
 *
 * 榜单不含订阅地址，点击后由外层做一次 iTunes lookup 再进订阅确认页；
 * 这里只负责展示与行内 loading，不关心地区（地区由外层按界面语言决定）。
 */
@Composable
fun PodcastChartPage(
    entries: List<PodcastChartEntry>,
    loading: Boolean,
    errorText: String?,
    resolvingId: Long?,
    onOpen: (PodcastChartEntry) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

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
                modifier = Modifier
                    .weight(1f)
                    .padding(start = RadioSpacing.small),
            )
            // 刷新中就地换成转圈：不改变按钮占位，列表也不会跳动
            if (loading) {
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = RadioSpacing.medium,
                    vertical = RadioSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                items(entries, key = { it.collectionId }) { entry ->
                    PodcastChartRow(
                        entry = entry,
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
    entry: PodcastChartEntry,
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
        if (resolving) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = RadioSpacing.small)
                    .size(RadioSizes.iconInline),
                color = RadioTextMuted,
                strokeWidth = 2.dp,
            )
        }
    }
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
