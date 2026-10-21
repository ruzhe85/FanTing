package omnibeat.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import omnibeat.app.data.StationRepository
import omnibeat.app.model.MainPage
import omnibeat.app.model.Station
import omnibeat.app.model.StationSortState

@Composable
fun RepositoryStateEffects(
    repository: StationRepository,
    selectedPage: MainPage,
    visibleTabPages: List<MainPage>,
    onStationsChange: (List<Station>) -> Unit,
    onAutoPlayNextEpisodeChange: (Boolean) -> Unit,
    onStopServiceAfterPauseMinutesChange: (Int) -> Unit,
    onKeepMediaCardChange: (Boolean) -> Unit,
    onShowStationArtworkChange: (Boolean) -> Unit,
    onShowEmptyFavoritesTabChange: (Boolean) -> Unit,
    onConfirmStationDeletionChange: (Boolean) -> Unit,
    onShowBitrateInControlPanelChange: (Boolean) -> Unit,
    onMarqueeTrackTitleChange: (Boolean) -> Unit,
    onShowPlayerMetadataChange: (Boolean) -> Unit,
    onAutoExpandPlayerPanelOnPlaybackChange: (Boolean) -> Unit,
    onCollapsePlayerPanelInSearchChange: (Boolean) -> Unit,
    onAddRadioBrowserTagsChange: (Boolean) -> Unit,
    onRemoveTrackingParametersChange: (Boolean) -> Unit,
    onShowAndroidAutoArtworkChange: (Boolean) -> Unit,
    onResumeAfterInterruptionChange: (Boolean) -> Unit,
    onPlayerPanelCollapsedChange: (Boolean) -> Unit,
    onLastMainPageChange: (MainPage) -> Unit,
    onSelectedPageRestore: (MainPage) -> Unit,
    onStationSortStateChange: (StationSortState) -> Unit,
    onCustomStationOrderChange: (List<String>) -> Unit,
    onCustomFavoriteOrderChange: (List<String>) -> Unit,
    onLastPlayedStationIdChange: (String?) -> Unit,
    onRecentlyPlayedStationIdsChange: (List<String>) -> Unit,
) {
    LaunchedEffect(repository) {
        repository.stations.collect { savedStations ->
            onStationsChange(savedStations)
        }
    }

    LaunchedEffect(repository) {
        repository.autoPlayNextEpisode.collect { savedAutoPlayNextEpisode ->
            onAutoPlayNextEpisodeChange(savedAutoPlayNextEpisode)
        }
    }

    LaunchedEffect(repository) {
        repository.stopServiceAfterPauseMinutes.collect { savedMinutes ->
            onStopServiceAfterPauseMinutesChange(savedMinutes)
        }
    }

    LaunchedEffect(repository) {
        repository.keepMediaCard.collect { keep ->
            onKeepMediaCardChange(keep)
        }
    }

    LaunchedEffect(repository) {
        repository.showStationArtwork.collect { savedShowStationArtwork ->
            onShowStationArtworkChange(savedShowStationArtwork)
        }
    }

    LaunchedEffect(repository) {
        repository.showEmptyFavoritesTab.collect { savedShowEmptyFavoritesTab ->
            onShowEmptyFavoritesTabChange(savedShowEmptyFavoritesTab)
        }
    }

    LaunchedEffect(repository) {
        repository.confirmStationDeletion.collect { savedConfirmStationDeletion ->
            onConfirmStationDeletionChange(savedConfirmStationDeletion)
        }
    }

    LaunchedEffect(repository) {
        repository.showBitrateInControlPanel.collect { savedShowBitrate ->
            onShowBitrateInControlPanelChange(savedShowBitrate)
        }
    }

    LaunchedEffect(repository) {
        repository.marqueeTrackTitle.collect { savedMarqueeTrackTitle ->
            onMarqueeTrackTitleChange(savedMarqueeTrackTitle)
        }
    }

    LaunchedEffect(repository) {
        repository.showPlayerMetadata.collect { savedShowPlayerMetadata ->
            onShowPlayerMetadataChange(savedShowPlayerMetadata)
        }
    }

    LaunchedEffect(repository) {
        repository.autoExpandPlayerPanelOnPlayback.collect { savedAutoExpand ->
            onAutoExpandPlayerPanelOnPlaybackChange(savedAutoExpand)
        }
    }

    LaunchedEffect(repository) {
        repository.collapsePlayerPanelInSearch.collect { savedCollapseInSearch ->
            onCollapsePlayerPanelInSearchChange(savedCollapseInSearch)
        }
    }

    LaunchedEffect(repository) {
        repository.addRadioBrowserTags.collect { savedAddRadioBrowserTags ->
            onAddRadioBrowserTagsChange(savedAddRadioBrowserTags)
        }
    }

    LaunchedEffect(repository) {
        repository.removeTrackingParameters.collect { savedRemoveTrackingParameters ->
            onRemoveTrackingParametersChange(savedRemoveTrackingParameters)
        }
    }

    LaunchedEffect(repository) {
        repository.showAndroidAutoArtwork.collect(onShowAndroidAutoArtworkChange)
    }

    LaunchedEffect(repository) {
        repository.resumeAfterInterruption.collect(onResumeAfterInterruptionChange)
    }

    LaunchedEffect(repository) {
        repository.playerPanelCollapsed.collect { savedPlayerPanelCollapsed ->
            onPlayerPanelCollapsedChange(savedPlayerPanelCollapsed)
        }
    }

    LaunchedEffect(repository, selectedPage, visibleTabPages) {
        repository.lastMainPage.collect { savedPage ->
            // 用 allTabPages：「上次停留页面」可能是播客页；tabPages 只表示电台区
            val restoredPage = MainPage.allTabPages.firstOrNull { it.name == savedPage } ?: MainPage.Stations
            onLastMainPageChange(restoredPage)
            if (selectedPage in visibleTabPages) {
                onSelectedPageRestore(restoredPage)
            }
        }
    }

    LaunchedEffect(repository) {
        repository.stationSortState.collect { savedSortState ->
            onStationSortStateChange(savedSortState)
        }
    }

    LaunchedEffect(repository) {
        repository.customStationOrder.collect { savedOrder ->
            onCustomStationOrderChange(savedOrder)
        }
    }

    LaunchedEffect(repository) {
        repository.customFavoriteOrder.collect { savedOrder ->
            onCustomFavoriteOrderChange(savedOrder)
        }
    }

    LaunchedEffect(repository) {
        repository.lastPlayedStationId.collect { savedStationId ->
            onLastPlayedStationIdChange(savedStationId)
        }
    }

    LaunchedEffect(repository) {
        repository.recentlyPlayedStationIds.collect { savedIds ->
            onRecentlyPlayedStationIdsChange(savedIds)
        }
    }
}
