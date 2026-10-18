package omnibeat.app.ui

import omnibeat.app.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_PLAYBACK_SPEEDS
import omnibeat.app.model.Station
import omnibeat.app.playback.PlaybackStreamInfo
import omnibeat.app.playback.PlaybackTrackStatus
import omnibeat.app.ui.appStringResource as stringResource
import java.util.Locale

/** 播客单集的快退 / 快进步长 */
private const val SKIP_BACKWARD_MS = 15_000L
private const val SKIP_FORWARD_MS = 30_000L

/**
 * 播放面板的固定尺寸。
 *
 * 折叠态是 64dp 的信息条；展开态在同一个容器里长高到 149dp，
 * 两态左右内边距相同、都没有圆角和外边距 —— 展开只是「面板长高」，
 * 不是浮起一张卡片。
 */
private val PANEL_SIDE_PADDING = 16.dp
private val COLLAPSED_HEIGHT = 64.dp
private val EXPANDED_TOP_PADDING = 12.dp
private val EXPANDED_BOTTOM_PADDING = 14.dp
private val EXPANDED_ARTWORK_SIZE = 42.dp

/**
 * 展开态信息行的高度。
 * 封面只有 42dp，但两行文字（标题 18sp 行高 + 副行 14sp 行高）加上字体内边距后
 * 实际要 39–41dp，行高卡在 42 会把副行裁掉半截 —— 所以这一行按文字给到 46dp，封面在行内居中。
 */
private val EXPANDED_ROW_HEIGHT = 46.dp
private val EXPANDED_MID_HEIGHT = 21.dp
private val CONTROL_ROW_HEIGHT = 44.dp
private val PLAY_BUTTON_SIZE = 44.dp
private val SIDE_BUTTON_SIZE = 38.dp
private val SECONDARY_BUTTON_SIZE = 34.dp
private val COLLAPSED_CHEVRON_SIZE = 28.dp
private val EXPANDED_CHEVRON_SIZE = 26.dp

/**
 * 关掉字体内边距：默认 includeFontPadding 会在每行上下各留 2–3dp，
 * 两行文本因此永远贴不紧。关掉后 Text 高度严格等于 lineHeight，行间距才是真的 1–2dp。
 */
