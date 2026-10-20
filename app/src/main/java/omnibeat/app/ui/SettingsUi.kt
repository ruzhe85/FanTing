package omnibeat.app.ui

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.heightIn

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import omnibeat.app.ui.appStringResource as stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import omnibeat.app.R
import omnibeat.app.data.AppLanguageOption
import omnibeat.app.data.AppLanguagePrefs
import omnibeat.app.data.STOP_SERVICE_AFTER_PAUSE_NEVER
import omnibeat.app.model.AccentPalette
import omnibeat.app.model.PODCAST_REFRESH_INTERVAL_OPTIONS
import omnibeat.app.model.PODCAST_SEARCH_REGIONS
import omnibeat.app.model.ThemeMode

@Composable
fun ThemeModeSegmentedControl(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)
    val selectedIndex = options.indexOf(themeMode).coerceAtLeast(0)
    val segmentWidth = RadioSizes.themeSegmentWidth
    val controlHeight = RadioSizes.themeSegmentHeight
    val selectedOffset by animateDpAsState(
        targetValue = segmentWidth * selectedIndex,
        animationSpec = tween(durationMillis = 220),
        label = "theme selector",
    )

    Box(
        modifier = modifier
            .width(segmentWidth * options.size)
            .height(controlHeight)
            .background(RadioSurfaceHigh, CircleShape),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(selectedOffset.roundToPx(), 0) }
                .width(segmentWidth)
                .height(controlHeight)
                .background(RadioPrimary, CircleShape),
        )
        Row(modifier = Modifier.matchParentSize()) {
            options.forEach { option ->
                val selected = themeMode == option
                val interactionSource = remember(option) { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(segmentWidth)
                        .height(controlHeight)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onThemeModeChange(option) },
                        ),
                ) {
                    Icon(
                        painter = painterResource(themeModeIcon(option)),
                        contentDescription = null,
                        tint = if (selected) RadioOnPrimary else RadioTextMuted,
                        modifier = Modifier.size(RadioSizes.icon),
                    )
                }
            }
        }
    }
}

private fun themeModeIcon(themeMode: ThemeMode): Int {
    return when (themeMode) {
        ThemeMode.System -> R.drawable.ic_theme_system
        ThemeMode.Light -> R.drawable.ic_theme_light
        ThemeMode.Dark -> R.drawable.ic_theme_dark
    }
}

@Composable
private fun pauseTimeoutLabel(minutes: Int): String {
    return if (minutes == STOP_SERVICE_AFTER_PAUSE_NEVER) {
        stringResource(R.string.settings_stop_service_after_pause_never)
    } else {
        stringResource(R.string.settings_stop_service_after_pause_minutes, minutes)
    }
}

