package omnibeat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import omnibeat.app.R
import omnibeat.app.model.PODCAST_FEED_URL_MAX_LENGTH
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
 * 播客 Tab 的内容分发：没打开订阅时是订阅列表，打开后是该订阅的期数列表。
 * 期数页显示返回箭头，同时吞掉系统返回键（优先于外层抽屉/翻页的 BackHandler）。
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
    onPlayEpisode: (PodcastSubscription, PodcastEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = subscriptions.firstOrNull { it.feedUrl == openSubscriptionUrl }
    if (open == null) {
        PodcastSubscriptionsPage(
            subscriptions = subscriptions,
            onOpen = onOpenSubscription,
            onDelete = onDeleteSubscription,
            modifier = modifier,
        )
    } else {
        BackHandler { onCloseSubscription() }
        PodcastEpisodesPage(
            subscription = open,
            playingEpisodeId = playingEpisodeId,
            loading = loading,
            onBack = onCloseSubscription,
            onEpisodeClick = onPlayEpisode,
            modifier = modifier,
        )
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
    onDelete: (PodcastSubscription) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subscriptions.isEmpty()) {
        EmptyPodcastsState(modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = RadioSpacing.medium,
            vertical = RadioSpacing.small,
        ),
        verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
    ) {
        items(subscriptions, key = { it.feedUrl }) { subscription ->
            PodcastSubscriptionRow(
                subscription = subscription,
                onClick = { onOpen(subscription) },
                onDelete = { onDelete(subscription) },
            )
        }
    }
}

@Composable
private fun PodcastSubscriptionRow(
    subscription: PodcastSubscription,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val updatedText = if (subscription.lastFetchedAtMs > 0L) {
        stringResource(R.string.podcast_last_updated, formatStamp(subscription.lastFetchedAtMs))
    } else {
        stringResource(R.string.podcast_never_updated)
    }
    val countText = stringResource(R.string.podcast_episode_count, subscription.episodes.size)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(RadioSurface, RoundedCornerShape(RadioCorners.medium))
            .clickable(onClick = onClick)
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
                text = "$countText · $updatedText",
                color = RadioTextMuted,
                fontSize = RadioTextSizes.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OmniIconButton(
            painter = painterResource(R.drawable.ic_delete),
            onClick = onDelete,
            tint = RadioDanger,
        )
    }
}

@Composable
fun PodcastEpisodesPage(
    subscription: PodcastSubscription,
    playingEpisodeId: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onEpisodeClick: (PodcastSubscription, PodcastEpisode) -> Unit,
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

        when {
            subscription.episodes.isEmpty() && loading -> CenteredNote(stringResource(R.string.podcast_loading))
            subscription.episodes.isEmpty() -> CenteredNote(stringResource(R.string.podcast_episodes_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = RadioSpacing.medium,
                    vertical = RadioSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            ) {
                items(subscription.episodes, key = { it.guid }) { episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        playing = episode.guid == playingEpisodeId,
                        onClick = { onEpisodeClick(subscription, episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val meta = listOf(formatEpisodeDate(episode.publishedAtMs), formatDuration(episode.durationSec))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

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
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = RadioSpacing.page),
    ) {
        Text(
            text = text,
            color = RadioTextMuted,
            fontSize = RadioTextSizes.bodyLarge,
            textAlign = TextAlign.Center,
        )
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
        onDismissRequest = { if (!loading) onDismiss() },
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
                    onClick = { if (!loading) onDismiss() },
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
