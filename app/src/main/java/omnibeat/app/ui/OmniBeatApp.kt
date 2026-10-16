package omnibeat.app.ui

import omnibeat.app.R
import omnibeat.app.data.parseStationTags
import omnibeat.app.data.StationExportData
import omnibeat.app.model.STATION_STREAM_URL_MAX_LENGTH
import omnibeat.app.model.STATION_TITLE_MAX_LENGTH
import omnibeat.app.radio.RadioBrowserClient
import omnibeat.app.radio.RadioBrowserFilterOption
import omnibeat.app.radio.RadioBrowserSearchParams

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import omnibeat.app.data.DEFAULT_STOP_SERVICE_AFTER_PAUSE_MINUTES
import omnibeat.app.data.PodcastRepository
import omnibeat.app.data.SimpleStationTextCodec
import omnibeat.app.data.StationExportCodec
import omnibeat.app.data.StationImportMode
import omnibeat.app.data.StationRepository
import omnibeat.app.data.StationArtworkCache
import omnibeat.app.data.TranslationLanguage
import omnibeat.app.data.appString
import omnibeat.app.data.removeTrackingParameters
import omnibeat.app.data.normalizeStreamUrl
import omnibeat.app.model.MainPage
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastSubscription
import omnibeat.app.model.Station
import omnibeat.app.model.StationEditorState
import omnibeat.app.model.StationReorderDraft
import omnibeat.app.model.StationSortMode
import omnibeat.app.model.StationSortState
import omnibeat.app.model.ThemeMode
import omnibeat.app.model.customSortedStations
import omnibeat.app.model.sortedStations
import omnibeat.app.network.NetworkStatus
import omnibeat.app.playback.PlaybackService
import omnibeat.app.podcast.RssParser
import omnibeat.app.radio.RadioBrowserStation
import omnibeat.app.radio.stationTags
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

/** 距结尾不足这个时长就算「听完」，避免结尾几秒的误差让单集一直显示未听完 */
private const val EPISODE_COMPLETED_TAIL_MS = 15_000L