@Composable
fun SettingsPage(
    themeMode: ThemeMode,
    accentPalette: AccentPalette,
    appLanguageTag: String?,
    autoPlayNextEpisode: Boolean,
    stopServiceAfterPauseMinutes: Int,
    keepMediaCard: Boolean,
    showStationArtwork: Boolean,
    syncingStationArtwork: Boolean,
    showEmptyFavoritesTab: Boolean,
    confirmStationDeletion: Boolean,
    showBitrateInControlPanel: Boolean,
    showPlayerMetadata: Boolean,
    marqueeTrackTitle: Boolean,
    autoExpandPlayerPanelOnPlayback: Boolean,
    collapsePlayerPanelInSearch: Boolean,
    addRadioBrowserTags: Boolean,
    removeTrackingParameters: Boolean,
    showAndroidAutoArtwork: Boolean,
    podcastAutoRefreshOnStart: Boolean,
    podcastAutoRefreshOnEnterList: Boolean,
    podcastRefreshIntervalMinutes: Int,
    podcastSearchRegion: String,
    podcastWifiOnlyDownload: Boolean,
    stationCount: Int,
    podcastCount: Int,
    episodeCount: Int,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentPaletteChange: (AccentPalette) -> Unit,
    onAppLanguageChange: (String?) -> Unit,
    onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
    onStopServiceAfterPauseMinutesChange: (Int) -> Unit,
    onKeepMediaCardChange: (Boolean) -> Unit,
    onShowStationArtworkChange: (Boolean) -> Unit,
    onSyncStationArtwork: () -> Unit,
    onShowEmptyFavoritesTabChange: (Boolean) -> Unit,
    onConfirmStationDeletionChange: (Boolean) -> Unit,
    onShowBitrateInControlPanelChange: (Boolean) -> Unit,
    onShowPlayerMetadataChange: (Boolean) -> Unit,
    onMarqueeTrackTitleChange: (Boolean) -> Unit,
    onAutoExpandPlayerPanelOnPlaybackChange: (Boolean) -> Unit,
    onCollapsePlayerPanelInSearchChange: (Boolean) -> Unit,
    onAddRadioBrowserTagsChange: (Boolean) -> Unit,
    onRemoveTrackingParametersChange: (Boolean) -> Unit,
    onShowAndroidAutoArtworkChange: (Boolean) -> Unit,
    onPodcastAutoRefreshOnStartChange: (Boolean) -> Unit,
    onPodcastAutoRefreshOnEnterListChange: (Boolean) -> Unit,
    onPodcastRefreshIntervalMinutesChange: (Int) -> Unit,
    onPodcastSearchRegionChange: (String) -> Unit,
    onPodcastWifiOnlyDownloadChange: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportSimpleText: () -> Unit,
    onImportSimpleText: () -> Unit,
    onClearLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val languageOptions = remember(context) { AppLanguagePrefs.options(context) }
    var confirmClearLibrary by remember { mutableStateOf(false) }
    var showKeepMediaCardInfo by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = RadioSpacing.page)
                .padding(top = RadioSpacing.rowVertical)
                .padding(bottom = RadioSpacing.page),
        ) {
            SettingsSectionHeader(title = stringResource(R.string.settings_section_appearance))
            SettingsThemeRow(
                title = stringResource(R.string.settings_theme_title),
                subtitle = when (themeMode) {
                    ThemeMode.System -> stringResource(R.string.settings_theme_system)
                    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
                    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
                },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
            )
            SettingsAccentRow(
                title = stringResource(R.string.settings_accent_title),
                subtitle = stringResource(accentPalette.labelRes()),
                accentPalette = accentPalette,
                onAccentPaletteChange = onAccentPaletteChange,
            )
            SettingsLanguageRow(
                title = stringResource(R.string.settings_language_title),
                subtitle = if (appLanguageTag == null) {
                    stringResource(R.string.settings_language_system)
                } else {
                    stringResource(R.string.settings_language_selected)
                },
                options = languageOptions,
                selectedTag = appLanguageTag,
                onLanguageSelect = onAppLanguageChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_show_empty_favorites_title),
                subtitle = if (showEmptyFavoritesTab) {
                    stringResource(R.string.settings_show_empty_favorites_on)
                } else {
                    stringResource(R.string.settings_show_empty_favorites_off)
                },
                checked = showEmptyFavoritesTab,
                onCheckedChange = onShowEmptyFavoritesTabChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_android_auto_artwork_title),
                subtitle = if (showAndroidAutoArtwork) {
                    stringResource(R.string.settings_android_auto_artwork_on)
                } else {
                    stringResource(R.string.settings_android_auto_artwork_off)
                },
                checked = showAndroidAutoArtwork,
                onCheckedChange = onShowAndroidAutoArtworkChange,
            )
            SettingsDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_playback))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_autoplay_next_episode_title),
                subtitle = if (autoPlayNextEpisode) {
                    stringResource(R.string.settings_autoplay_next_episode_on)
                } else {
                    stringResource(R.string.settings_autoplay_next_episode_off)
                },
                checked = autoPlayNextEpisode,
                onCheckedChange = onAutoPlayNextEpisodeChange,
            )
            SettingsPauseTimeoutRow(
                title = stringResource(R.string.settings_stop_service_after_pause_title),
                subtitle = if (stopServiceAfterPauseMinutes == STOP_SERVICE_AFTER_PAUSE_NEVER) {
                    stringResource(R.string.settings_stop_service_after_pause_never_subtitle)
                } else {
                    stringResource(R.string.settings_stop_service_after_pause_subtitle, stopServiceAfterPauseMinutes)
                },
                selectedMinutes = stopServiceAfterPauseMinutes,
                onSelectedMinutesChange = onStopServiceAfterPauseMinutesChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_keep_media_card_title),
                subtitle = stringResource(
                    if (keepMediaCard) R.string.settings_keep_media_card_on
                    else R.string.settings_keep_media_card_off,
                ),
                checked = keepMediaCard,
                onCheckedChange = onKeepMediaCardChange,
                supportingContent = {
                    TextButton(
                        onClick = { showKeepMediaCardInfo = true },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.action_more_info))
                    }
                },
            )
            SettingsDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_library))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_station_covers_title),
                subtitle = if (showStationArtwork) {
                    stringResource(R.string.settings_station_covers_on)
                } else {
                    stringResource(R.string.settings_station_covers_off)
                },
                checked = showStationArtwork,
                onCheckedChange = onShowStationArtworkChange,
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sync_artwork_title),
                subtitle = stringResource(R.string.settings_sync_artwork_subtitle),
                syncing = syncingStationArtwork,
                onClick = onSyncStationArtwork,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_confirm_station_deletion_title),
                subtitle = if (confirmStationDeletion) {
                    stringResource(R.string.settings_confirm_station_deletion_on)
                } else {
                    stringResource(R.string.settings_confirm_station_deletion_off)
                },
                checked = confirmStationDeletion,
                onCheckedChange = onConfirmStationDeletionChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_remove_tracking_title),
                subtitle = if (removeTrackingParameters) {
                    stringResource(R.string.settings_remove_tracking_on)
                } else {
                    stringResource(R.string.settings_remove_tracking_off)
                },
                checked = removeTrackingParameters,
                onCheckedChange = onRemoveTrackingParametersChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_save_radio_browser_tags_title),
                subtitle = if (addRadioBrowserTags) {
                    stringResource(R.string.settings_save_radio_browser_tags_on)
                } else {
                    stringResource(R.string.settings_save_radio_browser_tags_off)
                },
                checked = addRadioBrowserTags,
                onCheckedChange = onAddRadioBrowserTagsChange,
            )
            SettingsDangerRow(
                title = stringResource(R.string.settings_clear_library_title),
                onClick = { confirmClearLibrary = true },
            )
            SettingsDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_podcast))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_podcast_refresh_on_start_title),
                subtitle = stringResource(
                    if (podcastAutoRefreshOnStart) R.string.settings_podcast_refresh_on_start_on
                    else R.string.settings_podcast_refresh_on_start_off,
                ),
                checked = podcastAutoRefreshOnStart,
                onCheckedChange = onPodcastAutoRefreshOnStartChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_podcast_refresh_on_enter_list_title),
                subtitle = stringResource(
                    if (podcastAutoRefreshOnEnterList) R.string.settings_podcast_refresh_on_enter_list_on
                    else R.string.settings_podcast_refresh_on_enter_list_off,
                ),
                checked = podcastAutoRefreshOnEnterList,
                onCheckedChange = onPodcastAutoRefreshOnEnterListChange,
            )
            SettingsPodcastRefreshIntervalRow(
                title = stringResource(R.string.settings_podcast_refresh_interval_title),
                subtitle = stringResource(R.string.settings_podcast_refresh_interval_subtitle),
                selectedMinutes = podcastRefreshIntervalMinutes,
                onSelectedMinutesChange = onPodcastRefreshIntervalMinutesChange,
            )
            SettingsPodcastSearchRegionRow(
                title = stringResource(R.string.settings_podcast_search_region_title),
                subtitle = stringResource(R.string.settings_podcast_search_region_subtitle),
                selectedRegion = podcastSearchRegion,
                onSelectedRegionChange = onPodcastSearchRegionChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_podcast_wifi_only_download_title),
                subtitle = if (podcastWifiOnlyDownload) {
                    stringResource(R.string.settings_podcast_wifi_only_download_on)
                } else {
                    stringResource(R.string.settings_podcast_wifi_only_download_off)
                },
                checked = podcastWifiOnlyDownload,
                onCheckedChange = onPodcastWifiOnlyDownloadChange,
            )
            SettingsDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_control_panel))
            SettingsSwitchRow(
                title = stringResource(R.string.settings_show_player_metadata_title),
                subtitle = if (showPlayerMetadata) {
                    stringResource(R.string.settings_show_player_metadata_on)
                } else {
                    stringResource(R.string.settings_show_player_metadata_off)
                },
                checked = showPlayerMetadata,
                onCheckedChange = onShowPlayerMetadataChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_show_bitrate_title),
                subtitle = if (showBitrateInControlPanel) {
                    stringResource(R.string.settings_show_bitrate_on)
                } else {
                    stringResource(R.string.settings_show_bitrate_off)
                },
                checked = showBitrateInControlPanel,
                onCheckedChange = onShowBitrateInControlPanelChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_marquee_track_title_title),
                subtitle = if (marqueeTrackTitle) {
                    stringResource(R.string.settings_marquee_track_title_on)
                } else {
                    stringResource(R.string.settings_marquee_track_title_off)
                },
                checked = marqueeTrackTitle,
                onCheckedChange = onMarqueeTrackTitleChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_expand_player_panel_title),
                subtitle = if (autoExpandPlayerPanelOnPlayback) {
                    stringResource(R.string.settings_auto_expand_player_panel_on)
                } else {
                    stringResource(R.string.settings_auto_expand_player_panel_off)
                },
                checked = autoExpandPlayerPanelOnPlayback,
                onCheckedChange = onAutoExpandPlayerPanelOnPlaybackChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_collapse_player_panel_search_title),
                subtitle = if (collapsePlayerPanelInSearch) {
                    stringResource(R.string.settings_collapse_player_panel_search_on)
                } else {
                    stringResource(R.string.settings_collapse_player_panel_search_off)
                },
                checked = collapsePlayerPanelInSearch,
                onCheckedChange = onCollapsePlayerPanelInSearchChange,
            )
            SettingsDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_backup_restore))
            SettingsFileActionRow(
                iconRes = R.drawable.ic_file_export,
                title = stringResource(R.string.settings_backup_export_title),
                subtitle = stringResource(
                    R.string.settings_backup_export_subtitle,
                    stationCount,
                    podcastCount,
                    episodeCount,
                ),
                onClick = onExportBackup,
            )
            SettingsFileActionRow(
                iconRes = R.drawable.ic_file_import,
                title = stringResource(R.string.settings_backup_import_title),
                subtitle = stringResource(R.string.settings_backup_import_subtitle),
                onClick = onImportBackup,
            )
            SettingsFileActionRow(
                iconRes = R.drawable.ic_file_export,
                title = stringResource(R.string.settings_txt_export_title),
                subtitle = stringResource(R.string.settings_txt_export_subtitle),
                onClick = onExportSimpleText,
            )
            SettingsFileActionRow(
                iconRes = R.drawable.ic_file_import,
                title = stringResource(R.string.settings_txt_import_title),
                subtitle = stringResource(R.string.settings_txt_import_subtitle),
                onClick = onImportSimpleText,
            )
        }
        OmniScrollIndicator(
            scrollIndicatorState = scrollState.scrollIndicatorState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
            .padding(end = RadioSpacing.extraSmall),
        )
    }

    if (showKeepMediaCardInfo) {
        OmniInfoDialog(
            title = stringResource(R.string.settings_keep_media_card_title),
            text = stringResource(R.string.settings_keep_media_card_note),
            onDismiss = { showKeepMediaCardInfo = false },
        )
    }

    if (confirmClearLibrary) {
        OmniConfirmDialog(
            title = stringResource(R.string.dialog_clear_library_title),
            text = stringResource(R.string.dialog_clear_library_text),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmClearLibrary = false },
            onConfirm = {
                confirmClearLibrary = false
                onClearLibrary()
            },
        )
    }
}