private val TightTextStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
fun PlayerPanel(
    station: Station?,
    trackText: String,
    trackStatus: PlaybackTrackStatus?,
    errorText: String?,
    streamInfo: PlaybackStreamInfo,
    loading: Boolean,
    isPlaying: Boolean,
    showBitrate: Boolean,
    showPlayerMetadata: Boolean,
    marqueeTrackTitle: Boolean,
    collapsed: Boolean,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    playbackSpeed: Float = PODCAST_DEFAULT_PLAYBACK_SPEED,
    /** 当前是否为可 seek 的点播内容（播客单集）；电台直播恒为 false，面板据此切换控制组 */
    seekable: Boolean = false,
    onCollapsedChange: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPreviousStation: () -> Unit,
    onNextStation: () -> Unit,
    onRandomStation: () -> Unit,
    onSeek: (Long) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    /** 点信息区的动作。null = 保持默认（有曲目文本时复制到剪贴板）；播客单集传「跳到该订阅」 */
    onContentClick: (() -> Unit)? = null,
) {
    var showStreamInfo by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val trackClipboardLabel = stringResource(R.string.player_clipboard_track_label)
    val trackCopiedText = stringResource(R.string.player_track_copied)
    val streamInfoClipboardLabel = stringResource(R.string.player_clipboard_stream_info_label)
    val streamInfoCopiedText = stringResource(R.string.player_stream_info_copied)

    val onDemand = seekable
    // 播客单集是 HLS 点播，没有 ICY 元数据层；单集时第二行只在出错时出现。
    // 电台保留「曲目名 / 播放状态」逻辑（那边有 ICY 元数据，有信息量）。
    // 元数据和码率一样「没有就不占位」：无元数据 / 等待元数据时不再显示占位文案，
    // 即使设置里开着显示元数据 —— 副行只留 LIVE，不留一行「暂无元数据」。
    val displayTrackText: String? = if (onDemand) {
        errorText?.takeIf { it.isNotBlank() }
    } else {
        errorText ?: if (showPlayerMetadata) {
            when (trackStatus) {
                // 停止 / 无元数据 / 等待元数据：副行不留状态占位，只留 LIVE
                PlaybackTrackStatus.Stopped,
                PlaybackTrackStatus.NoMetadata,
                PlaybackTrackStatus.WaitingMetadata -> null
                null -> trackText.takeIf { it.isNotBlank() }
                else -> trackStatus?.let { trackStatusText(it) }
            }
        } else {
            null
        }
    }
    val subtitle: String? = if (onDemand) station?.artist?.takeIf { it.isNotBlank() } else displayTrackText
    val bitratePart = if (showBitrate) {
        streamInfo.bitrateKbps?.let { stringResource(R.string.player_bitrate_format, it) }
    } else {
        null
    }
    // 折叠态的码率作为独立子项渲染，不拼进元数据串：元数据过长时它会先被截断，
    // 码率始终留在行内可见（拼成一串的话，长曲目会把「· 128 kbps」整段吃掉）
    val collapsedSubtitle: String? = subtitle
    val collapsedBitrate: String? = bitratePart
    // 展开态：直播的中段已经在展示码率，不重复；点播的中段被进度条占用，码率并进副行
    val expandedSubtitle: String? = if (onDemand) {
        listOfNotNull(subtitle, bitratePart).joinToString(" · ").takeIf { it.isNotBlank() }
    } else {
        subtitle
    }
    val paramsText = listOfNotNull(bitratePart, streamInfo.sampleRateText, streamInfo.formatLabel)
        .joinToString(" · ")
    val hasActivePlaybackRequest = isPlaying || loading

    val onInfoClick: () -> Unit = {
        if (onContentClick != null) {
            onContentClick()
        } else if (trackStatus == null && trackText.isNotBlank()) {
            copyTextToClipboard(context = context, label = trackClipboardLabel, text = trackText)
            android.widget.Toast.makeText(context, trackCopiedText, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val onInfoLongClick: () -> Unit = { showStreamInfo = true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RadioSurface)
            .navigationBarsPadding()
            .animateContentSize(),
    ) {
        if (collapsed) {
            CollapsedPlayerBar(
                title = station?.title.orEmpty(),
                subtitle = collapsedSubtitle,
                bitrateText = collapsedBitrate,
                isLive = !onDemand && station != null,
                loading = loading,
                hasActivePlaybackRequest = hasActivePlaybackRequest,
                enabled = station != null,
                marquee = marqueeTrackTitle,
                onClick = onInfoClick,
                onLongClick = onInfoLongClick,
                onPrevious = onPreviousStation,
                onPlayPause = onPlayPause,
                onStop = onStop,
                onNext = onNextStation,
                onExpand = { onCollapsedChange(false) },
            )
        } else {
            ExpandedPlayerPanel(
                imageUrl = station?.imageUrl,
                title = station?.title.orEmpty(),
                subtitle = expandedSubtitle,
                isLive = !onDemand && station != null,
                loading = loading,
                paramsText = paramsText,
                onDemand = onDemand,
                positionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                hasActivePlaybackRequest = hasActivePlaybackRequest,
                enabled = station != null,
                marquee = marqueeTrackTitle,
                onClick = onInfoClick,
                onLongClick = onInfoLongClick,
                onCollapse = { onCollapsedChange(true) },
                onPrevious = onPreviousStation,
                onPlayPause = onPlayPause,
                onStop = onStop,
                onNext = onNextStation,
                onRandom = onRandomStation,
                onSeek = onSeek,
                onSpeedChange = onSpeedChange,
            )
        }
    }

    if (showStreamInfo) {
        StreamInfoDialog(
            stationTitle = station?.title.orEmpty(),
            trackText = displayTrackText.orEmpty(),
            streamInfo = streamInfo,
            onDismiss = { showStreamInfo = false },
            onCopyInfo = { info ->
                copyTextToClipboard(context, label = streamInfoClipboardLabel, text = info)
                android.widget.Toast.makeText(context, streamInfoCopiedText, android.widget.Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/**
 * 折叠态：64dp 信息条。
 * 左：台名 + 副行；右：上一 / 播放 / 下一 / 展开。展开内容从它上方生长，条本身位置不动。
 */
@Composable
private fun CollapsedPlayerBar(
    title: String,
    subtitle: String?,
    bitrateText: String?,
    isLive: Boolean,
    loading: Boolean,
    hasActivePlaybackRequest: Boolean,
    enabled: Boolean,
    marquee: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(COLLAPSED_HEIGHT)
            .padding(horizontal = PANEL_SIDE_PADDING),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_not_playing) },
                    color = if (title.isBlank()) RadioTextMuted else RadioText,
                    style = TightTextStyle,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (marquee) {
                                Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1_500)
                            } else {
                                Modifier
                            },
                        ),
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = RadioPrimary,
                    )
                }
            }
            val sub = if (!enabled) {
                stringResource(R.string.player_not_playing_hint)
            } else {
                subtitle
            }
            if (isLive || sub != null || bitrateText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                ) {
                    if (isLive) {
                        LiveBadge()
                    }
                    if (sub != null) {
                        Text(
                            text = sub,
                            color = RadioTextMuted,
                            style = TightTextStyle,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            maxLines = 1,
                            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier
                                // fill = false：短元数据不占满整行，码率紧跟在后面；
                                // 长元数据仍会在分配到的宽度里截断，不会把码率挤出屏幕
                                .weight(1f, fill = false)
                                .then(
                                    if (marquee) {
                                        Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1_500)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    if (bitrateText != null) {
                        Text(
                            text = bitrateText,
                            color = RadioTextMuted,
                            style = TightTextStyle,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
        PanelIconButton(
            painter = painterResource(R.drawable.ic_skip_previous),
            size = SIDE_BUTTON_SIZE,
            iconSize = 22.dp,
            enabled = enabled,
            onClick = onPrevious,
        )
        Spacer(Modifier.width(4.dp))
        PlayButton(
            hasActivePlaybackRequest = hasActivePlaybackRequest,
            onClick = onPlayPause,
            onLongClick = onStop,
        )
        Spacer(Modifier.width(4.dp))
        PanelIconButton(
            painter = painterResource(R.drawable.ic_skip_next),
            size = SIDE_BUTTON_SIZE,
            iconSize = 22.dp,
            enabled = enabled,
            onClick = onNext,
        )
        // 下一台与折叠箭头之间留出隔离带，避免点前进时误触展开
        Spacer(Modifier.width(8.dp))
        PanelIconButton(
            painter = painterResource(R.drawable.ic_player_collapse),
            size = COLLAPSED_CHEVRON_SIZE,
            iconSize = 22.dp,
            tint = RadioTextMuted.copy(alpha = 0.65f),
            modifier = Modifier.rotate(180f),
            onClick = onExpand,
        )
    }
}

/**
 * 展开态：149dp 面板。封面 + 信息、参数 / 进度、控制行三段。
 * 与折叠态共用同一个容器宽度和左右内边距，展开时只增加高度。
 */
@Composable
private fun ExpandedPlayerPanel(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    isLive: Boolean,
    loading: Boolean,
    paramsText: String,
    onDemand: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    hasActivePlaybackRequest: Boolean,
    enabled: Boolean,
    marquee: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCollapse: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onRandom: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PANEL_SIDE_PADDING,
                end = PANEL_SIDE_PADDING,
                top = EXPANDED_TOP_PADDING,
                bottom = EXPANDED_BOTTOM_PADDING,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(EXPANDED_ROW_HEIGHT)
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        ) {
            StationArtwork(
                imageUrl = imageUrl,
                size = EXPANDED_ARTWORK_SIZE,
                modifier = Modifier.clip(RoundedCornerShape(11.dp)),
            )
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title.ifBlank { stringResource(R.string.player_not_playing) },
                        color = if (title.isBlank()) RadioTextMuted else RadioText,
                        style = TightTextStyle,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (marquee) {
                                    Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1_500)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = RadioPrimary,
                        )
                    }
                }
                val sub = if (!enabled) {
                    stringResource(R.string.player_not_playing_hint)
                } else {
                    subtitle
                }
                if (isLive || sub != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                    ) {
                        if (isLive) {
                            LiveBadge()
                        }
                        if (sub != null) {
                            Text(
                                text = sub,
                                color = RadioTextMuted,
                                style = TightTextStyle,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                maxLines = 1,
                                overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                                softWrap = false,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (marquee) {
                                            Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1_500)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
            PanelIconButton(
                painter = painterResource(R.drawable.ic_player_collapse),
                size = EXPANDED_CHEVRON_SIZE,
                iconSize = 20.dp,
                tint = RadioTextMuted.copy(alpha = 0.62f),
                onClick = onCollapse,
            )
        }

        Spacer(Modifier.height(8.dp))

        // 中段固定 21dp：直播放流参数，点播放可拖动的进度 + 时间，两态因此等高
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EXPANDED_MID_HEIGHT),
        ) {
            if (onDemand && durationMs > 0L) {
                CompactSeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                )
            } else if (paramsText.isNotBlank()) {
                Text(
                    text = paramsText,
                    color = RadioTextMuted,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (onDemand) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(CONTROL_ROW_HEIGHT),
        ) {
            if (onDemand) {
                SkipTextButton(
                    label = stringResource(R.string.podcast_skip_backward_label),
                    onClick = { onSeek((positionMs - SKIP_BACKWARD_MS).coerceAtLeast(0L)) },
                )
                PanelIconButton(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    size = SECONDARY_BUTTON_SIZE,
                    iconSize = 20.dp,
                    enabled = enabled,
                    onClick = onPrevious,
                )
                PlayButton(
                    hasActivePlaybackRequest = hasActivePlaybackRequest,
                    onClick = onPlayPause,
                    onLongClick = onStop,
                )
                PanelIconButton(
                    painter = painterResource(R.drawable.ic_skip_next),
                    size = SECONDARY_BUTTON_SIZE,
                    iconSize = 20.dp,
                    enabled = enabled,
                    onClick = onNext,
                )
                SkipTextButton(
                    label = stringResource(R.string.podcast_skip_forward_label),
                    onClick = { onSeek((positionMs + SKIP_FORWARD_MS).coerceAtMost(durationMs)) },
                )
                SpeedChip(speed = playbackSpeed, onSpeedChange = onSpeedChange)
            } else {
                PanelIconButton(
                    painter = painterResource(R.drawable.ic_shuffle),
                    size = SECONDARY_BUTTON_SIZE,
                    iconSize = 20.dp,
                    enabled = enabled,
                    onClick = onRandom,
                )
                PanelIconButton(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    size = SECONDARY_BUTTON_SIZE,
                    iconSize = 20.dp,
                    enabled = enabled,
                    onClick = onPrevious,
                )
                PlayButton(
                    hasActivePlaybackRequest = hasActivePlaybackRequest,
                    onClick = onPlayPause,
                    onLongClick = onStop,
                )
                PanelIconButton(
                    painter = painterResource(R.drawable.ic_skip_next),
                    size = SECONDARY_BUTTON_SIZE,
                    iconSize = 20.dp,
                    enabled = enabled,
                    onClick = onNext,
                )
            }
        }
    }
}

/** 直播标识：小圆点 + LIVE 文字；比原来的实色 pill 克制，但文字保留，一眼能认出是直播 */
@Composable
private fun LiveBadge() {
    // 颜色必须在 composable 上下文取好：DrawScope 里的 lambda 不是 @Composable
    val primary = RadioPrimary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 6.dp),
    ) {
        Canvas(Modifier.size(9.dp)) {
            drawCircle(color = primary.copy(alpha = 0.18f), radius = size.minDimension / 2f)
            drawCircle(color = primary, radius = size.minDimension / 2f * 0.5f)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.player_live_badge),
            color = primary,
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            maxLines = 1,
        )
    }
}

