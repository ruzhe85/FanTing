package omnibeat.app.ui

import androidx.compose.foundation.layout.heightIn

import omnibeat.app.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import omnibeat.app.ui.appStringResource as stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_PLAYBACK_SPEEDS
import omnibeat.app.model.Station
import omnibeat.app.playback.PlaybackTrackStatus
import omnibeat.app.playback.PlaybackStreamInfo
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Locale

/** 播客单集的快退 / 快进步长 */
private const val SKIP_BACKWARD_MS = 15_000L
private const val SKIP_FORWARD_MS = 30_000L

/**
 * 时间行里倍速 / 音量两颗次要按钮的直径。
 * 它们紧贴在总时长左侧，比播放控制按钮（53dp）小一档，避免抢戏。
 */
private val AUX_BUTTON_SIZE = 38.dp



@Composable
fun PlayerPanel(
    station: Station?,
    trackText: String,
    trackStatus: PlaybackTrackStatus?,
    errorText: String?,
    streamInfo: PlaybackStreamInfo,
    loading: Boolean,
    isPlaying: Boolean,
    appVolume: Float,
    showBitrate: Boolean,
    showUnavailableBitrate: Boolean,
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
    onVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
) {
    var showStreamInfo by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val trackClipboardLabel = stringResource(R.string.player_clipboard_track_label)
    val trackCopiedText = stringResource(R.string.player_track_copied)
    val streamInfoClipboardLabel = stringResource(R.string.player_clipboard_stream_info_label)
    val streamInfoCopiedText = stringResource(R.string.player_stream_info_copied)
    // 播客单集是 HLS 点播，**没有 ICY 元数据这一层**：服务端等不到元数据会把第二行
    // 永久停在「无元数据」，所以单集时第二行只在出错时出现；电台保留原来的
    // 「曲目名 / 播放状态」逻辑（那边有 ICY 元数据，是有信息量的）。
    val onDemand = seekable
    val displayTrackText: String? = if (onDemand) {
        errorText?.takeIf { it.isNotBlank() }
    } else {
        // 元数据可以整体关掉：不少电台没有 ICY 信息，只会在「正在等待元数据 / 无元数据」之间跳；
        // 错误提示不受开关影响，必须保留
        errorText ?: if (showPlayerMetadata) {
            trackStatus?.let { trackStatusText(it) } ?: trackText
        } else {
            null
        }
    }
    val bitrateText = if (showBitrate) {
        streamInfo.bitrateKbps?.let { bitrate ->
            stringResource(R.string.player_bitrate_format, bitrate)
        } ?: stringResource(R.string.player_bitrate_unavailable).takeIf { showUnavailableBitrate }
    } else {
        null
    }
    val hasActivePlaybackRequest = isPlaying || loading

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RadioSurface)
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(RadioSizes.playerHeaderHeight)
                .padding(horizontal = RadioSpacing.page),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(RadioSizes.playerDividerThickness)
                    .background(RadioOutline),
            )
            OmniIconButton(
                painter = painterResource(R.drawable.ic_player_collapse),
                onClick = { onCollapsedChange(!collapsed) },
                tint = RadioTextMuted,
                modifier = Modifier.size(RadioSizes.playerCollapseButton),
                iconModifier = Modifier
                    .size(RadioSizes.playerCollapseIcon)
                    .rotate(if (collapsed) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = slideInVertically(
                animationSpec = tween(),
                initialOffsetY = { it },
            ) + expandVertically(animationSpec = tween(), expandFrom = Alignment.Bottom),
            exit = slideOutVertically(
                animationSpec = tween(),
                targetOffsetY = { it },
            ) + shrinkVertically(animationSpec = tween(), shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RadioSurface)
                    .padding(start = RadioSpacing.page, end = RadioSpacing.page, bottom = RadioSpacing.large),
            ) {
                Column(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = station != null,
                            onClick = {
                                if (trackStatus == null && trackText.isNotBlank()) {
                                    copyTextToClipboard(
                                        context = context,
                                        label = trackClipboardLabel,
                                        text = trackText,
                                    )
                                    android.widget.Toast.makeText(
                                        context,
                                        trackCopiedText,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onLongClick = { showStreamInfo = true },
                        )
                        .fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = station?.title ?: stringResource(R.string.player_choose_station),
                            color = RadioText,
                            fontSize = RadioTextSizes.title,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            // 播客的超长节目名也走同一套滚动设置（此前只有电台的曲目行会滚）；
                            // 电台的台名保持不滚 —— 那边第二行才是「曲目名」
                            overflow = if (marqueeTrackTitle && onDemand) {
                                TextOverflow.Clip
                            } else {
                                TextOverflow.Ellipsis
                            },
                            softWrap = false,
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (marqueeTrackTitle && onDemand) {
                                        Modifier.basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            repeatDelayMillis = 1_500,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(start = RadioSpacing.medium)
                                    .size(RadioSizes.icon),
                                strokeWidth = 2.dp,
                                color = RadioPrimary,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = RadioSpacing.extraSmall),
                    ) {
                        displayTrackText?.let { trackText ->
                            Text(
                                text = trackText,
                                color = RadioTextMuted,
                                fontSize = RadioTextSizes.body,
                                maxLines = 1,
                                overflow = if (marqueeTrackTitle) TextOverflow.Clip else TextOverflow.Ellipsis,
                                softWrap = false,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (marqueeTrackTitle) {
                                            Modifier.basicMarquee(
                                                iterations = Int.MAX_VALUE,
                                                repeatDelayMillis = 1_500,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        }
                        if (!onDemand && station != null) {
                            LiveBadge()
                        }
                        bitrateText?.let { bitrate ->
                            Text(
                                text = bitrate,
                                color = RadioTextMuted,
                                fontSize = RadioTextSizes.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.padding(start = RadioSpacing.medium),
                            )
                        }
                    }
                }
                // 进度区：仅点播单集显示，且等时长真正拿到再画（避免 0:00/0:00 闪烁）。
                // 直播没有可拖动的进度，这块区域直接不占位。
                if (onDemand && durationMs > 0L) {
                    PlaybackProgress(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        playbackSpeed = playbackSpeed,
                        appVolume = appVolume,
                        onSeek = onSeek,
                        onSpeedChange = onSpeedChange,
                        onVolumeChange = onVolumeChange,
                    )
                }
                // 控制行：统一五槽骨架 —— 同位置、同尺寸、整组居中，只槽位内容随内容类型变：
                //   直播 = 随机 / 上一台 / 播放暂停 / 下一台 / 音量
                //   点播 = −15s / 上一集 / 播放暂停 / 下一集 / +30s
                // 倍速与音量（点播）不在这行 —— 五控 288dp + 倍速音量 106dp = 394dp，
                // 超过 360dp 屏可用的 316dp 会重叠，所以那两个右对齐挂在进度条那一行。
                // 间隔用固定值 + 整组居中：SpaceBetween 会把按钮摊到整行两端，观感很散。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(
                        RadioSpacing.extraSmall,
                        Alignment.CenterHorizontally,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = RadioSpacing.large),
                ) {
                    // 槽1：直播随机 / 点播快退 15s
                    if (onDemand) {
                        PlayerTextButton(
                            label = stringResource(R.string.podcast_skip_backward_label),
                            onClick = { onSeek((positionMs - SKIP_BACKWARD_MS).coerceAtLeast(0L)) },
                        )
                    } else {
                        PlayerIconButton(
                            enabled = true,
                            onClick = onRandomStation,
                            modifier = Modifier.size(RadioSizes.button),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shuffle),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.icon),
                            )
                        }
                    }
                    // 槽2：上一台 / 上一集
                    PlayerIconButton(
                        enabled = true,
                        onClick = onPreviousStation,
                        modifier = Modifier.size(RadioSizes.button),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_skip_previous),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.icon),
                        )
                    }
                    // 槽3：播放 / 暂停（长按停止）
                    PlayerIconButton(
                        enabled = true,
                        onClick = onPlayPause,
                        onLongClick = onStop,
                        modifier = Modifier.size(RadioSizes.primaryPlaybackButton),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (hasActivePlaybackRequest) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.iconPlayback),
                        )
                    }
                    // 槽4：下一台 / 下一集
                    PlayerIconButton(
                        enabled = true,
                        onClick = onNextStation,
                        modifier = Modifier.size(RadioSizes.button),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_skip_next),
                            contentDescription = null,
                            modifier = Modifier.size(RadioSizes.icon),
                        )
                    }
                    // 槽5：直播音量 / 点播快进 30s
                    if (onDemand) {
                        PlayerTextButton(
                            label = stringResource(R.string.podcast_skip_forward_label),
                            onClick = { onSeek((positionMs + SKIP_FORWARD_MS).coerceAtMost(durationMs)) },
                        )
                    } else {
                        VolumeButton(
                            volume = appVolume,
                            onVolumeChange = onVolumeChange,
                        )
                    }
                }
            }
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
 * 直播徽标：曲目行里的小 pill。
 * 直播没有可拖动的进度（点播有进度条），用徽标把「这是直播」说明白，
 * 免得用户以为进度条缺失是 bug。只在电台直播且已选中电台时显示。
 */