@Composable
private fun SettingsLanguageRow(
    title: String,
    subtitle: String,
    options: List<AppLanguageOption>,
    selectedTag: String?,
    onLanguageSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.tag == selectedTag }
        ?.let { option ->
            if (option.tag == null) stringResource(R.string.settings_language_system) else option.displayName
        }
        ?: selectedTag.orEmpty()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = selectedLabel,
                    color = RadioPrimary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = RadioSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                        contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                        text = {
                            Text(
                                text = if (option.tag == null) {
                                    stringResource(R.string.settings_language_system)
                                } else option.displayName,
                                color = RadioText,
                            )
                        },
                        onClick = {
                            expanded = false
                            onLanguageSelect(option.tag)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun podcastSearchRegionLabel(region: String): String {
    return stringResource(
        when (region) {
            "cn" -> R.string.settings_podcast_search_region_cn
            "tw" -> R.string.settings_podcast_search_region_tw
            "hk" -> R.string.settings_podcast_search_region_hk
            "jp" -> R.string.settings_podcast_search_region_jp
            "kr" -> R.string.settings_podcast_search_region_kr
            "gb" -> R.string.settings_podcast_search_region_gb
            "de" -> R.string.settings_podcast_search_region_de
            else -> R.string.settings_podcast_search_region_us
        },
    )
}

@Composable
private fun podcastRefreshIntervalLabel(minutes: Int): String {
    return when (minutes) {
        60 -> stringResource(R.string.settings_podcast_refresh_interval_1h)
        360 -> stringResource(R.string.settings_podcast_refresh_interval_6h)
        1440 -> stringResource(R.string.settings_podcast_refresh_interval_1d)
        else -> stringResource(R.string.settings_podcast_refresh_interval_10m)
    }
}

/**
 * 播客刷新间隔行：交互复刻「暂停后停止服务」（当前值 + 点开菜单 + 选中项即当前值），
 * 选项取 [PODCAST_REFRESH_INTERVAL_OPTIONS]（10 分钟 / 1 小时 / 6 小时 / 每天）。
 */
@Composable
private fun SettingsPodcastRefreshIntervalRow(
    title: String,
    subtitle: String,
    selectedMinutes: Int,
    onSelectedMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = podcastRefreshIntervalLabel(selectedMinutes),
                    color = RadioPrimary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = RadioSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            ) {
                PODCAST_REFRESH_INTERVAL_OPTIONS.forEach { minutes ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                        contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                        text = {
                            Text(
                                text = podcastRefreshIntervalLabel(minutes),
                                color = RadioText,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelectedMinutesChange(minutes)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 在线搜索的 iTunes 商店地区：交互与刷新间隔行一致（当前值 + 菜单），
 * 选项取 [PODCAST_SEARCH_REGIONS]，默认美国区。
 */
@Composable
private fun SettingsPodcastSearchRegionRow(
    title: String,
    subtitle: String,
    selectedRegion: String,
    onSelectedRegionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = podcastSearchRegionLabel(selectedRegion),
                    color = RadioPrimary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = RadioSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            ) {
                PODCAST_SEARCH_REGIONS.forEach { region ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                        contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                        text = {
                            Text(
                                text = podcastSearchRegionLabel(region),
                                color = RadioText,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelectedRegionChange(region)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPauseTimeoutRow(
    title: String,
    subtitle: String,
    selectedMinutes: Int,
    onSelectedMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(STOP_SERVICE_AFTER_PAUSE_NEVER, 1, 5, 10, 30)
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = pauseTimeoutLabel(selectedMinutes),
                    color = RadioPrimary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = RadioSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(RadioCorners.medium),
            ) {
                options.forEach { minutes ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = RadioSizes.menuItemMinHeight),
                        contentPadding = PaddingValues(horizontal = RadioSpacing.large),
                        text = {
                            Text(
                                text = pauseTimeoutLabel(minutes),
                                color = RadioText,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelectedMinutesChange(minutes)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text = title,
        color = color ?: RadioTextMuted,
        fontSize = RadioTextSizes.bodySmall,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = RadioSpacing.fieldGap, bottom = RadioSpacing.small),
    )
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = RadioOutline.copy(alpha = 0.65f),
        modifier = modifier.padding(top = RadioSpacing.rowVertical, bottom = RadioSpacing.small),
    )
}

@Composable
private fun SettingsThemeRow(
    title: String,
    subtitle: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        ThemeModeSegmentedControl(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )
    }
}

private val AccentSwatchTouchSize = 44.dp
private val AccentSwatchSize = 32.dp

/**
 * 主题配色行：标题/当前配色名 + 一排色点。
 * 色点做成一枚「左浅右深」的双色圆，浅色与深色主题下的主色都能认出来。
 */
@Composable
private fun SettingsAccentRow(
    title: String,
    subtitle: String,
    accentPalette: AccentPalette,
    onAccentPaletteChange: (AccentPalette) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(RadioSpacing.medium),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(RadioSpacing.small),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            AccentPalette.entries.forEach { palette ->
                AccentSwatch(
                    palette = palette,
                    selected = palette == accentPalette,
                    onClick = { onAccentPaletteChange(palette) },
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    palette: AccentPalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(palette.labelRes())
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(AccentSwatchTouchSize)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            )
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(AccentSwatchSize)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) RadioText else RadioOutline,
                    shape = CircleShape,
                )
                .padding(if (selected) 3.dp else 2.dp)
                .clip(CircleShape)
                .background(accentSwatchBrush(palette)),
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(RadioSizes.iconTiny),
                )
            }
        }
    }
}

/** 左半浅色主色、右半深色主色的硬边渐变，代表该配色在浅色/深色下的样子 */
private fun accentSwatchBrush(palette: AccentPalette): Brush {
    val light = Color(palette.seedLight)
    val dark = Color(palette.seedDark)
    return Brush.horizontalGradient(
        0f to light,
        0.5f to light,
        0.5f to dark,
        1f to dark,
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val titleColor = if (enabled) RadioText else RadioTextMuted.copy(alpha = 0.55f)
    val subtitleColor = if (enabled) RadioTextMuted else RadioTextMuted.copy(alpha = 0.45f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = subtitleColor,
                fontSize = RadioTextSizes.bodySmall,
            )
            supportingContent?.invoke()
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = RadioText,
                checkedTrackColor = RadioPrimary,
                uncheckedThumbColor = RadioTextMuted,
                uncheckedTrackColor = RadioSurfaceHigh,
                uncheckedBorderColor = RadioOutline,
                disabledCheckedThumbColor = RadioTextMuted.copy(alpha = 0.45f),
                disabledCheckedTrackColor = RadioSurfaceHigh.copy(alpha = 0.45f),
                disabledUncheckedThumbColor = RadioTextMuted.copy(alpha = 0.35f),
                disabledUncheckedTrackColor = RadioSurfaceHigh.copy(alpha = 0.35f),
                disabledUncheckedBorderColor = RadioOutline.copy(alpha = 0.35f),
            ),
        )
    }
}