/** 播放 / 暂停：44dp 实心主色，长按停止 */
@Composable
private fun PlayButton(
    hasActivePlaybackRequest: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(PLAY_BUTTON_SIZE)
            .clip(CircleShape)
            .background(RadioPrimary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Icon(
            painter = painterResource(
                if (hasActivePlaybackRequest) R.drawable.ic_pause else R.drawable.ic_play_arrow,
            ),
            contentDescription = null,
            tint = RadioOnPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 无底色的次要图标按钮：视觉尺寸小，但由外层尺寸决定触摸区 */
@Composable
private fun PanelIconButton(
    painter: Painter,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    modifier: Modifier = Modifier,
) {
    val resolvedTint = tint ?: RadioText
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = if (enabled) resolvedTint.copy(alpha = 0.87f) else RadioTextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 文字式圆形按钮：−15s / +30s */
@Composable
private fun SkipTextButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SECONDARY_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = RadioText.copy(alpha = 0.87f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 倍速胶囊：点开是速率菜单，当前项带对勾 */
@Composable
private fun SpeedChip(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Text(
            text = formatSpeedLabel(speed),
            color = RadioPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(RadioCorners.pill))
                .background(RadioPrimary.copy(alpha = 0.12f))
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RadioSurface,
        ) {
            PODCAST_PLAYBACK_SPEEDS.forEach { option ->
                val selected = option == speed
                DropdownMenuItem(
                    text = {
                        Text(
                            text = formatSpeedLabel(option),
                            color = if (selected) RadioPrimary else RadioText,
                            fontSize = RadioTextSizes.body,
                        )
                    },
                    leadingIcon = if (selected) {
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
                        expanded = false
                        onSpeedChange(option)
                    },
                )
            }
        }
    }
}

/**
 * 点播进度：3dp 细轨道 + 10dp 拖钮，整块 21dp 都是触摸区。
 * 拖动中只更新本地值，松手才真正 seek。
 */
@Composable
private fun CompactSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val totalMs = durationMs.coerceAtLeast(1L)
    val playedFraction = (positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (scrubbing) scrubFraction else playedFraction
    val shownPosition = if (scrubbing) (scrubFraction * totalMs).toLong() else positionMs
    // DrawScope 的 lambda 不是 @Composable，颜色先取出来
    val primary = RadioPrimary
    val outline = RadioOutline

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(totalMs) {
                fun fractionAt(x: Float): Float {
                    if (size.width <= 0) return 0f
                    return (x / size.width.toFloat()).coerceIn(0f, 1f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrubbing = true
                    scrubFraction = fractionAt(down.position.x)
                    down.consume()
                    drag(down.id) { change ->
                        scrubFraction = fractionAt(change.position.x)
                        change.consume()
                    }
                    scrubbing = false
                    onSeek((scrubFraction * totalMs).toLong())
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .align(Alignment.TopStart),
        ) {
            val trackHeight = 3.dp.toPx()
            val centerY = size.height / 2f
            val radius = trackHeight / 2f
            drawRoundRect(
                color = outline,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(radius, radius),
            )
            val activeWidth = size.width * shownFraction
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            drawCircle(
                color = primary,
                radius = 5.dp.toPx(),
                center = Offset(activeWidth.coerceIn(0f, size.width), centerY),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart),
        ) {
            Text(
                text = formatPlaybackTime(shownPosition),
                color = RadioTextMuted,
                fontSize = 10.sp,
                lineHeight = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatPlaybackTime(durationMs),
                color = RadioTextMuted,
                fontSize = 10.sp,
                lineHeight = 11.sp,
            )
        }
    }
}

private fun formatPlaybackTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatSpeedLabel(speed: Float): String {
    val value = if (speed % 1f == 0f) {
        String.format(Locale.US, "%.0f", speed)
    } else {
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "$value×"
}

@Composable
private fun trackStatusText(trackStatus: PlaybackTrackStatus): String {
    return when (trackStatus) {
        PlaybackTrackStatus.Stopped -> stringResource(R.string.track_text_stopped)
        PlaybackTrackStatus.Paused -> stringResource(R.string.track_text_paused)
        PlaybackTrackStatus.LoadingStations -> stringResource(R.string.track_text_loading_stations)
        PlaybackTrackStatus.Resolving -> stringResource(R.string.track_text_resolving)
        PlaybackTrackStatus.WaitingMetadata -> stringResource(R.string.track_text_waiting_metadata)
        PlaybackTrackStatus.NoMetadata -> stringResource(R.string.track_text_no_metadata)
    }
}

@Composable
private fun StreamInfoDialog(
    stationTitle: String,
    trackText: String,
    streamInfo: PlaybackStreamInfo,
    onDismiss: () -> Unit,
    onCopyInfo: (String) -> Unit,
) {
    val textFieldColors = omniDisabledTextFieldColors()
    val notAvailable = stringResource(R.string.player_not_available)
    val bitrate = streamInfo.bitrateKbps?.let { stringResource(R.string.player_bitrate_format, it) } ?: notAvailable
    val sampleRate = streamInfo.sampleRateText ?: notAvailable
    val format = streamInfo.formatLabel ?: notAvailable
    val copyText = listOf(
        stringResource(R.string.player_stream_info_copy_station, stationTitle.ifBlank { notAvailable }),
        stringResource(R.string.player_stream_info_copy_track, trackText.ifBlank { notAvailable }),
        stringResource(R.string.player_stream_info_copy_bitrate, bitrate),
        stringResource(R.string.player_stream_info_copy_sample_rate, sampleRate),
        stringResource(R.string.player_stream_info_copy_format, format),
    ).joinToString(" | ")

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RadioSpacing.large)
            .widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.player_stream_info_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = stationTitle,
                    onValueChange = {},
                    enabled = false,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    label = { Text(stringResource(R.string.player_stream_info_station)) },
                    colors = textFieldColors,
                    modifier = Modifier.heightIn(min = RadioSizes.textFieldMinHeight).fillMaxWidth(),
                )
                OutlinedTextField(
                    value = trackText,
                    onValueChange = {},
                    enabled = false,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 5,
                    label = { Text(stringResource(R.string.player_stream_info_track_metadata)) },
                    colors = textFieldColors,
                    modifier = Modifier.heightIn(min = RadioSizes.textFieldMinHeight).fillMaxWidth(),
                )
                StreamInfoRow(label = stringResource(R.string.player_stream_info_bitrate), value = bitrate)
                StreamInfoRow(label = stringResource(R.string.player_stream_info_sample_rate), value = sampleRate)
                StreamInfoRow(label = stringResource(R.string.player_stream_info_format), value = format)
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OmniSecondaryButton(text = stringResource(R.string.action_close), onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                OmniPrimaryButton(
                    text = stringResource(R.string.action_copy),
                    onClick = { onCopyInfo(copyText) },
                )
            }
        },
        dismissButton = {},
        containerColor = RadioSurface,
        titleContentColor = RadioText,
        textContentColor = RadioTextMuted,
    )
}

@Composable
private fun StreamInfoRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, color = RadioTextMuted, fontSize = RadioTextSizes.bodySmall)
        Text(text = value, color = RadioText, fontSize = RadioTextSizes.body, fontWeight = FontWeight.Medium)
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    @Suppress("UsePropertyAccessSyntax")
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
}
