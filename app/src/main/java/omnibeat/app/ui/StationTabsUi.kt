package omnibeat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import omnibeat.app.R
import omnibeat.app.model.FavoritesSection
import omnibeat.app.model.MainPage
import omnibeat.app.model.Station
import omnibeat.app.ui.appStringResource as stringResource
import omnibeat.app.model.StationEditorState
import omnibeat.app.model.StationReorderDraft
import omnibeat.app.model.StationSortState
import omnibeat.app.model.sortedStations

@Composable
fun StationTabsPager(
    visibleTabPages: List<MainPage>,
    pagerState: PagerState,
    stations: List<Station>,
    selectedStationId: String?,
    playingStationId: String?,
    sortState: StationSortState,
    customStationOrder: List<String>,
    customFavoriteOrder: List<String>,
    reorderDraft: StationReorderDraft?,
    scrollToSelectedRequest: Int,
    scrollToStationId: String?,
    showArtwork: Boolean,
    listEnabled: Boolean,
    onMove: (Int, Int) -> Unit,
    onFavoriteClick: (Station) -> Unit,
    onEditStation: (StationEditorState) -> Unit,
    onStationClick: (Station) -> Unit,
    favoritesSection: FavoritesSection = FavoritesSection.Stations,
    onFavoritesSectionChange: (FavoritesSection) -> Unit = {},
    favoriteEpisodeCount: Int = 0,
    /** 收藏 tab 的节目列表由外层注入：数据与播放回调都宿主在 OmniBeatApp */
    episodeFavoriteContent: @Composable () -> Unit = {},
    podcastContent: @Composable (Modifier) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = reorderDraft == null,
        modifier = modifier,
    ) { pageIndex ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pagerFade(pagerState, pageIndex),
        ) {
            when (visibleTabPages[pageIndex]) {
                MainPage.Stations -> {
                    val visibleStations = reorderDraft?.stations ?: sortedStations(
                        source = stations,
                        page = MainPage.Stations,
                        sortState = sortState,
                        customStationOrder = customStationOrder,
                        customFavoriteOrder = customFavoriteOrder,
                    )
                    StationPageList(
                        stations = visibleStations,
                        selectedStationId = selectedStationId,
                        playingStationId = playingStationId,
                        emptyContent = {
                            EmptyStationsState(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = RadioSpacing.inset),
                            )
                        },
                        scrollToSelectedRequest = scrollToSelectedRequest,
                        scrollToStationId = scrollToStationId,
                        showArtwork = showArtwork,
                        enabled = listEnabled,
                        reordering = reorderDraft != null,
                        onMove = onMove,
                        onFavoriteClick = onFavoriteClick,
                        onEditStation = { station ->
                            createEditorState(stations, station)?.let(onEditStation)
                        },
                        onStationClick = onStationClick,
                    )
                }

                MainPage.Favorites -> {
                    val favoriteStations = reorderDraft?.stations ?: sortedStations(
                        source = stations.filter { it.isFavorite },
                        page = MainPage.Favorites,
                        sortState = sortState,
                        customStationOrder = customStationOrder,
                        customFavoriteOrder = customFavoriteOrder,
                    )
                    FavoritesTabContent(
                        section = favoritesSection,
                        onSectionChange = onFavoritesSectionChange,
                        stationCount = favoriteStations.size,
                        episodeCount = favoriteEpisodeCount,
                        stationContent = {
                            StationPageList(
                                stations = favoriteStations,
                                selectedStationId = selectedStationId,
                                playingStationId = playingStationId,
                                emptyContent = {
                                    EmptyFavoritesState(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = RadioSpacing.inset),
                                    )
                                },
                                scrollToSelectedRequest = scrollToSelectedRequest,
                                scrollToStationId = scrollToStationId,
                                showArtwork = showArtwork,
                                enabled = listEnabled,
                                reordering = reorderDraft != null,
                                onMove = onMove,
                                onFavoriteClick = onFavoriteClick,
                                onEditStation = { station ->
                                    createEditorState(stations, station)?.let(onEditStation)
                                },
                                onStationClick = onStationClick,
                            )
                        },
                        episodeContent = episodeFavoriteContent,
                    )
                }

                // 播客页内容由外部注入（状态与对话框都宿主在 OmniBeatApp）
                MainPage.Podcasts -> podcastContent(Modifier.fillMaxSize())

                else -> Unit
            }
        }
    }
}