@Composable
private fun SettingsDangerRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioDanger,
                fontSize = RadioTextSizes.bodyLarge,
            )
        }
        OmniIconButton(
            painter = painterResource(R.drawable.ic_delete),
            onClick = onClick,
            tint = RadioDanger,
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
    enabled: Boolean = true,
    actionText: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "artwork sync")
    val rotation = if (syncing) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sync rotation",
        ).value
    } else {
        0f
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        if (actionText == null) {
            OmniIconButton(
                painter = painterResource(R.drawable.ic_sync),
                onClick = onClick,
                enabled = enabled && !syncing,
                tint = if (syncing || !enabled) RadioTextMuted else RadioPrimary,
                iconModifier = Modifier.rotate(rotation),
            )
        } else {
            TextButton(
                onClick = onClick,
                enabled = enabled,
            ) {
                Text(
                    text = actionText,
                    color = if (enabled) RadioPrimary else RadioTextMuted,
                )
            }
        }
    }
}

/** 备份与恢复的分组行：左侧文案 + 右侧文件操作图标 */
@Composable
private fun SettingsFileActionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RadioSpacing.groupGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = RadioSpacing.fieldGap),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = RadioText,
                fontSize = RadioTextSizes.bodyLarge,
            )
            Text(
                text = subtitle,
                color = RadioTextMuted,
                fontSize = RadioTextSizes.bodySmall,
            )
        }
        OmniIconButton(
            painter = painterResource(iconRes),
            onClick = onClick,
            tint = RadioPrimary,
        )
    }
}
