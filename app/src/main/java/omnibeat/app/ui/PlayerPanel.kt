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
 * 单集控制行（一行七个控件）的按钮尺寸。
 * 面板内容区左右各 22dp 内边距，360dp 手机上只剩约 316dp；
 * 六个次级按钮 36dp + 主按钮 60dp = 276dp，留出约 40dp 给间隔。
 */
private val SECONDARY_BUTTON_SIZE = 36.dp
private val PRIMARY_BUTTON_SIZE = 60.dp

/** 小尺寸文字按钮（15s / 30s / 倍速）的字号：36dp 圆里放不下 15sp 的「1.25×」 */
private val SECONDARY_BUTTON_LABEL_SIZE = RadioTextSizes.badge

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
    marqueeTrackTitle: Boolean,
    collapsed: Boolean,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    playbackSpeed: Float = PODCAST_DEFAULT_PLAYBACK_SPEED,
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
    val displayTrackText = errorText ?: trackStatus?.let { trackStatusText(it) } ?: trackText
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
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
                        Text(
                            text = displayTrackText,
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
                // durationMs > 0 说明在播点播单集（直播没有时长）。两种形态分开：
                if (durationMs > 0L) {
                    // 单集：进度条 + 一行排开的常规播放器控制
                    PlaybackProgress(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeek = onSeek,
                    )
                    // 上一集 / 后退 15 秒 / 播放暂停 / 前进 30 秒 / 下一集 / 倍速 / 音量
                    // 七个控件排一行：面板左右各 22dp 内边距，360dp 屏只剩约 316dp，
                    // 所以次级按钮 36dp、主按钮 60dp（6×36 + 60 = 276 + 间隔 ≈ 303 ≤ 316）。
                    // 间隔固定、整组居中 —— 不能用 SpaceBetween（会把按钮摊到整行两端，
                    // 宽屏上每个按钮之间空出一大截，观感很散）。
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
                        PlayerIconButton(
                            enabled = true,
                            onClick = onPreviousStation,
                            modifier = Modifier.size(SECONDARY_BUTTON_SIZE),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_skip_previous),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.iconInline),
                            )
                        }
                        PlayerTextButton(
                            label = stringResource(R.string.podcast_skip_backward_label),
                            onClick = { onSeek((positionMs - SKIP_BACKWARD_MS).coerceAtLeast(0L)) },
                            buttonSize = SECONDARY_BUTTON_SIZE,
                            labelSize = SECONDARY_BUTTON_LABEL_SIZE,
                        )
                        PlayerIconButton(
                            enabled = true,
                            onClick = onPlayPause,
                            onLongClick = onStop,
                            modifier = Modifier.size(PRIMARY_BUTTON_SIZE),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (hasActivePlaybackRequest) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.iconPlayback),
                            )
                        }
                        PlayerTextButton(
                            label = stringResource(R.string.podcast_skip_forward_label),
                            onClick = { onSeek((positionMs + SKIP_FORWARD_MS).coerceAtMost(durationMs)) },
                            buttonSize = SECONDARY_BUTTON_SIZE,
                            labelSize = SECONDARY_BUTTON_LABEL_SIZE,
                        )
                        PlayerIconButton(
                            enabled = true,
                            onClick = onNextStation,
                            modifier = Modifier.size(SECONDARY_BUTTON_SIZE),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_skip_next),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.iconInline),
                            )
                        }
                        PlaybackSpeedButton(
                            speed = playbackSpeed,
                            onSpeedChange = onSpeedChange,
                            buttonSize = SECONDARY_BUTTON_SIZE,
                            labelSize = SECONDARY_BUTTON_LABEL_SIZE,
                        )
                        VolumeButton(
                            volume = appVolume,
                            onVolumeChange = onVolumeChange,
                            buttonSize = SECONDARY_BUTTON_SIZE,
                            iconSize = RadioSizes.iconInline,
                        )
                    }
                } else {
                    // 电台直播：面板维持原样（随机 / 上一台 / 播放暂停 / 下一台 / 音量）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = RadioSpacing.large),
                    ) {
                        PlayerIconButton(
                            enabled = true,
                            onClick = onRandomStation,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(RadioSizes.button),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shuffle),
                                contentDescription = null,
                                modifier = Modifier.size(RadioSizes.icon),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(RadioSpacing.fieldGap),
                            modifier = Modifier.align(Alignment.Center),
                        ) {
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
                        }
                        VolumeButton(
                            volume = appVolume,
                            onVolumeChange = onVolumeChange,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
    }

    if (showStreamInfo) {
        StreamInfoDialog(
            stationTitle = station?.title.orEmpty(),
            trackText = displayTrackText,
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
 * 点播单集的进度条：拖动中只更新本地值，松手才真正跳转 ——
 * 否则每移动一格都会往服务发一次 seek。
 */
@Composable
private fun PlaybackProgress(
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
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatPlaybackTime(shownPosition),
                color = RadioTextMuted,
                fontSize = RadioTextSizes.caption,
            )
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
 * 文字式圆形按钮：倍速（`1×`）与快退/快进（`15s` / `30s`）共用。
 * 项目里没有这类图标，且全仓不使用 Material Icons，所以统一用文字，与周围圆形按钮同高。
 */
@Composable
private fun PlayerTextButton(
    label: String,
    onClick: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp = RadioSizes.button,
    labelSize: androidx.compose.ui.unit.TextUnit = RadioTextSizes.bodySmall,
) {
    PlayerIconButton(
        enabled = true,
        onClick = onClick,
        modifier = Modifier.size(buttonSize),
    ) {
        Text(
            text = label,
            color = RadioText,
            fontSize = labelSize,
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
    buttonSize: androidx.compose.ui.unit.Dp = RadioSizes.button,
    labelSize: androidx.compose.ui.unit.TextUnit = RadioTextSizes.bodySmall,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        PlayerTextButton(
            label = formatSpeedLabel(speed),
            onClick = { expanded = true },
            buttonSize = buttonSize,
            labelSize = labelSize,
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