@Composable
fun OmniBeatApp() {
    val context = LocalContext.current
    val repository = remember(context) { StationRepository(context.applicationContext) }
    // 播客订阅存在独立的 DataStore 里，与电台库互不干扰
    val podcastRepository = remember(context) { PodcastRepository(context.applicationContext) }
    val podcastSubscriptions by podcastRepository.subscriptions.collectAsState(initial = emptyList())
    val podcastPlaybackSpeed by podcastRepository.playbackSpeed
        .collectAsState(initial = PODCAST_DEFAULT_PLAYBACK_SPEED)
    // 播客刷新偏好（设置-播客分组可改）：自动刷新开关与刷新间隔
    val podcastAutoRefreshOnStart by podcastRepository.autoRefreshOnStart
        .collectAsState(initial = true)
    val podcastAutoRefreshOnEnterList by podcastRepository.autoRefreshOnEnterList
        .collectAsState(initial = true)
    val podcastRefreshIntervalMinutes by podcastRepository.refreshIntervalMinutes
        .collectAsState(initial = PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES)
    val translationEnabled by repository.translationEnabled.collectAsState(initial = false)
    var translationLoaded by remember(repository) { mutableStateOf(false) }
    LaunchedEffect(repository) {
        TranslationLanguage.load(repository)
        translationLoaded = true
    }
    val themeMode by repository.themeMode.collectAsState(initial = ThemeMode.System)
    val appLanguage = context.applicationContext.currentAppLanguage()
    val useDarkTheme = shouldUseDarkTheme(themeMode)

    OmniBeatTheme(themeMode = themeMode) {
        val resources = LocalResources.current
        val statusBarColor = RadioBackground
        val navigationBarColor = RadioBackground
        SideEffect {
            val activity = context.findActivity() as? ComponentActivity ?: return@SideEffect
            val statusBarArgb = statusBarColor.toArgb()
            val navigationBarArgb = navigationBarColor.toArgb()
            activity.enableEdgeToEdge(
                statusBarStyle = if (useDarkTheme) {
                    SystemBarStyle.dark(statusBarArgb)
                } else {
                    SystemBarStyle.light(statusBarArgb, statusBarArgb)
                },
                navigationBarStyle = if (useDarkTheme) {
                    SystemBarStyle.dark(navigationBarArgb)
                } else {
                    SystemBarStyle.light(navigationBarArgb, navigationBarArgb)
                },
            )
        }

        val scope = rememberCoroutineScope()
        // 进度落盘用的额外作用域：退出时的最后一次写不能跟着组合一起被取消
        val progressScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
        val onboardingCompleted by repository.onboardingCompleted.collectAsState(initial = null)

        if (onboardingCompleted == null || !translationLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RadioBackground),
            )
            return@OmniBeatTheme
        }

        if (onboardingCompleted == false) {
            OnboardingFlow(
                onFinished = {
                    scope.launch { repository.saveOnboardingCompleted(true) }
                },
                themeMode = themeMode,
                onThemeModeChange = { nextThemeMode ->
                    scope.launch { repository.saveThemeMode(nextThemeMode) }
                },
                modifier = Modifier.fillMaxSize(),
            )
            return@OmniBeatTheme
        }

        val updates = (context.applicationContext as? AppUpdateProvider)?.rememberUpdates()
        val radioBrowserClient = remember { RadioBrowserClient(context.applicationContext) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val playbackState by PlaybackService.state.collectAsState()

        var stations by remember { mutableStateOf(emptyList<Station>()) }
        var appVolume by remember { mutableFloatStateOf(0.75f) }
        var rememberLastStation by remember { mutableStateOf(true) }
        var autoPlayNextEpisode by remember { mutableStateOf(true) }
        var stopServiceAfterPauseMinutes by remember { mutableIntStateOf(DEFAULT_STOP_SERVICE_AFTER_PAUSE_MINUTES) }
        var keepMediaCard by remember { mutableStateOf(repository.keepMediaCard.value) }
        var showStationArtwork by remember { mutableStateOf(true) }
        var syncingStationArtwork by remember { mutableStateOf(false) }
        var showEmptyFavoritesTab by remember { mutableStateOf(true) }
        var confirmStationDeletion by remember { mutableStateOf(true) }
        var showBitrateInControlPanel by remember { mutableStateOf(true) }
        var showUnavailableBitrate by remember { mutableStateOf(false) }
        var marqueeTrackTitle by remember { mutableStateOf(true) }
        var autoExpandPlayerPanelOnPlayback by remember { mutableStateOf(true) }
        var collapsePlayerPanelInSearch by remember { mutableStateOf(true) }
        var addRadioBrowserTags by remember { mutableStateOf(true) }
        var removeTrackingParametersFromUrls by remember { mutableStateOf(true) }
        var showAndroidAutoArtwork by remember { mutableStateOf(true) }
        var playerPanelCollapsed by remember { mutableStateOf(false) }
        var editorState by remember { mutableStateOf<StationEditorState?>(null) }
        var selectedPage by remember { mutableStateOf(MainPage.Stations) }
        var lastMainPage by remember { mutableStateOf(MainPage.Stations) }
        var sortState by remember { mutableStateOf(StationSortState()) }
        var customStationOrder by remember { mutableStateOf(emptyList<String>()) }
        var customFavoriteOrder by remember { mutableStateOf(emptyList<String>()) }
        var reorderDraft by remember { mutableStateOf<StationReorderDraft?>(null) }
        var scrollToSelectedRequest by remember { mutableIntStateOf(0) }
        var scrollToStationId by remember { mutableStateOf<String?>(null) }
        var lastPlayedStationId by remember { mutableStateOf<String?>(null) }
        var pendingExportContent by remember { mutableStateOf<String?>(null) }
        var pendingImportData by remember { mutableStateOf<StationExportData?>(null) }
        var onlineSearchState by remember { mutableStateOf(OnlineStationSearchState()) }
        var onlineSearchResults by remember { mutableStateOf(emptyList<RadioBrowserStation>()) }
        var onlineSearchLoading by remember { mutableStateOf(false) }
        var onlineSearchLoadingMore by remember { mutableStateOf(false) }
        var onlineSearchHasMore by remember { mutableStateOf(false) }
        var onlineSearchLastQuery by remember { mutableStateOf<OnlineStationSearchState?>(null) }
        var errorDialog by remember { mutableStateOf<String?>(null) }
        var onlineCountries by remember { mutableStateOf(emptyList<RadioBrowserFilterOption>()) }
        var onlineLanguages by remember { mutableStateOf(emptyList<RadioBrowserFilterOption>()) }
        var onlineFilterRequest by remember { mutableStateOf<Deferred<List<String>>?>(null) }
        var onlineFilterErrorsHandledBySearch by remember { mutableStateOf(false) }
        var onlineOptionsExpanded by remember { mutableStateOf(false) }
        // 播客页状态：当前打开的订阅（null = 订阅列表）、添加弹窗、加载与错误
        var openPodcastUrl by remember { mutableStateOf<String?>(null) }
        // 正在编辑的订阅（按 feedUrl 定位；地址改动后会重定向到新地址）
        var editPodcastUrl by remember { mutableStateOf<String?>(null) }
        var addPodcastDialogVisible by remember { mutableStateOf(false) }
        var podcastLoading by remember { mutableStateOf(false) }
        // 一键刷新全部的进度：总数 > 0 表示正在刷新，已完成数驱动 FAB 的「n/N」
        var podcastRefreshAllTotal by remember { mutableIntStateOf(0) }
        var podcastRefreshAllDone by remember { mutableIntStateOf(0) }
        var podcastError by remember { mutableStateOf<String?>(null) }
        var pendingPodcastRemoval by remember { mutableStateOf<PodcastSubscription?>(null) }
        // 播客页始终可见；收藏页在「一条收藏都没有且设置里关掉空收藏页」时隐藏
        val visibleTabPages = MainPage.allTabPages.filter { page ->
            page != MainPage.Favorites || showEmptyFavoritesTab || stations.any { it.isFavorite }
        }
        val pagerState = rememberPagerState(pageCount = { visibleTabPages.size })
        val visualSelectedPage by remember(pagerState, visibleTabPages, selectedPage) {
            derivedStateOf {
                if (selectedPage in visibleTabPages) {
                    visibleTabPages.getOrNull(pagerState.currentPage) ?: selectedPage
                } else {
                    selectedPage
                }
            }
        }

        RepositoryStateEffects(
            repository = repository,
            selectedPage = selectedPage,
            visibleTabPages = visibleTabPages,
            onStationsChange = { stations = it },
            onAppVolumeChange = { appVolume = it },
            onRememberLastStationChange = { rememberLastStation = it },
            onAutoPlayNextEpisodeChange = { autoPlayNextEpisode = it },
            onStopServiceAfterPauseMinutesChange = { stopServiceAfterPauseMinutes = it },
            onKeepMediaCardChange = { keepMediaCard = it },
            onShowStationArtworkChange = { showStationArtwork = it },
            onShowEmptyFavoritesTabChange = { showEmptyFavoritesTab = it },
            onConfirmStationDeletionChange = { confirmStationDeletion = it },
            onShowBitrateInControlPanelChange = { showBitrateInControlPanel = it },
            onShowUnavailableBitrateChange = { showUnavailableBitrate = it },
            onMarqueeTrackTitleChange = { marqueeTrackTitle = it },
            onAutoExpandPlayerPanelOnPlaybackChange = { autoExpandPlayerPanelOnPlayback = it },
            onCollapsePlayerPanelInSearchChange = { collapsePlayerPanelInSearch = it },
            onAddRadioBrowserTagsChange = { addRadioBrowserTags = it },
            onRemoveTrackingParametersChange = { removeTrackingParametersFromUrls = it },
            onShowAndroidAutoArtworkChange = { showAndroidAutoArtwork = it },
            onPlayerPanelCollapsedChange = { playerPanelCollapsed = it },
            onLastMainPageChange = { lastMainPage = it },
            onSelectedPageRestore = { selectedPage = it },
            onStationSortStateChange = { sortState = it },
            onCustomStationOrderChange = { customStationOrder = it },
            onCustomFavoriteOrderChange = { customFavoriteOrder = it },
            onLastPlayedStationIdChange = { lastPlayedStationId = it },
        )

        fun writePendingExport(uri: android.net.Uri?, successMessage: String) {
            val exportContent = pendingExportContent ?: return
            pendingExportContent = null
            if (uri == null) {
                return
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(exportContent.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open export file")
                    }
                }.onSuccess {
                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_export_failed, error.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        val jsonExportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            writePendingExport(uri, resources.appString(R.string.toast_export_json))
        }

        val textExportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            writePendingExport(uri, resources.appString(R.string.toast_export_txt))
        }

        fun importStations(mode: StationImportMode) {
            val importData = pendingImportData ?: return
            val importResult = StationExportCodec.buildImportResult(
                importedData = importData,
                currentStations = stations,
                currentSortState = sortState,
                currentCustomStationOrder = customStationOrder,
                currentCustomFavoriteOrder = customFavoriteOrder,
                mode = mode,
            )
            pendingImportData = null
            stations = importResult.stations
            sortState = importResult.sortState
            customStationOrder = importResult.customStationOrder
            customFavoriteOrder = importResult.customFavoriteOrder
            reorderDraft = null
            if (
                playbackState.selectedStation != null &&
                importResult.stations.none { it.id == playbackState.selectedStation?.id }
            ) {
                PlaybackService.stop(context)
            }
            scope.launch {
                repository.saveImportedLibrary(importResult)
                Toast.makeText(context, resources.appString(R.string.toast_stations_imported), Toast.LENGTH_SHORT).show()
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                runCatching {
                    val importText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            inputStream.readBytes().toString(Charsets.UTF_8)
                        } ?: error("Could not open import file")
                    }
                    if (importText.trimStart().startsWith("{")) {
                        StationExportCodec.decode(importText)
                    } else {
                        SimpleStationTextCodec.decode(
                            text = importText,
                            cleanTrackingParameters = removeTrackingParametersFromUrls,
                        )
                    }
                }.onSuccess { importData ->
                    pendingImportData = importData
                    if (stations.isEmpty()) {
                        importStations(StationImportMode.Replace)
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_import_failed, error.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        LaunchedEffect(playbackState.errorText) {
            playbackState.errorText?.let { errorText ->
                errorDialog = errorText
                Toast.makeText(context, resources.appString(R.string.toast_playback_error), Toast.LENGTH_SHORT).show()
            }
        }

        fun loadOnlineFilterOptions(): Deferred<List<String>> {
            onlineFilterRequest?.let { return it }
            onlineFilterErrorsHandledBySearch = false
            val request = scope.async(start = CoroutineStart.LAZY) {
                try {
                    coroutineScope {
                        val requests = buildList {
                            add(async {
                                try {
                                    radioBrowserClient.cachedCountries()?.let { onlineCountries = it }
                                    if (NetworkStatus.isOnline(context)) {
                                        onlineCountries = radioBrowserClient.countries()
                                    }
                                    null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    "${resources.appString(R.string.online_search_countries)}: ${error.message ?: error.toString()}"
                                }
                            })
                            add(async {
                                try {
                                    radioBrowserClient.cachedLanguages()?.let { onlineLanguages = it }
                                    if (NetworkStatus.isOnline(context)) {
                                        onlineLanguages = radioBrowserClient.languages()
                                    }
                                    null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    "${resources.appString(R.string.online_search_languages)}: ${error.message ?: error.toString()}"
                                }
                            })
                        }
                        requests.awaitAll().filterNotNull()
                    }
                } finally {
                    onlineFilterRequest = null
                }
            }
            onlineFilterRequest = request
            return request
        }

        LaunchedEffect(selectedPage) {
            if (selectedPage == MainPage.SearchOnline) {
                onlineOptionsExpanded = true
                val errors = loadOnlineFilterOptions().await()
                if (errors.isNotEmpty() && !onlineFilterErrorsHandledBySearch) {
                    errorDialog = errors.joinToString("\n\n")
                }
            } else {
                onlineOptionsExpanded = false
            }
        }

        fun selectMainPage(page: MainPage) {
            selectedPage = page
            lastMainPage = page
            scope.launch { repository.saveLastMainPage(page.name) }
        }

        fun activeNavigationPage(): MainPage {
            return selectedPage.takeIf { it in visibleTabPages }
                ?: lastMainPage.takeIf { it in visibleTabPages }
                ?: MainPage.Stations
        }

        LaunchedEffect(visibleTabPages, selectedPage) {
            if (selectedPage == MainPage.Favorites && MainPage.Favorites !in visibleTabPages) {
                selectMainPage(MainPage.Stations)
            }
        }

        LaunchedEffect(selectedPage, collapsePlayerPanelInSearch) {
            if (selectedPage == MainPage.SearchOnline && collapsePlayerPanelInSearch && !playerPanelCollapsed) {
                playerPanelCollapsed = true
                repository.savePlayerPanelCollapsed(true)
            }
        }

        SyncPagerWithSelectedPage(
            pagerState = pagerState,
            pages = visibleTabPages,
            selectedPage = selectedPage,
        ) { page ->
            if (selectedPage in visibleTabPages && selectedPage != page) {
                selectMainPage(page)
            }
        }

        fun exportStations() {
            pendingExportContent = StationExportCodec.encode(
                StationExportData(
                    stations = stations,
                    sortState = sortState,
                    customStationOrder = customStationOrder,
                    customFavoriteOrder = customFavoriteOrder,
                ),
            )
            val exportDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))
            jsonExportLauncher.launch("omnibeat_stations_$exportDate.json")
        }

        fun exportSimpleText() {
            if (stations.isEmpty()) {
                pendingExportContent = resources
                    .openRawResource(R.raw.omnibeat_export_example)
                    .bufferedReader()
                    .use { it.readText() }
                textExportLauncher.launch("omnibeat_export_example.txt")
            } else {
                pendingExportContent = SimpleStationTextCodec.encode(stations)
                val exportDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))
                textExportLauncher.launch("omnibeat_stations_$exportDate.txt")
            }
        }

        fun clearLibrary() {
            PlaybackService.stop(context)
            stations = emptyList()
            customStationOrder = emptyList()
            customFavoriteOrder = emptyList()
            reorderDraft = null
            lastPlayedStationId = null
            if (selectedPage == MainPage.Favorites) {
                selectMainPage(MainPage.Stations)
            }
            scope.launch {
                repository.clearLibrary()
                Toast.makeText(context, resources.appString(R.string.toast_library_deleted), Toast.LENGTH_SHORT).show()
            }
        }

        fun hasInternetOrToast(): Boolean {
            if (NetworkStatus.isOnline(context)) {
                return true
            }
            Toast.makeText(context, resources.appString(R.string.toast_no_internet), Toast.LENGTH_SHORT).show()
            return false
        }

        fun searchOnlineStations() {
            if (onlineSearchLoading || onlineSearchLoadingMore) return
            if (!NetworkStatus.isOnline(context)) {
                errorDialog = resources.appString(R.string.toast_no_internet)
                return
            }
            onlineSearchLoading = true
            val query = onlineSearchState
            scope.launch {
                val filterRequest = loadOnlineFilterOptions()
                onlineFilterErrorsHandledBySearch = true
                val errors = mutableListOf<String>()
                try {
                    runCatching {
                        radioBrowserClient.searchStations(query.toRadioBrowserParams(offset = 0))
                    }.onSuccess { results ->
                        onlineSearchResults = results
                        onlineSearchLastQuery = query
                        onlineSearchHasMore = results.size == RadioBrowserSearchParams.DEFAULT_LIMIT
                        onlineOptionsExpanded = false
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        onlineSearchResults = emptyList()
                        onlineSearchLastQuery = null
                        onlineSearchHasMore = false
                        val message = error.message ?: resources.appString(R.string.toast_search_default_error)
                        errors += message
                        Toast.makeText(context, resources.appString(R.string.toast_search_failed), Toast.LENGTH_SHORT).show()
                    }
                    errors += filterRequest.await()
                    if (errors.isNotEmpty()) errorDialog = errors.joinToString("\n\n")
                } finally {
                    onlineSearchLoading = false
                }
            }
        }

        fun stationFromOnlineResult(radioStation: RadioBrowserStation): Station {
            val streamUrl = radioStation.streamUrl
                .trim()
                .let { if (removeTrackingParametersFromUrls) removeTrackingParameters(it) else it }
                .let(::normalizeStreamUrl)
            return Station(
                id = radioStation.stationUuid.takeIf { it.isNotBlank() } ?: streamUrl,
                title = radioStation.title.trim().take(STATION_TITLE_MAX_LENGTH)
                    .ifBlank { streamUrl.take(STATION_TITLE_MAX_LENGTH) },
                streamUrl = streamUrl.take(STATION_STREAM_URL_MAX_LENGTH),
                tags = if (addRadioBrowserTags) radioStation.stationTags() else emptyList(),
                imageUrl = radioStation.imageUrl,
                isFavorite = false,
                dateAdded = Instant.now().toString(),
            )
        }

        fun expandPlayerPanelForUserPlaybackRequest() {
            if (!autoExpandPlayerPanelOnPlayback || !playerPanelCollapsed) return
            playerPanelCollapsed = false
            scope.launch { repository.savePlayerPanelCollapsed(false) }
        }

        fun previewOnlineStation(radioStation: RadioBrowserStation) {
            if (radioStation.streamUrl.isBlank()) return
            if (!hasInternetOrToast()) return
            expandPlayerPanelForUserPlaybackRequest()
            PlaybackService.playPreview(context, stationFromOnlineResult(radioStation))
        }

        /** 添加订阅：先拉取解析，成功才写入；失败只提示，不落库 */
        fun subscribePodcast(feedUrl: String) {
            if (podcastLoading) return
            if (!hasInternetOrToast()) return
            podcastLoading = true
            podcastError = null
            scope.launch {
                runCatching { RssParser.load(feedUrl) }
                    .onSuccess { parsed ->
                        podcastRepository.upsertSubscription(
                            PodcastSubscription(
                                feedUrl = feedUrl,
                                title = parsed.title,
                                imageUrl = parsed.imageUrl,
                                lastFetchedAtMs = System.currentTimeMillis(),
                                episodes = parsed.episodes,
                            ),
                        )
                        addPodcastDialogVisible = false
                    }
                    .onFailure { error ->
                        podcastError = resources.appString(
                            R.string.podcast_error_load,
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                podcastLoading = false
            }
        }

        /** 距上次拉取超过设置的刷新间隔才算「可以再拉」 */
        fun refreshIntervalElapsed(subscription: PodcastSubscription): Boolean {
            val intervalMs = podcastRefreshIntervalMinutes.coerceAtLeast(1) * 60_000L
            return System.currentTimeMillis() - subscription.lastFetchedAtMs >= intervalMs
        }

        /** 单个订阅刷新失败的提示（RssParser 的异常 message 就是可展示文案） */
        fun showPodcastRefreshError(error: Throwable) {
            val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            Toast.makeText(
                context,
                resources.appString(R.string.podcast_refresh_failed, reason),
                Toast.LENGTH_LONG,
            ).show()
        }

        /**
         * 刷新订阅的期数。
         * [force] = true 时忽略节流（下拉刷新 / 一键刷新用），否则按设置里的刷新间隔节流。
         */
        fun refreshPodcast(subscription: PodcastSubscription, force: Boolean = false) {
            if (podcastLoading) return
            if (!force && !refreshIntervalElapsed(subscription)) return
            if (!NetworkStatus.isOnline(context)) return
            podcastLoading = true
            scope.launch {
                // 刷新前的期数 guid 快照：刷新后 diff 就是「新增期数」，用于手动刷新的结果反馈
                val previousGuids = subscription.episodes.mapTo(mutableSetOf()) { it.guid }
                runCatching { RssParser.load(subscription.feedUrl) }
                    .onSuccess { parsed ->
                        val newEpisodes = parsed.episodes.count { it.guid !in previousGuids }
                        podcastRepository.upsertSubscription(
                            subscription.copy(
                                title = parsed.title,
                                imageUrl = parsed.imageUrl ?: subscription.imageUrl,
                                lastFetchedAtMs = System.currentTimeMillis(),
                                episodes = parsed.episodes,
                            ),
                        )
                        // 手动刷新（期数页下拉）必须给结果反馈：新增几期 / 暂无新内容，
                        // 否则用户拉完不知道到底刷没刷到东西；自动刷新（非 force）保持静默
                        if (force) {
                            val message = if (newEpisodes > 0) {
                                resources.appString(R.string.podcast_refresh_single_new, newEpisodes)
                            } else {
                                resources.appString(R.string.podcast_refresh_single_none)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .onFailure { error ->
                        // 手动刷新失败必须说一声，否则用户分不清「没有新期」还是「拉取失败」；
                        // 自动刷新（非 force）保持静默，由批量逻辑统一汇总提示
                        if (force) {
                            showPodcastRefreshError(error)
                        }
                    }
                podcastLoading = false
            }
        }

        /**
         * 一键刷新全部订阅（订阅列表 FAB 用）。
         *
         * 串行执行：并发打多个 feed 容易触发源站限流，逐个拉 + FAB 进度（n/N）可感知且稳妥。
         * [silent] = true 是自动刷新（启动 / 进入列表）：只拉间隔已到的订阅，成功不提示、
         * 仅在失败时汇总提示；[silent] = false 是手动操作：忽略节流全部重拉，结束后给汇总
         * toast（N 个订阅 / 新增 M 期 / 失败 K 个）。
         */
        fun refreshAllPodcasts(targets: List<PodcastSubscription>, silent: Boolean) {
            if (podcastLoading) return
            val pending = if (silent) {
                targets.filter { refreshIntervalElapsed(it) }
            } else {
                targets
            }
            if (pending.isEmpty()) {
                if (!silent) {
                    Toast.makeText(
                        context,
                        resources.appString(R.string.podcast_refresh_nothing),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }
            podcastLoading = true
            podcastRefreshAllTotal = pending.size
            podcastRefreshAllDone = 0
            scope.launch {
                var succeeded = 0
                var failed = 0
                var newEpisodes = 0
                pending.forEach { subscription ->
                    // 刷新前的期数 guid 快照：刷新后 diff 就是「新增期数」
                    val previousGuids = subscription.episodes.mapTo(mutableSetOf()) { it.guid }
                    runCatching { RssParser.load(subscription.feedUrl) }
                        .onSuccess { parsed ->
                            succeeded++
                            newEpisodes += parsed.episodes.count { it.guid !in previousGuids }
                            podcastRepository.upsertSubscription(
                                subscription.copy(
                                    title = parsed.title,
                                    imageUrl = parsed.imageUrl ?: subscription.imageUrl,
                                    lastFetchedAtMs = System.currentTimeMillis(),
                                    episodes = parsed.episodes,
                                ),
                            )
                        }
                        .onFailure { failed++ }
                    podcastRefreshAllDone++
                }
                podcastLoading = false
                podcastRefreshAllTotal = 0
                podcastRefreshAllDone = 0
                when {
                    // 自动刷新不打扰：只在有失败时提示
                    silent && failed > 0 -> Toast.makeText(
                        context,
                        resources.appString(R.string.podcast_refresh_auto_failed, failed),
                        Toast.LENGTH_LONG,
                    ).show()
                    !silent -> Toast.makeText(
                        context,
                        resources.appString(
                            R.string.podcast_refresh_summary,
                            pending.size,
                            newEpisodes,
                            failed,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        fun openPodcast(subscription: PodcastSubscription) {
            openPodcastUrl = subscription.feedUrl
            refreshPodcast(subscription)
        }

        fun removePodcast(subscription: PodcastSubscription) {
            if (openPodcastUrl == subscription.feedUrl) openPodcastUrl = null
            scope.launch { podcastRepository.removeSubscription(subscription.feedUrl) }
        }

        // 启动时按设置自动刷新全部订阅（静默：只拉间隔已到的，成功不提示、失败才提示）。
        // 订阅列表从 DataStore 现读，避免组合首次时 collectAsState 还是空列表导致漏刷。
        // 注意：本地函数必须先声明后使用，这两个 effect 放在刷新函数之后。
        LaunchedEffect(Unit) {
            if (!podcastAutoRefreshOnStart) return@LaunchedEffect
            val saved = podcastRepository.subscriptions.first()
            if (saved.isNotEmpty()) {
                refreshAllPodcasts(saved, silent = true)
            }
        }

        // 进入播客列表页（Tab 页且没打开某个订阅）时按设置自动刷新，同样静默 + 节流。
        // 打开期数页（openPodcastUrl != null）不触发，那边走打开订阅时的单个节流刷新。
        LaunchedEffect(selectedPage, openPodcastUrl) {
            if (selectedPage != MainPage.Podcasts || openPodcastUrl != null) return@LaunchedEffect
            if (!podcastAutoRefreshOnEnterList) return@LaunchedEffect
            val saved = podcastRepository.subscriptions.first()
            if (saved.isNotEmpty()) {
                refreshAllPodcasts(saved, silent = true)
            }
        }

        /**
         * 当前正在播放的单集（连同它所属的订阅）。
         * 播放服务只把单集的 guid 放在 `selectedStation.id` 上，这里再对回播客数据。
         */
        fun currentEpisode(): Pair<PodcastSubscription, PodcastEpisode>? {
            val guid = playbackState.selectedStation?.id ?: return null
            podcastSubscriptions.forEach { subscription ->
                subscription.episodes.firstOrNull { it.guid == guid }?.let { return subscription to it }
            }
            return null
        }

        /**
         * 把当前这一集的播放进度落盘。
         *
         * 只在**暂停 / 切集 / 退出**时调用：DataStore 每次写都是整文件重写，
         * 不能挂到每秒的进度上报上。
         */
        fun saveEpisodeProgress(target: CoroutineScope = scope) {
            val (subscription, episode) = currentEpisode() ?: return
            val playback = playbackState
            // 时长还没拿到时不写，否则会把位置误判成「听完」
            if (playback.durationMs <= 0L) return
            val completed = playback.positionMs >= playback.durationMs - EPISODE_COMPLETED_TAIL_MS
            val position = if (completed) 0L else playback.positionMs
            // 位置为 0 且没听完 = 没有有效进度：不写，免得把已有的断点清成 0
            if (position <= 0L && !completed) return
            if (position == episode.positionMs && completed == episode.completed) return
            target.launch {
                podcastRepository.updateEpisodeProgress(
                    feedUrl = subscription.feedUrl,
                    guid = episode.guid,
                    positionMs = position,
                    completed = completed,
                )
            }
        }

        // 暂停时写回进度（播放中不会触发，所以不会每秒写盘）
        LaunchedEffect(playbackState.isPlaying) {
            if (!playbackState.isPlaying) saveEpisodeProgress()
        }

        // 退出（或旋转）时最后写一次
        DisposableEffect(Unit) {
            onDispose { saveEpisodeProgress(progressScope) }
        }

        /**
         * 播放单集：交给 PlaybackService 的独立入口（点播语义、可拖进度、可倍速）。
         * 单集只作为「播放描述」传过去，不写进电台库。
         */
        fun playPodcastEpisode(subscription: PodcastSubscription, episode: PodcastEpisode) {
            if (episode.audioUrl.isBlank()) return
            if (!hasInternetOrToast()) return
            // 切集前先把上一集的进度落盘（此刻 playbackState 还指向上一集）
            saveEpisodeProgress()
            expandPlayerPanelForUserPlaybackRequest()
            PlaybackService.playEpisode(
                context = context,
                episode = Station(
                    id = episode.guid,
                    title = episode.title,
                    streamUrl = episode.audioUrl,
                    tags = emptyList(),
                    imageUrl = subscription.imageUrl,
                    isFavorite = false,
                    dateAdded = "",
                ),
                // 听完的期从头播；否则从上次位置继续
                startPositionMs = if (episode.completed) 0L else episode.positionMs,
                speed = podcastPlaybackSpeed,
            )
        }

        /**
         * 切换订阅内相邻的一期。
         *
         * 期数列表按时间倒序（最新在上），所以 direction = +1 取列表下一条 = **更早一期**（"下一集"），
         * -1 取更新的一期（"上一集"）。
         * 当前在播的不是订阅内单集时返回 false，调用方回落到电台切台逻辑；
         * 若是单集但已到列表尽头，返回 true（不回落，避免按"下一集"却切了电台）。
         */
        fun playAdjacentEpisode(direction: Int): Boolean {
            val (subscription, episode) = currentEpisode() ?: return false
            val index = subscription.episodes.indexOfFirst { it.guid == episode.guid }
            if (index == -1) return false
            val target = subscription.episodes.getOrNull(index + direction) ?: return true
            playPodcastEpisode(subscription, target)
            return true
        }

        /** 播放某订阅的最新一期（列表按期数倒序，第一条即最新） */
        fun playLatestEpisode(subscription: PodcastSubscription) {
            val latest = subscription.episodes.firstOrNull() ?: return
            playPodcastEpisode(subscription, latest)
        }

        /**
         * 保存订阅编辑。
         * 地址没变 → 只改标题；地址变了 → 先用新地址拉取解析，成功才替换（进度按 guid 搬过去）。
         */
        fun savePodcastEdit(subscription: PodcastSubscription, title: String, feedUrl: String) {
            if (podcastLoading) return
            podcastError = null
            if (feedUrl == subscription.feedUrl) {
                scope.launch {
                    podcastRepository.replaceSubscription(
                        previousFeedUrl = subscription.feedUrl,
                        subscription = subscription.copy(title = title),
                    )
                }
                editPodcastUrl = null
                return
            }
            if (!hasInternetOrToast()) return
            podcastLoading = true
            scope.launch {
                runCatching { RssParser.load(feedUrl) }
                    .onSuccess { parsed ->
                        podcastRepository.replaceSubscription(
                            previousFeedUrl = subscription.feedUrl,
                            subscription = PodcastSubscription(
                                feedUrl = feedUrl,
                                // 没改过标题就跟随新源的标题，改过则保留用户填的
                                title = if (title == subscription.title) parsed.title else title,
                                imageUrl = parsed.imageUrl,
                                lastFetchedAtMs = System.currentTimeMillis(),
                                episodes = parsed.episodes,
                            ),
                        )
                        if (openPodcastUrl == subscription.feedUrl) openPodcastUrl = feedUrl
                        editPodcastUrl = null
                    }
                    .onFailure { error ->
                        podcastError = resources.appString(
                            R.string.podcast_error_load,
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                podcastLoading = false
            }
        }

        // 通知栏 / 锁屏 / 耳机线控的上一集、下一集：服务只发计数，这里执行切集
        LaunchedEffect(playbackState.previousRequest) {
            if (playbackState.previousRequest > 0L) playAdjacentEpisode(-1)
        }
        LaunchedEffect(playbackState.nextRequest) {
            if (playbackState.nextRequest > 0L) playAdjacentEpisode(1)
        }

        // 一期播完：先记为「已听完」，再按设置自动播下一集（更早一期；最后一集自然到头忽略）
        LaunchedEffect(playbackState.ended) {
            if (!playbackState.ended) return@LaunchedEffect
            if (currentEpisode() == null) return@LaunchedEffect
            saveEpisodeProgress()
            if (autoPlayNextEpisode) playAdjacentEpisode(1)
        }

        fun addOnlineStation(radioStation: RadioBrowserStation) {
            val station = stationFromOnlineResult(radioStation)
            if (station.streamUrl.isBlank() || stations.any { it.streamUrl == station.streamUrl }) return
            val nextStations = stations + station.copy(id = UUID.randomUUID().toString())
            stations = nextStations
            scope.launch {
                repository.saveStations(nextStations)
                Toast.makeText(context, resources.appString(R.string.toast_station_added), Toast.LENGTH_SHORT).show()
            }
        }

        suspend fun refreshStationArtwork(station: Station): Boolean {
            val imageUrl = radioBrowserClient.findStationByStreamUrl(station.streamUrl)
                ?.imageUrl
                ?.takeIf { it.isNotBlank() }
                ?: return false
            StationArtworkCache.refresh(context, imageUrl)
            val currentStation = stations.firstOrNull { it.id == station.id } ?: return false
            if (currentStation.streamUrl != station.streamUrl) return false
            if (currentStation.imageUrl != imageUrl) {
                val nextStations = stations.map { savedStation ->
                    if (savedStation.id == station.id) savedStation.copy(imageUrl = imageUrl) else savedStation
                }
                stations = nextStations
                repository.saveStations(nextStations)
            }
            return true
        }

        fun syncStationArtwork() {
            if (syncingStationArtwork) return
            if (!hasInternetOrToast()) return
            syncingStationArtwork = true
            scope.launch {
                var updatedCount = 0
                try {
                    stations.toList().forEach { station ->
                        runCatching {
                            if (refreshStationArtwork(station)) updatedCount++
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                        }
                    }
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_artwork_synced_count, updatedCount),
                        Toast.LENGTH_SHORT,
                    ).show()
                } finally {
                    syncingStationArtwork = false
                }
            }
        }

        fun syncNewStationArtwork(station: Station) {
            if (!showStationArtwork || !station.imageUrl.isNullOrBlank()) return
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        radioBrowserClient.findStationByStreamUrl(station.streamUrl)
                            ?.imageUrl
                            ?.takeIf { it.isNotBlank() }
                    }
                }.onSuccess { imageUrl ->
                    if (imageUrl == null) return@onSuccess
                    val nextStations = stations.map { savedStation ->
                        if (savedStation.id == station.id && savedStation.imageUrl.isNullOrBlank()) {
                            savedStation.copy(imageUrl = imageUrl)
                        } else {
                            savedStation
                        }
                    }
                    stations = nextStations
                    repository.saveStations(nextStations)
                }
            }
        }

        fun syncStationArtwork(index: Int) {
            val station = stations.getOrNull(index) ?: return
            if (!hasInternetOrToast()) return
            Toast.makeText(context, resources.appString(R.string.toast_artwork_searching), Toast.LENGTH_SHORT).show()
            scope.launch {
                runCatching {
                    refreshStationArtwork(station)
                }.onSuccess { refreshed ->
                    if (!refreshed) {
                        Toast.makeText(context, resources.appString(R.string.toast_artwork_not_found), Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    Toast.makeText(context, resources.appString(R.string.toast_artwork_synced), Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_artwork_sync_failed, error.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        fun toggleFavorite(station: Station) {
            val index = stations.indexOfFirst { it.id == station.id }
            if (index == -1) return
            val nextStations = stations.toMutableList().also { list ->
                list[index] = list[index].copy(isFavorite = !list[index].isFavorite)
            }
            stations = nextStations
            scope.launch { repository.saveStations(nextStations) }
        }

        fun navigationStations(): List<Station> {
            if (selectedPage == MainPage.SearchOnline) {
                return onlineSearchResults.map(::stationFromOnlineResult)
            }
            val navigationPage = activeNavigationPage()
            val pageStations = if (navigationPage == MainPage.Favorites) {
                stations.filter { it.isFavorite }
            } else {
                stations
            }
            return sortedStations(
                source = pageStations,
                page = navigationPage,
                sortState = sortState,
                customStationOrder = customStationOrder,
                customFavoriteOrder = customFavoriteOrder,
            )
        }

        fun playStationAt(index: Int) {
            // 从单集切到电台前，先把单集进度存下来
            saveEpisodeProgress()
            if (!hasInternetOrToast()) return
            expandPlayerPanelForUserPlaybackRequest()
            scope.launch { drawerState.close() }
            scrollToStationId = stations.getOrNull(index)?.id
            scrollToSelectedRequest += 1
            PlaybackService.playStation(
                context = context,
                index = index,
                queueIds = navigationStations().map { it.id },
            )
        }

        fun playStation(station: Station) {
            val index = stations.indexOfFirst { it.id == station.id }
            if (index != -1) {
                playStationAt(index)
            } else {
                if (!hasInternetOrToast()) return
                expandPlayerPanelForUserPlaybackRequest()
                PlaybackService.playPreview(context, station)
            }
        }

        fun loadMoreOnlineStations() {
            if (onlineSearchLoading || onlineSearchLoadingMore || !onlineSearchHasMore) return
            if (!NetworkStatus.isOnline(context)) {
                errorDialog = resources.appString(R.string.toast_no_internet)
                return
            }
            val query = onlineSearchLastQuery ?: onlineSearchState
            onlineSearchLoadingMore = true
            scope.launch {
                runCatching {
                    radioBrowserClient.searchStations(
                        query.toRadioBrowserParams(offset = onlineSearchResults.size),
                    )
                }.onSuccess { results ->
                    if (results.isEmpty()) {
                        onlineSearchHasMore = false
                    } else {
                        val existingKeys = onlineSearchResults
                            .map { it.stationUuid.ifBlank { it.streamUrl } }
                            .toSet()
                        val newResults = results.filter { station ->
                            station.stationUuid.ifBlank { station.streamUrl } !in existingKeys
                        }
                        onlineSearchResults = onlineSearchResults + newResults
                        onlineSearchHasMore = results.size == RadioBrowserSearchParams.DEFAULT_LIMIT
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    val message = error.message ?: resources.appString(R.string.toast_load_more_default_error)
                    errorDialog = message
                    Toast.makeText(context, resources.appString(R.string.toast_search_failed), Toast.LENGTH_SHORT).show()
                }
                onlineSearchLoadingMore = false
            }
        }

        fun selectSortMode(nextSortMode: StationSortMode) {
            if (nextSortMode == StationSortMode.Custom) {
                val reorderPage = activeNavigationPage()
                val pageStations = if (reorderPage == MainPage.Favorites) {
                    stations.filter { it.isFavorite }
                } else {
                    stations
                }
                val customOrder = if (reorderPage == MainPage.Favorites) {
                    customFavoriteOrder
                } else {
                    customStationOrder
                }
                val draftStations = if (customOrder.isEmpty()) {
                    navigationStations()
                } else {
                    customSortedStations(pageStations, customOrder)
                }
                val nextSortState = StationSortState(mode = StationSortMode.Custom, ascending = false)
                sortState = nextSortState
                reorderDraft = StationReorderDraft(page = reorderPage, stations = draftStations)
                scope.launch { repository.saveStationSortState(nextSortState) }
                return
            }
            val nextSortState = if (sortState.mode == nextSortMode) {
                sortState.copy(ascending = !sortState.ascending)
            } else {
                StationSortState(mode = nextSortMode, ascending = false)
            }
            sortState = nextSortState
            scope.launch { repository.saveStationSortState(nextSortState) }
        }

        fun moveReorderDraft(fromIndex: Int, toIndex: Int) {
            val currentDraft = reorderDraft ?: return
            if (
                fromIndex !in currentDraft.stations.indices ||
                toIndex !in currentDraft.stations.indices ||
                fromIndex == toIndex
            ) {
                return
            }
            val nextStations = currentDraft.stations.toMutableList().also { list ->
                val movedStation = list.removeAt(fromIndex)
                list.add(toIndex, movedStation)
            }
            reorderDraft = currentDraft.copy(stations = nextStations)
        }

        fun cancelReorder() {
            reorderDraft = null
        }

        fun confirmReorder() {
            val draft = reorderDraft ?: return
            val nextOrder = draft.stations.map { it.id }
            val nextSortState = StationSortState(mode = StationSortMode.Custom, ascending = false)
            if (draft.page == MainPage.Favorites) {
                customFavoriteOrder = nextOrder
            } else {
                customStationOrder = nextOrder
            }
            sortState = nextSortState
            reorderDraft = null
            scope.launch {
                if (draft.page == MainPage.Favorites) {
                    repository.saveCustomFavoriteOrder(nextOrder)
                } else {
                    repository.saveCustomStationOrder(nextOrder)
                }
                repository.saveStationSortState(nextSortState)
            }
        }

        fun playAdjacentStation(direction: Int) {
            val pageStations = navigationStations()
            if (pageStations.isEmpty()) return
            val currentIndex = pageStations.indexOfFirst { it.id == playbackState.selectedStation?.id }
            val nextIndex = if (currentIndex in pageStations.indices) {
                (currentIndex + direction + pageStations.size) % pageStations.size
            } else if (direction > 0) {
                0
            } else {
                pageStations.lastIndex
            }
            playStation(pageStations[nextIndex])
        }

        fun playRandomStation() {
            val pageStations = navigationStations()
            if (pageStations.isEmpty()) return
            val currentIndex = pageStations.indexOfFirst { it.id == playbackState.selectedStation?.id }
            val nextIndex = if (pageStations.size == 1) {
                0
            } else {
                var randomIndex: Int
                do {
                    randomIndex = Random.nextInt(pageStations.size)
                } while (randomIndex == currentIndex)
                randomIndex
            }
            playStation(pageStations[nextIndex])
        }

        fun playOrPause() {
            val hasActivePlaybackRequest = playbackState.isPlaying || playbackState.resolving || playbackState.buffering
            // 播客页：还没选过具体一期时，点播放就播当前订阅的最新一期（避免"点了没反应"）
            if (selectedPage == MainPage.Podcasts &&
                !hasActivePlaybackRequest &&
                playbackState.selectedStation == null
            ) {
                val openSubscription = podcastSubscriptions.firstOrNull { it.feedUrl == openPodcastUrl }
                if (openSubscription != null) {
                    playLatestEpisode(openSubscription)
                    return
                }
            }
            val pageStations = navigationStations()
            if (!hasActivePlaybackRequest && playbackState.selectedStation == null && pageStations.isNotEmpty()) {
                val rememberedStation = if (rememberLastStation && selectedPage != MainPage.SearchOnline) {
                    lastPlayedStationId
                        ?.let { stationId -> stations.firstOrNull { it.id == stationId } }
                } else {
                    null
                }
                playStation(rememberedStation ?: pageStations.first())
            } else {
                if (!hasActivePlaybackRequest && !hasInternetOrToast()) return
                PlaybackService.playOrPause(context)
            }
        }

        BackHandler(enabled = drawerState.isOpen || reorderDraft != null || selectedPage !in visibleTabPages) {
            if (drawerState.isOpen) {
                scope.launch { drawerState.close() }
            } else if (reorderDraft != null) {
                reorderDraft = null
            } else {
                selectedPage = lastMainPage
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                DrawerContent(
                    selectedPage = selectedPage,
                    isOpen = drawerState.isOpen,
                    translationEnabled = translationEnabled,
                    onToggleTranslation = {
                        scope.launch {
                            val enabled = repository.toggleTranslation()
                            val message = if (enabled) R.string.translation_enabled else R.string.translation_disabled
                            Toast.makeText(context, resources.appString(message), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTranslationClick = {
                        selectedPage = MainPage.Translation
                        scope.launch { drawerState.close() }
                    },
                    onStationsClick = {
                        // lastMainPage 可能是播客页（它不属于电台区）：那就回到电台首页
                        selectedPage = lastMainPage.takeIf { it in MainPage.tabPages } ?: MainPage.Stations
                        scope.launch { drawerState.close() }
                    },
                    onPodcastClick = {
                        selectMainPage(MainPage.Podcasts)
                        scope.launch { drawerState.close() }
                    },
                    onExportImportClick = {
                        selectedPage = MainPage.ExportImport
                        scope.launch { drawerState.close() }
                    },
                    onSettingsClick = {
                        selectedPage = MainPage.Settings
                        scope.launch { drawerState.close() }
                    },
                    onAboutClick = {
                        selectedPage = MainPage.About
                        scope.launch { drawerState.close() }
                    },
                    onExitClick = {
                        PlaybackService.stop(context)
                        context.findActivity()?.finishAffinity()
                    },
                )
            },
        ) {
            Scaffold(
                containerColor = RadioBackground,
                topBar = {
                    MainTopBar(
                        selectedPage = selectedPage,
                        visualSelectedPage = visualSelectedPage,
                        tabPages = visibleTabPages,
                        sortState = sortState,
                        reordering = reorderDraft != null,
                        onPageSelected = { page ->
                            if (reorderDraft == null) {
                                selectMainPage(page)
                            }
                        },
                        onSortModeSelected = ::selectSortMode,
                        onCancelReorder = ::cancelReorder,
                        onConfirmReorder = ::confirmReorder,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNavigateBack = { selectedPage = lastMainPage },
                        onAddStation = {
                            editorState = StationEditorState(
                                stationIndex = null,
                                title = "",
                                streamUrl = "",
                                tags = "",
                                dateAdded = null,
                            )
                        },
                        onSearchOnline = {
                            selectedPage = MainPage.SearchOnline
                        },
                        onAddPodcast = {
                            podcastError = null
                            addPodcastDialogVisible = true
                        },
                        onlineSearchControl = if (selectedPage == MainPage.SearchOnline) {
                            { controlModifier ->
                                SearchOptionsTopBarControl(
                                    expanded = onlineOptionsExpanded,
                                    loading = onlineSearchLoading,
                                    onExpandedChange = { onlineOptionsExpanded = it },
                                    modifier = controlModifier,
                                )
                            }
                        } else {
                            null
                        },
                    )
                },
                bottomBar = {
                    // 播客 Tab 且未点进订阅、同时没有内容在播时隐藏面板（避免出现"点了没反应"的播放按钮）；
                    // 若正在播电台或某一期则保留面板，否则无法暂停/停止
                    val showPlayerPanel =
                        (selectedPage in visibleTabPages || selectedPage == MainPage.SearchOnline) &&
                            !(selectedPage == MainPage.Podcasts &&
                                openPodcastUrl == null &&
                                playbackState.selectedStation == null)
                    if (showPlayerPanel) {
                        PlayerPanel(
                            station = playbackState.selectedStation,
                            trackText = playbackState.trackText,
                            trackStatus = playbackState.trackStatus,
                            errorText = playbackState.errorText,
                            streamInfo = playbackState.streamInfo,
                            loading = playbackState.resolving || playbackState.buffering,
                            isPlaying = playbackState.isPlaying,
                            appVolume = appVolume,
                            showBitrate = showBitrateInControlPanel,
                            showUnavailableBitrate = showUnavailableBitrate,
                            marqueeTrackTitle = marqueeTrackTitle,
                            collapsed = playerPanelCollapsed,
                            positionMs = playbackState.positionMs,
                            durationMs = playbackState.durationMs,
                            seekable = playbackState.seekable,
                            onCollapsedChange = { collapsed ->
                                playerPanelCollapsed = collapsed
                                scope.launch { repository.savePlayerPanelCollapsed(collapsed) }
                            },
                            onSeek = { positionMs -> PlaybackService.seekTo(context, positionMs) },
                            playbackSpeed = playbackState.playbackSpeed,
                            onSpeedChange = { speed ->
                                // 立即生效 + 记住，下次播放单集仍是这个倍速
                                PlaybackService.setPlaybackSpeed(context, speed)
                                scope.launch { podcastRepository.savePlaybackSpeed(speed) }
                            },
                            onPlayPause = { playOrPause() },
                            onStop = {
                                // 主动停止前先把单集进度存下来
                                saveEpisodeProgress()
                                PlaybackService.stop(context)
                            },
                            // 单集时切相邻一期；否则维持原来的电台切台
                            onPreviousStation = { if (!playAdjacentEpisode(-1)) playAdjacentStation(-1) },
                            onNextStation = { if (!playAdjacentEpisode(1)) playAdjacentStation(1) },
                            onRandomStation = { playRandomStation() },
                            onVolumeChange = { volume ->
                                appVolume = volume
                                scope.launch { repository.saveAppVolume(volume) }
                            },
                        )
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    when (selectedPage) {
                        MainPage.Stations,
                        MainPage.Favorites,
                        MainPage.Podcasts -> {
                            StationTabsPager(
                                visibleTabPages = visibleTabPages,
                                pagerState = pagerState,
                                stations = stations,
                                selectedStationId = playbackState.selectedStation?.id,
                                playingStationId = playbackState.selectedStation?.id.takeIf { playbackState.isPlaying },
                                sortState = sortState,
                                customStationOrder = customStationOrder,
                                customFavoriteOrder = customFavoriteOrder,
                                reorderDraft = reorderDraft,
                                scrollToSelectedRequest = scrollToSelectedRequest,
                                scrollToStationId = scrollToStationId,
                                showArtwork = showStationArtwork,
                                listEnabled = drawerState.isClosed && reorderDraft == null,
                                onMove = ::moveReorderDraft,
                                onFavoriteClick = ::toggleFavorite,
                                onEditStation = { editorState = it },
                                onStationClick = ::playStation,
                                podcastContent = { contentModifier ->
                                    PodcastTabContent(
                                        subscriptions = podcastSubscriptions,
                                        openSubscriptionUrl = openPodcastUrl,
                                        playingEpisodeId = playbackState.selectedStation?.id,
                                        loading = podcastLoading,
                                        onOpenSubscription = ::openPodcast,
                                        onCloseSubscription = { openPodcastUrl = null },
                                        onDeleteSubscription = { pendingPodcastRemoval = it },
                                        onRefreshSubscription = { refreshPodcast(it, force = true) },
                                        onRefreshAll = {
                                            refreshAllPodcasts(podcastSubscriptions, silent = false)
                                        },
                                        refreshingAll = podcastRefreshAllTotal > 0,
                                        refreshProgress = if (podcastRefreshAllTotal > 0) {
                                            podcastRefreshAllDone to podcastRefreshAllTotal
                                        } else {
                                            null
                                        },
                                        onEditSubscription = {
                                            podcastError = null
                                            editPodcastUrl = it.feedUrl
                                        },
                                        onPlayLatest = ::playLatestEpisode,
                                        onPlayEpisode = ::playPodcastEpisode,
                                        modifier = contentModifier,
                                    )
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        MainPage.ExportImport -> {
                            ExportImportPage(
                                stationCount = stations.size,
                                favoriteCount = stations.count { it.isFavorite },
                                onExportStations = { exportStations() },
                                onExportSimpleText = { exportSimpleText() },
                                onImportStations = {
                                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                                },
                                onClearLibrary = { clearLibrary() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        MainPage.Translation -> TranslationPage(repository = repository)

                        MainPage.SearchOnline -> {
                            OnlineStationSearchPage(
                                searchState = onlineSearchState,
                                countries = onlineCountries,
                                languages = onlineLanguages,
                                results = onlineSearchResults,
                                loading = onlineSearchLoading,
                                loadingMore = onlineSearchLoadingMore,
                                hasMoreResults = onlineSearchHasMore,
                                optionsExpanded = onlineOptionsExpanded,
                                showArtwork = showStationArtwork,
                                addedStreamUrls = stations.mapTo(mutableSetOf()) { it.streamUrl },
                                selectedStreamUrl = playbackState.selectedStation?.streamUrl,
                                onSearchStateChange = {
                                    onlineSearchState = it
                                },
                                onSearch = { searchOnlineStations() },
                                onLoadMore = { loadMoreOnlineStations() },
                                onPreviewStation = { previewOnlineStation(it) },
                                onAddStation = { addOnlineStation(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        MainPage.Settings -> {
                            SettingsPage(
                                themeMode = themeMode,
                                appLanguage = appLanguage,
                                rememberLastStation = rememberLastStation,
                                autoPlayNextEpisode = autoPlayNextEpisode,
                                stopServiceAfterPauseMinutes = stopServiceAfterPauseMinutes,
                                keepMediaCard = keepMediaCard,
                                showStationArtwork = showStationArtwork,
                                syncingStationArtwork = syncingStationArtwork,
                                showEmptyFavoritesTab = showEmptyFavoritesTab,
                                confirmStationDeletion = confirmStationDeletion,
                                showBitrateInControlPanel = showBitrateInControlPanel,
                                showUnavailableBitrate = showUnavailableBitrate,
                                marqueeTrackTitle = marqueeTrackTitle,
                                autoExpandPlayerPanelOnPlayback = autoExpandPlayerPanelOnPlayback,
                                collapsePlayerPanelInSearch = collapsePlayerPanelInSearch,
                                addRadioBrowserTags = addRadioBrowserTags,
                                removeTrackingParameters = removeTrackingParametersFromUrls,
                                showAndroidAutoArtwork = showAndroidAutoArtwork,
                                podcastAutoRefreshOnStart = podcastAutoRefreshOnStart,
                                podcastAutoRefreshOnEnterList = podcastAutoRefreshOnEnterList,
                                podcastRefreshIntervalMinutes = podcastRefreshIntervalMinutes,
                                onThemeModeChange = { nextThemeMode ->
                                    scope.launch { repository.saveThemeMode(nextThemeMode) }
                                },
                                onAppLanguageChange = { nextAppLanguage ->
                                    context.applicationContext.applyAppLanguage(nextAppLanguage)
                                },
                                onRememberLastStationChange = { remember ->
                                    rememberLastStation = remember
                                    scope.launch { repository.saveRememberLastStation(remember) }
                                },
                                onAutoPlayNextEpisodeChange = { autoPlay ->
                                    autoPlayNextEpisode = autoPlay
                                    scope.launch { repository.saveAutoPlayNextEpisode(autoPlay) }
                                },
                                onStopServiceAfterPauseMinutesChange = { minutes ->
                                    stopServiceAfterPauseMinutes = minutes
                                    scope.launch { repository.saveStopServiceAfterPauseMinutes(minutes) }
                                },
                                onKeepMediaCardChange = { keep ->
                                    repository.saveKeepMediaCard(keep)
                                    keepMediaCard = keep
                                },
                                onShowStationArtworkChange = { show ->
                                    showStationArtwork = show
                                    scope.launch { repository.saveShowStationArtwork(show) }
                                },
                                onSyncStationArtwork = { syncStationArtwork() },
                                onShowEmptyFavoritesTabChange = { show ->
                                    showEmptyFavoritesTab = show
                                    scope.launch { repository.saveShowEmptyFavoritesTab(show) }
                                },
                                onConfirmStationDeletionChange = { confirm ->
                                    confirmStationDeletion = confirm
                                    scope.launch { repository.saveConfirmStationDeletion(confirm) }
                                },
                                onShowBitrateInControlPanelChange = { show ->
                                    showBitrateInControlPanel = show
                                    scope.launch { repository.saveShowBitrateInControlPanel(show) }
                                },
                                onShowUnavailableBitrateChange = { show ->
                                    showUnavailableBitrate = show
                                    scope.launch { repository.saveShowUnavailableBitrate(show) }
                                },
                                onMarqueeTrackTitleChange = { marquee ->
                                    marqueeTrackTitle = marquee
                                    scope.launch { repository.saveMarqueeTrackTitle(marquee) }
                                },
                                onAutoExpandPlayerPanelOnPlaybackChange = { autoExpand ->
                                    autoExpandPlayerPanelOnPlayback = autoExpand
                                    scope.launch { repository.saveAutoExpandPlayerPanelOnPlayback(autoExpand) }
                                },
                                onCollapsePlayerPanelInSearchChange = { collapse ->
                                    collapsePlayerPanelInSearch = collapse
                                    scope.launch { repository.saveCollapsePlayerPanelInSearch(collapse) }
                                },
                                onAddRadioBrowserTagsChange = { add ->
                                    addRadioBrowserTags = add
                                    scope.launch { repository.saveAddRadioBrowserTags(add) }
                                },
                                onRemoveTrackingParametersChange = { remove ->
                                    removeTrackingParametersFromUrls = remove
                                    scope.launch { repository.saveRemoveTrackingParameters(remove) }
                                },
                                onShowAndroidAutoArtworkChange = { show ->
                                    showAndroidAutoArtwork = show
                                    scope.launch { repository.saveShowAndroidAutoArtwork(show) }
                                },
                                onPodcastAutoRefreshOnStartChange = { enabled ->
                                    scope.launch { podcastRepository.saveAutoRefreshOnStart(enabled) }
                                },
                                onPodcastAutoRefreshOnEnterListChange = { enabled ->
                                    scope.launch { podcastRepository.saveAutoRefreshOnEnterList(enabled) }
                                },
                                onPodcastRefreshIntervalMinutesChange = { minutes ->
                                    scope.launch { podcastRepository.saveRefreshIntervalMinutes(minutes) }
                                },
                                onClearLibrary = { clearLibrary() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        MainPage.About -> {
                            AboutPage(modifier = Modifier.fillMaxSize(), updates = updates)
                        }
                    }
                }
            }
        }

        pendingImportData?.let { importData ->
            ImportStationsDialog(
                stationCount = importData.stations.size,
                onImport = ::importStations,
                onDismiss = { pendingImportData = null },
            )
        }

        errorDialog?.let { message ->
            ErrorDialog(
                message = message,
                onDismiss = { errorDialog = null },
            )
        }

        if (addPodcastDialogVisible) {
            AddPodcastDialog(
                loading = podcastLoading,
                errorText = podcastError,
                onSubmit = ::subscribePodcast,
                onDismiss = {
                    if (!podcastLoading) {
                        addPodcastDialogVisible = false
                        podcastError = null
                    }
                },
            )
        }

        podcastSubscriptions.firstOrNull { it.feedUrl == editPodcastUrl }?.let { subscription ->
            EditPodcastDialog(
                subscription = subscription,
                loading = podcastLoading,
                errorText = podcastError,
                onSave = { title, feedUrl -> savePodcastEdit(subscription, title, feedUrl) },
                onDismiss = {
                    if (!podcastLoading) {
                        editPodcastUrl = null
                        podcastError = null
                    }
                },
            )
        }

        pendingPodcastRemoval?.let { subscription ->
            OmniConfirmDialog(
                title = resources.appString(R.string.podcast_remove_title),
                text = resources.appString(R.string.podcast_remove_text, subscription.title),
                confirmText = resources.appString(R.string.podcast_remove_confirm),
                destructive = true,
                onDismiss = { pendingPodcastRemoval = null },
                onConfirm = {
                    pendingPodcastRemoval = null
                    removePodcast(subscription)
                },
            )
        }

        editorState?.let { state ->
            StationEditorDialog(
                state = state,
                showDelete = state.stationIndex != null,
                confirmStationDeletion = confirmStationDeletion,
                onDismiss = { editorState = null },
                onSyncArtwork = state.stationIndex?.let { stationIndex ->
                    { syncStationArtwork(stationIndex) }
                },
                onDelete = {
                    val index = state.stationIndex ?: return@StationEditorDialog
                    val nextStations = stations.toMutableList().also { it.removeAt(index) }
                    stations = nextStations
                    if (playbackState.selectedIndex == index) {
                        PlaybackService.stop(context)
                    }
                    scope.launch { repository.saveStations(nextStations) }
                    editorState = null
                },
                onSave = { title, streamUrl, tags ->
                    val trimmedStreamUrl = streamUrl.trim()
                    val cleanedStreamUrl = if (state.stationIndex == null && removeTrackingParametersFromUrls) {
                        removeTrackingParameters(trimmedStreamUrl)
                    } else {
                        trimmedStreamUrl
                    }
                    val savedStreamUrl = normalizeStreamUrl(cleanedStreamUrl)
                    val updatedStation = Station(
                        id = state.stationIndex?.let { stations[it].id } ?: UUID.randomUUID().toString(),
                        title = title.trim().ifBlank { savedStreamUrl.take(STATION_TITLE_MAX_LENGTH) },
                        streamUrl = savedStreamUrl,
                        tags = parseStationTags(tags),
                        imageUrl = state.stationIndex?.let { stations[it].imageUrl },
                        isFavorite = state.stationIndex?.let { stations[it].isFavorite } ?: false,
                        dateAdded = state.stationIndex?.let { stations[it].dateAdded } ?: Instant.now().toString(),
                    )
                    val nextStations = stations.toMutableList().also { list ->
                        val index = state.stationIndex
                        if (index == null) {
                            list.add(updatedStation)
                        } else {
                            list[index] = updatedStation
                        }
                    }
                    stations = nextStations
                    scope.launch { repository.saveStations(nextStations) }
                    if (state.stationIndex == null) {
                        syncNewStationArtwork(updatedStation)
                    }
                    editorState = null
                },
            )
        }
    }
}