@Composable
private fun StationPageList(
    stations: List<Station>,
    selectedStationId: String?,
    playingStationId: String?,
    emptyContent: @Composable () -> Unit,
    scrollToSelectedRequest: Int,
    scrollToStationId: String?,
    showArtwork: Boolean,
    enabled: Boolean,
    reordering: Boolean,
    onMove: (Int, Int) -> Unit,
    onFavoriteClick: (Station) -> Unit,
    onEditStation: (Station) -> Unit,
    onStationClick: (Station) -> Unit,
) {
    if (stations.isEmpty()) {
        emptyContent()
        return
    }

    StationList(
        stations = stations,
        selectedIndex = stations.indexOfFirst { it.id == selectedStationId },
        playingStationId = playingStationId,
        scrollToSelectedRequest = scrollToSelectedRequest,
        scrollToStationId = scrollToStationId,
        showArtwork = showArtwork,
        enabled = enabled,
        reordering = reordering,
        onMove = onMove,
        onFavoriteClick = { _, station -> onFavoriteClick(station) },
        onStationEdit = { _, station -> onEditStation(station) },
        onStationClick = { _, station -> onStationClick(station) },
    )
}

private fun createEditorState(
    stations: List<Station>,
    station: Station,
): StationEditorState? {
    val index = stations.indexOfFirst { it.id == station.id }
    if (index == -1) {
        return null
    }
    return StationEditorState(
        stationIndex = index,
        title = station.title,
        streamUrl = station.streamUrl,
        tags = station.tags.joinToString(", "),
        dateAdded = station.dateAdded,
        imageUrl = station.imageUrl,
    )
}

/**
 * 收藏 tab：顶部分段「电台 / 节目」，两份列表由调用方注入。
 * 记住上一次停留的分段；若当前分段为空而另一段有内容，自动落到有内容的那一段。
 */
@Composable
fun FavoritesTabContent(
    section: FavoritesSection,
    onSectionChange: (FavoritesSection) -> Unit,
    stationCount: Int,
    episodeCount: Int,
    stationContent: @Composable () -> Unit,
    episodeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(section, stationCount, episodeCount) {
        if (section == FavoritesSection.Stations && stationCount == 0 && episodeCount > 0) {
            onSectionChange(FavoritesSection.Episodes)
        } else if (section == FavoritesSection.Episodes && episodeCount == 0 && stationCount > 0) {
            onSectionChange(FavoritesSection.Stations)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RadioSpacing.extraSmall),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = RadioSpacing.page,
                    end = RadioSpacing.page,
                    top = RadioSpacing.small,
                    bottom = RadioSpacing.extraSmall,
                ),
        ) {
            FavoritesSectionTab(
                text = stringResource(R.string.favorites_section_stations, stationCount),
                selected = section == FavoritesSection.Stations,
                onClick = { onSectionChange(FavoritesSection.Stations) },
                modifier = Modifier.weight(1f),
            )
            FavoritesSectionTab(
                text = stringResource(R.string.favorites_section_episodes, episodeCount),
                selected = section == FavoritesSection.Episodes,
                onClick = { onSectionChange(FavoritesSection.Episodes) },
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (section == FavoritesSection.Stations) {
                stationContent()
            } else {
                episodeContent()
            }
        }
    }
}

@Composable
private fun FavoritesSectionTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = if (selected) RadioText else RadioTextMuted,
        fontSize = RadioTextSizes.body,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(RadioCorners.pill))
            .background(if (selected) RadioSurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = RadioSpacing.small),
    )
}