@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.player_live_badge),
        color = Color.White,
        fontSize = RadioTextSizes.badge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .padding(start = RadioSpacing.medium)
            .clip(RoundedCornerShape(RadioCorners.pill))
            .background(RadioPrimary)
            .padding(horizontal = RadioSpacing.compact, vertical = RadioSpacing.extraSmall),
    )
}

/**
 * 点播单集的进度条：拖动中只更新本地值，松手才真正跳转 ——
 * 否则每移动一格都会往服务发一次 seek。
 */
@Composable
private fun PlaybackProgress(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    appVolume: Float,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val totalMs = durationMs.coerceAtLeast(1L)
    val playedFraction = (positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val shownFraction = if (scrubbing) scrubFraction else playedFraction
    val shownPosition = if (scrubbing) (scrubFraction * totalMs).toLong() else positionMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = RadioSpacing.large),
    ) {
        Slider(
            value = shownFraction,
            onValueChange = {
                scrubbing = true
                scrubFraction = it.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                scrubbing = false
                onSeek((scrubFraction * totalMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = RadioPrimary,
                activeTrackColor = RadioPrimary,
                inactiveTrackColor = RadioOutline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatPlaybackTime(shownPosition),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.caption,
            )
            Spacer(Modifier.weight(1f))
            // 顺序：已播（左）→ 倍速 → 音量 → 总时长（最右）。
            // 两颗次要按钮紧贴总时长，总时长落在行尾 —— 一眼就能看出哪里是结尾
            PlaybackSpeedButton(
                speed = playbackSpeed,
                onSpeedChange = onSpeedChange,
            )
            Spacer(Modifier.width(RadioSpacing.labelGap))
            VolumeButton(
                volume = appVolume,
                onVolumeChange = onVolumeChange,
                buttonSize = AUX_BUTTON_SIZE,
                iconSize = RadioSizes.iconInline,
            )
            Spacer(Modifier.width(RadioSpacing.small))
            Text(
                text = formatPlaybackTime(durationMs),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.caption,
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

/**
 * 文字式圆形按钮：快退 / 快进（`15s` / `30s`）用。
 * 项目里没有这类图标，且全仓不使用 Material Icons，所以用文字；尺寸与周围圆形按钮一致。
 */
@Composable
private fun PlayerTextButton(
    label: String,
    onClick: () -> Unit,
) {
    PlayerIconButton(
        enabled = true,
        onClick = onClick,
        modifier = Modifier.size(RadioSizes.button),
    ) {
        Text(
            text = label,
            color = RadioText,
            fontSize = RadioTextSizes.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 单集倍速按钮：用纯文字显示当前倍速，点开是速率菜单，当前项带对勾。
 */
@Composable
private fun PlaybackSpeedButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp = AUX_BUTTON_SIZE,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // 与音量按钮同款同尺寸的圆形按钮（不做胶囊，避免两颗次要按钮割裂）；
        // 圆里是文字，字号要比图标小一档，否则「1.25×」会擦到圆弧
        PlayerIconButton(
            enabled = true,
            onClick = { expanded = true },
            modifier = Modifier.size(buttonSize),
        ) {
            Text(
                text = formatSpeedLabel(speed),
                color = RadioPrimary,
                fontSize = RadioTextSizes.badge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
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

@Composable
private fun PlayerIconButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onLongClick != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(CircleShape)
                .background(if (enabled) RadioSurfaceHigh else RadioSurfaceHigh)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) RadioPrimary else RadioTextMuted) {
                content()
            }
        }
        return
    }

    OmniFilledIconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
private fun VolumeButton(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: androidx.compose.ui.unit.Dp = RadioSizes.button,
    iconSize: androidx.compose.ui.unit.Dp = RadioSizes.icon,
) {
    var expanded by remember { mutableStateOf(false) }
    val sliderHeight = RadioSizes.volumeSliderHeight
    val sliderTrackBottomGap = RadioSpacing.rowVertical
    val sliderTrackBottomInset = RadioSpacing.medium
    val popupOffsetY = with(LocalDensity.current) {
        -(sliderHeight + sliderTrackBottomGap - sliderTrackBottomInset).roundToPx()
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        OmniFilledIconButton(
            enabled = true,
            onClick = { expanded = !expanded },
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_volume),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(x = 0, y = popupOffsetY),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                VerticalVolumeSlider(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    modifier = Modifier
                        .width(RadioSizes.volumeSliderWidth)
                        .height(sliderHeight),
                )
            }
        }
    }
}

@Composable
private fun VerticalVolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color? = null,
    activeTrackColor: Color? = null,
    thumbColor: Color? = null,
) {
    val coercedVolume = volume.coerceIn(0f, 1f)
    val resolvedTrackColor = trackColor ?: RadioOutline
    val resolvedActiveTrackColor = activeTrackColor ?: RadioPrimary
    val resolvedThumbColor = thumbColor ?: RadioText

    fun positionToVolume(y: Float, height: Float, thumbHeight: Float, verticalPadding: Float): Float {
        val trackTop = verticalPadding + thumbHeight / 2f
        val trackBottom = height - verticalPadding - thumbHeight / 2f
        if (trackBottom <= trackTop) return coercedVolume
        return ((trackBottom - y) / (trackBottom - trackTop)).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val thumbHeight = RadioSizes.volumeThumbHeight.toPx()
            val verticalPadding = RadioSpacing.small.toPx()

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onVolumeChange(positionToVolume(down.position.y, size.height.toFloat(), thumbHeight, verticalPadding))
                down.consume()

                drag(down.id) { change ->
                    onVolumeChange(positionToVolume(change.position.y, size.height.toFloat(), thumbHeight, verticalPadding))
                    change.consume()
                }
            }
        },
    ) {
        val trackWidth = RadioSizes.volumeTrackWidth.toPx()
        val thumbWidth = RadioSizes.volumeThumbWidth.toPx()
        val thumbHeight = RadioSizes.volumeThumbHeight.toPx()
        val verticalPadding = RadioSpacing.small.toPx()
        val centerX = size.width / 2f
        val trackTop = verticalPadding + thumbHeight / 2f
        val trackBottom = size.height - verticalPadding - thumbHeight / 2f
        val trackHeight = trackBottom - trackTop
        val thumbCenterY = trackBottom - trackHeight * coercedVolume
        val trackRadius = trackWidth / 2f
        val thumbRadius = thumbWidth / 2f
        val thumbTrackGap = RadioSpacing.extraSmall.toPx()
        val inactiveTrackBottom = (thumbCenterY - thumbHeight / 2f - thumbTrackGap).coerceAtLeast(trackTop)
        val activeTrackTop = (thumbCenterY + thumbHeight / 2f + thumbTrackGap).coerceAtMost(trackBottom)

        fun drawTrackSegment(
            color: Color,
            top: Float,
            bottom: Float,
            roundTop: Boolean,
            roundBottom: Boolean,
        ) {
            if (bottom <= top) return

            val left = centerX - trackWidth / 2f
            val rectTop = if (roundTop) top + trackRadius else top
            val rectBottom = if (roundBottom) bottom - trackRadius else bottom

            if (rectBottom > rectTop) {
                drawRect(
                    color = color,
                    topLeft = Offset(left, rectTop),
                    size = Size(trackWidth, rectBottom - rectTop),
                )
            }
            if (roundTop) {
                drawCircle(
                    color = color,
                    radius = trackRadius,
                    center = Offset(centerX, top + trackRadius),
                )
            }
            if (roundBottom) {
                drawCircle(
                    color = color,
                    radius = trackRadius,
                    center = Offset(centerX, bottom - trackRadius),
                )
            }
        }
        drawTrackSegment(
            color = resolvedTrackColor,
            top = trackTop,
            bottom = inactiveTrackBottom,
            roundTop = true,
            roundBottom = false,
        )
        drawTrackSegment(
            color = resolvedActiveTrackColor,
            top = activeTrackTop,
            bottom = trackBottom,
            roundTop = false,
            roundBottom = true,
        )
        drawRoundRect(
            color = resolvedThumbColor,
            topLeft = Offset(centerX - thumbWidth / 2f, thumbCenterY - thumbHeight / 2f),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbRadius, thumbRadius),
        )
    }
}
