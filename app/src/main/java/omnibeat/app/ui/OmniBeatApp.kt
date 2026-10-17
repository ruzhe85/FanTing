package omnibeat.app.ui

import omnibeat.app.R
import omnibeat.app.data.parseStationTags
import omnibeat.app.data.StationExportData
import omnibeat.app.model.STATION_STREAM_URL_MAX_LENGTH
import omnibeat.app.model.STATION_TITLE_MAX_LENGTH
import omnibeat.app.radio.ArtworkLookup
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import omnibeat.app.data.DEFAULT_STOP_SERVICE_AFTER_PAUSE_MINUTES
import omnibeat.app.data.PodcastDownloadManager
import omnibeat.app.data.PodcastRepository
import omnibeat.app.data.buildDownloadExportName
import omnibeat.app.data.SimpleStationTextCodec
import omnibeat.app.data.StationExportCodec
import omnibeat.app.data.StationImportMode
import omnibeat.app.data.StationRepository
import omnibeat.app.data.StationArtworkCache
import omnibeat.app.data.TranslationLanguage
import omnibeat.app.data.appString
import omnibeat.app.data.removeTrackingParameters
import omnibeat.app.data.normalizeStreamUrl
import omnibeat.app.model.FavoritesSection
import omnibeat.app.model.MainPage
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES
import omnibeat.app.model.PODCAST_DEFAULT_SEARCH_REGION
import omnibeat.app.model.PODCAST_SEARCH_FETCH_LIMIT
import omnibeat.app.model.AccentPalette
import omnibeat.app.model.PODCAST_SEARCH_PAGE_SIZE
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
import omnibeat.app.model.podcastFeedKey
import omnibeat.app.podcast.ITunesPodcast
import omnibeat.app.podcast.ITunesPodcastClient
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

/** 批量同步封面时每个电台之间的间隔：查找链最多会打 8 个请求，连着打太密 */
private const val ARTWORK_SYNC_DELAY_MS = 200L

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
    // 仅 Wi-Fi 下载开关（默认开）：移动数据下手动下载前先弹窗询问
    val podcastWifiOnlyDownload by podcastRepository.wifiOnlyDownload
        .collectAsState(initial = true)
    // 在线搜索使用的 iTunes 商店地区（设置-播客可选，默认美国区）
    val podcastSearchRegion by podcastRepository.searchRegion
        .collectAsState(initial = PODCAST_DEFAULT_SEARCH_REGION)
    // 期数列表排序：false = 最新在上（默认）
    val podcastEpisodesOldestFirst by podcastRepository.episodesOldestFirst
        .collectAsState(initial = false)
    // 订阅列表紧凑模式：无封面、两行，一屏显示更多
    val podcastCompactList by podcastRepository.compactList
        .collectAsState(initial = false)
    // 收藏 tab 上次停留的分段（电台 / 节目）
    val favoritesSection by repository.favoritesSection
        .collectAsState(initial = FavoritesSection.Stations)
    // 在线搜索结果的「已订阅」判定：按归一化后的 feedUrl 比较，避免协议/结尾斜杠差异漏判
    val subscribedPodcastKeys = remember(podcastSubscriptions) {
        podcastSubscriptions.mapTo(mutableSetOf()) { podcastFeedKey(it.feedUrl) }
    }
    // 收藏的节目：跨订阅聚合，按发布时间倒序（与订阅内排序一致）。
    // 纯读派生——数据源就是各订阅里 favorite = true 的期，不额外落库。
    val favoriteEpisodes = remember(podcastSubscriptions) {
        podcastSubscriptions
            .flatMap { subscription ->
                subscription.episodes.filter { it.favorite }.map { subscription to it }
            }
            .sortedByDescending { it.second.publishedAtMs }
    }
    // 播客下载状态（guid → 下载中/失败）；已下载与否看 episode.localFileName
    val podcastDownloadStates by PodcastDownloadManager.states.collectAsState()
    // 已下载的节目：跨订阅聚合，按发布时间倒序。纯读派生，下载管理页与顶栏入口都用它。
    val downloadedEpisodes = remember(podcastSubscriptions) {
        podcastSubscriptions
            .flatMap { subscription ->
                subscription.episodes.filter { it.localFileName != null }.map { subscription to it }
            }
            .sortedByDescending { it.second.publishedAtMs }
    }
    val translationEnabled by repository.translationEnabled.collectAsState(initial = false)
    var translationLoaded by remember(repository) { mutableStateOf(false) }
    LaunchedEffect(repository) {
        TranslationLanguage.load(repository)
        translationLoaded = true
    }
    val themeMode by repository.themeMode.collectAsState(initial = ThemeMode.System)
    val accentPalette by repository.accentPalette.collectAsState(initial = AccentPalette.Default)
    val appLanguage = context.applicationContext.currentAppLanguage()
    val useDarkTheme = shouldUseDarkTheme(themeMode)

    OmniBeatTheme(themeMode = themeMode, accentPalette = accentPalette) {
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
        // 默认空收藏页不显示：要收藏 tab 常驻，可在设置里打开
        var showEmptyFavoritesTab by remember { mutableStateOf(false) }
        var confirmStationDeletion by remember { mutableStateOf(true) }
        var showBitrateInControlPanel by remember { mutableStateOf(true) }
        var showUnavailableBitrate by remember { mutableStateOf(false) }
        var showPlayerMetadata by remember { mutableStateOf(true) }
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
        // 列表 / 期数页的刷新状态；与弹窗里的提交状态分开，否则自动刷新期间
        // 打开「添加播客」会继承 loading，把取消、订阅、返回键一起锁死
        var podcastLoading by remember { mutableStateOf(false) }
        // 添加 / 编辑弹窗自己的提交状态：只反映用户在弹窗里发起的请求
        var podcastSubmitLoading by remember { mutableStateOf(false) }
        var podcastSubmitJob by remember { mutableStateOf<Job?>(null) }
        // 请求代号：取消后重开弹窗会发新请求，旧请求的收尾不能清掉新请求的状态
        var podcastSubmitRequest by remember { mutableIntStateOf(0) }
        // 一键刷新全部的进度：总数 > 0 表示正在刷新，已完成数驱动 FAB 的「n/N」
        var podcastRefreshAllTotal by remember { mutableIntStateOf(0) }
        var podcastRefreshAllDone by remember { mutableIntStateOf(0) }
        var podcastError by remember { mutableStateOf<String?>(null) }
        var pendingPodcastRemoval by remember { mutableStateOf<PodcastSubscription?>(null) }
        // 仅 Wi-Fi 下载：移动数据下点下载时先记下这一集，等弹窗确认（单次放行，不持久化）
        var pendingMobileDataDownload by remember {
            mutableStateOf<Pair<PodcastSubscription, PodcastEpisode>?>(null)
        }
        // 下载管理页是否打开（播客 Tab 内的二级页，顶栏图标进入）；与 openPodcastUrl 互斥显示
        var downloadsOpen by remember { mutableStateOf(false) }
        // 期数页的本地搜索：只过滤当前订阅的期数，不联网
        var podcastEpisodeSearchOpen by remember { mutableStateOf(false) }
        var podcastEpisodeSearchQuery by remember { mutableStateOf("") }
        // 在线搜索（iTunes）：搜索、详情、订阅三套状态各自独立，互不复用刷新/弹窗的 loading
        var podcastSearchOpen by remember { mutableStateOf(false) }
        var podcastSearchQuery by remember { mutableStateOf("") }
        var podcastSearchResults by remember { mutableStateOf(emptyList<ITunesPodcast>()) }
        // 已展开到第几条：iTunes 不支持分页，结果一次取满，这里只控制列表显示多少
        var podcastSearchVisibleCount by remember { mutableIntStateOf(PODCAST_SEARCH_PAGE_SIZE) }
        var podcastSearchLoading by remember { mutableStateOf(false) }
        var podcastSearchError by remember { mutableStateOf<String?>(null) }
        var podcastSearchSelected by remember { mutableStateOf<ITunesPodcast?>(null) }
        var podcastSearchDescriptionLoading by remember { mutableStateOf(false) }
        var podcastSearchSubmitting by remember { mutableStateOf(false) }
        var podcastSearchSubmitError by remember { mutableStateOf<String?>(null) }
        var podcastSearchJob by remember { mutableStateOf<Job?>(null) }
        var podcastDetailJob by remember { mutableStateOf<Job?>(null) }
        var podcastSearchSubmitJob by remember { mutableStateOf<Job?>(null) }
        var podcastSearchRequest by remember { mutableIntStateOf(0) }
        var podcastDetailRequest by remember { mutableIntStateOf(0) }
        var podcastSearchSubmitRequest by remember { mutableIntStateOf(0) }
        // 播客页始终可见；收藏页在「一条收藏都没有且设置里关掉空收藏页」时隐藏
        val visibleTabPages = MainPage.allTabPages.filter { page ->
            page != MainPage.Favorites ||
                showEmptyFavoritesTab ||
                stations.any { it.isFavorite } ||
                favoriteEpisodes.isNotEmpty()
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
            onShowPlayerMetadataChange = { showPlayerMetadata = it },
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

        // ---- 播客下载导出：SAF 选位置 → 内部文件字节拷出去（不需要 FileProvider / 权限）----
        // 注意本地函数先声明后使用：拷贝函数 → launcher → 触发函数。
        var pendingDownloadExport by remember {
            mutableStateOf<Pair<PodcastSubscription, PodcastEpisode>?>(null)
        }

        /** 把已下载的单集音频拷到用户选的位置；文件不在 / 写失败返回 false */
        suspend fun copyDownloadedEpisode(
            episode: PodcastEpisode,
            destination: android.net.Uri,
        ): Boolean {
            val fileName = episode.localFileName ?: return false
            val file = PodcastDownloadManager.localFile(context, fileName)
            if (!file.isFile) return false
            return withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination)?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: error("cannot open output stream")
                }.isSuccess
            }
        }

        // 建议名「订阅名 - 单集名.ext」：内部 hash 名用户看不懂，导出必须可读；
        // SAF 里用户还能自己改。mime 用通配 audio/*，实际后缀由建议名带。
        val downloadExportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("audio/*"),
        ) { uri ->
            val target = pendingDownloadExport ?: return@rememberLauncherForActivityResult
            pendingDownloadExport = null
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val exported = copyDownloadedEpisode(target.second, uri)
                Toast.makeText(
                    context,
                    resources.appString(
                        if (exported) R.string.toast_download_exported
                        else R.string.toast_download_export_failed,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        /** 导出已下载的单集：存在性检查 → 拼可读建议名 → SAF */
        fun exportDownloadedEpisode(
            subscription: PodcastSubscription,
            episode: PodcastEpisode,
        ) {
            val fileName = episode.localFileName ?: return
            if (!PodcastDownloadManager.localFileExists(context, fileName)) {
                // 文件没了（清过数据之类）：提示失败，别把一个空壳导出去
                Toast.makeText(
                    context,
                    resources.appString(R.string.toast_download_export_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            pendingDownloadExport = subscription to episode
            downloadExportLauncher.launch(buildDownloadExportName(subscription, episode))
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
                // 记住 uuid：之后「查找封面」可以直接按 uuid 精确回查，不用赌 byurl 的精确匹配
                radioBrowserUuid = radioStation.stationUuid.takeIf { it.isNotBlank() },
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
            // 只拦自己的重复提交：后台刷新（podcastLoading）不该挡住添加，
            // 否则自动刷新期间打开弹窗会出现「取消和订阅都没反应」
            if (podcastSubmitLoading) return
            if (!hasInternetOrToast()) return
            val request = podcastSubmitRequest + 1
            podcastSubmitRequest = request
            podcastSubmitLoading = true
            podcastError = null
            podcastSubmitJob = scope.launch {
                try {
                    val parsed = RssParser.load(feedUrl)
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    podcastError = resources.appString(
                        R.string.podcast_error_load,
                        error.message ?: error.javaClass.simpleName,
                    )
                } finally {
                    // 用户取消后旧请求的 finally 可能很晚才回来，别清掉新请求的状态
                    if (podcastSubmitRequest == request) {
                        podcastSubmitLoading = false
                        podcastSubmitJob = null
                    }
                }
            }
        }

        /** 关闭添加 / 编辑弹窗：连同在途请求一起取消，UI 不必等网络超时 */
        fun dismissPodcastSubmit() {
            podcastSubmitRequest++
            podcastSubmitJob?.cancel()
            podcastSubmitJob = null
            podcastSubmitLoading = false
            podcastError = null
        }

        /** 打开在线搜索：保留上一次的关键词与结果，只清掉错误与详情 */
        fun openPodcastSearch() {
            podcastSearchOpen = true
            podcastSearchSelected = null
            podcastSearchError = null
            podcastSearchSubmitError = null
            podcastSearchDescriptionLoading = false
        }

        /** 只退出搜索界面，不动搜索请求本身（订阅成功跳转时用） */
        fun leavePodcastSearch() {
            podcastDetailRequest++
            podcastDetailJob?.cancel()
            podcastDetailJob = null
            podcastSearchDescriptionLoading = false
            podcastSearchSubmitError = null
            podcastSearchSelected = null
            podcastSearchOpen = false
        }

        /** 只退出详情页，回到搜索结果列表 */
        fun closePodcastSearchDetail() {
            podcastDetailRequest++
            podcastDetailJob?.cancel()
            podcastDetailJob = null
            podcastSearchDescriptionLoading = false
            podcastSearchSubmitError = null
            podcastSearchSelected = null
        }

        /** 关闭搜索：连同搜索 / 订阅的在途请求一起取消，状态全部复位 */
        fun closePodcastSearch() {
            podcastSearchRequest++
            podcastSearchJob?.cancel()
            podcastSearchJob = null
            podcastSearchLoading = false
            podcastSearchError = null
            podcastSearchSubmitRequest++
            podcastSearchSubmitJob?.cancel()
            podcastSearchSubmitJob = null
            podcastSearchSubmitting = false
            leavePodcastSearch()
        }

        /** 在线搜索：手动触发，请求代号防止旧响应覆盖新结果 */
        fun searchPodcasts() {
            val term = podcastSearchQuery.trim()
            if (term.isEmpty() || podcastSearchLoading) return
            if (!hasInternetOrToast()) return
            val request = podcastSearchRequest + 1
            podcastSearchRequest = request
            podcastSearchLoading = true
            podcastSearchError = null
            podcastSearchJob = scope.launch {
                try {
                    val results = ITunesPodcastClient.search(
                        term = term,
                        countryCode = podcastSearchRegion,
                        limit = PODCAST_SEARCH_FETCH_LIMIT,
                    )
                    if (podcastSearchRequest == request) {
                        podcastSearchResults = results
                        podcastSearchVisibleCount = PODCAST_SEARCH_PAGE_SIZE
                        // 结果已出来，清空关键词：否则得先删掉才能输入下一个搜索词
                        podcastSearchQuery = ""
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (podcastSearchRequest == request) {
                        podcastSearchResults = emptyList()
                        podcastSearchVisibleCount = PODCAST_SEARCH_PAGE_SIZE
                        podcastSearchError = error.message ?: error.javaClass.simpleName
                    }
                } finally {
                    if (podcastSearchRequest == request) {
                        podcastSearchLoading = false
                        podcastSearchJob = null
                    }
                }
            }
        }

        /** 「加载更多」只做本地展开：候选结果首屏已经取满，不再发请求 */
        fun loadMorePodcasts() {
            if (podcastSearchVisibleCount >= podcastSearchResults.size) return
            podcastSearchVisibleCount = (podcastSearchVisibleCount + PODCAST_SEARCH_PAGE_SIZE)
                .coerceAtMost(podcastSearchResults.size)
        }

        /** 进详情：先用搜索结果里的信息立即显示，缺简介时从订阅源头部补 */
        fun openPodcastSearchDetail(podcast: ITunesPodcast) {
            podcastSearchSelected = podcast
            podcastSearchSubmitError = null
            val request = podcastDetailRequest + 1
            podcastDetailRequest = request
            podcastDetailJob?.cancel()
            podcastDetailJob = null
            if (!podcast.description.isNullOrBlank()) {
                podcastSearchDescriptionLoading = false
                return
            }
            if (!NetworkStatus.isOnline(context)) {
                podcastSearchDescriptionLoading = false
                return
            }
            podcastSearchDescriptionLoading = true
            podcastDetailJob = scope.launch {
                try {
                    // iTunes 的 search / lookup 都不返回简介，改从订阅源头部取（只读前 64KB）
                    val summary = RssParser.loadSummary(podcast.feedUrl)
                    if (podcastDetailRequest == request && !summary.isNullOrBlank()) {
                        podcastSearchSelected = podcast.copy(description = summary)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 简介只是补充信息，拿不到就不显示，不打扰用户
                } finally {
                    if (podcastDetailRequest == request) {
                        podcastSearchDescriptionLoading = false
                        podcastDetailJob = null
                    }
                }
            }
        }

        /** 详情页订阅：与手动添加同一条链路（先解析 RSS，成功才落库） */
        fun subscribeSearchedPodcast(podcast: ITunesPodcast) {
            if (podcastSearchSubmitting) return
            val feedUrl = podcast.feedUrl
            if (podcastFeedKey(feedUrl) in subscribedPodcastKeys) return
            if (!hasInternetOrToast()) return
            val request = podcastSearchSubmitRequest + 1
            podcastSearchSubmitRequest = request
            podcastSearchSubmitting = true
            podcastSearchSubmitError = null
            podcastSearchSubmitJob = scope.launch {
                try {
                    val parsed = RssParser.load(feedUrl)
                    podcastRepository.upsertSubscription(
                        PodcastSubscription(
                            feedUrl = feedUrl,
                            title = parsed.title,
                            imageUrl = parsed.imageUrl,
                            lastFetchedAtMs = System.currentTimeMillis(),
                            episodes = parsed.episodes,
                        ),
                    )
                    if (podcastSearchSubmitRequest == request) {
                        // 订阅成功：关掉搜索，直接进入这个订阅的期数页
                        leavePodcastSearch()
                        openPodcastUrl = feedUrl
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (podcastSearchSubmitRequest == request) {
                        podcastSearchSubmitError = error.message ?: error.javaClass.simpleName
                    }
                } finally {
                    if (podcastSearchSubmitRequest == request) {
                        podcastSearchSubmitting = false
                        podcastSearchSubmitJob = null
                    }
                }
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
            if (!NetworkStatus.isOnline(context)) {
                // 手动刷新（期数页下拉 / force）要有反馈：转圈不能悄悄消失；
                // 自动刷新（打开订阅时的节流刷新）保持静默——按设计浏览列表不需要网络
                if (force) {
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_no_internet),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }
            podcastLoading = true
            scope.launch {
                try {
                    // 刷新前的期数 guid 快照：刷新后 diff 就是「新增期数」，用于手动刷新的结果反馈
                    val previousGuids = subscription.episodes.mapTo(mutableSetOf()) { it.guid }
                    val parsed = RssParser.load(subscription.feedUrl)
                    val newEpisodes = parsed.episodes.count { it.guid !in previousGuids }
                    podcastRepository.upsertSubscription(
                        subscription.copy(
                            title = parsed.title,
                            imageUrl = if (subscription.imageUrlManual) {
                                subscription.imageUrl
                            } else {
                                parsed.imageUrl ?: subscription.imageUrl
                            },
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // 手动刷新失败必须说一声，否则用户分不清「没有新期」还是「拉取失败」；
                    // 自动刷新（非 force）保持静默，由批量逻辑统一汇总提示
                    if (force) {
                        showPodcastRefreshError(error)
                    }
                } finally {
                    // 写库也可能抛异常；不复位的话刷新按钮会永久失效
                    podcastLoading = false
                }
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
            // 断网预检：自动刷新（启动 / 进列表）直接返回不打扰；手动（FAB）给一声「没有网络」。
            // 没有这道检查，离线时每个订阅都会串行连接超时（15s×N），最后还弹
            // 「N 个订阅刷新失败」——按设计，刷新才需要网络，浏览不该被它骚扰。
            if (!NetworkStatus.isOnline(context)) {
                if (!silent) {
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_no_internet),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }
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
                try {
                    pending.forEach { subscription ->
                        // 刷新前的期数 guid 快照：刷新后 diff 就是「新增期数」
                        val previousGuids = subscription.episodes.mapTo(mutableSetOf()) { it.guid }
                        try {
                            val parsed = RssParser.load(subscription.feedUrl)
                            succeeded++
                            newEpisodes += parsed.episodes.count { it.guid !in previousGuids }
                            podcastRepository.upsertSubscription(
                                subscription.copy(
                                    title = parsed.title,
                                    imageUrl = if (subscription.imageUrlManual) {
                                        subscription.imageUrl
                                    } else {
                                        parsed.imageUrl ?: subscription.imageUrl
                                    },
                                    lastFetchedAtMs = System.currentTimeMillis(),
                                    episodes = parsed.episodes,
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed++
                        }
                        podcastRefreshAllDone++
                    }
                } finally {
                    // 被取消或写库抛异常时也要复位，否则刷新入口会永久失效
                    podcastLoading = false
                    podcastRefreshAllTotal = 0
                    podcastRefreshAllDone = 0
                }
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
            // 换订阅时清掉上一份搜索词，避免拿旧词过滤新列表
            podcastEpisodeSearchOpen = false
            podcastEpisodeSearchQuery = ""
            refreshPodcast(subscription)
        }

        fun removePodcast(subscription: PodcastSubscription) {
            if (openPodcastUrl == subscription.feedUrl) openPodcastUrl = null
            scope.launch { podcastRepository.removeSubscription(subscription.feedUrl) }
        }

        /**
         * 手动下载单集的统一入口（期数行 / 收藏页 / 失败重试都走这里）。
         *
         * 「仅 Wi-Fi 下载」开着且当前不是 Wi-Fi 时，不直接开下，而是记下这一集等弹窗确认——
         * 确认后只放行**这一次**下载（pending 一清就恢复询问），不把开关临时关掉。
         * 拿不到网络类型时保守按移动数据处理，不偷流量。
         */
        fun startEpisodeDownload(subscription: PodcastSubscription, episode: PodcastEpisode) {
            if (podcastWifiOnlyDownload && !NetworkStatus.isWifi(context)) {
                pendingMobileDataDownload = subscription to episode
            } else {
                PodcastDownloadManager.download(context, subscription, episode)
            }
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
            // 点的就是当前这一集：不能重建播放器。重建会拿磁盘里的旧进度（往往还是 0）起播，
            // 表现为「正听着再点一下就从头上重播」。正在播就忽略，暂停中则继续播。
            val current = playbackState
            if (current.seekable && current.selectedStation?.id == episode.guid) {
                val loaded = current.isPlaying || current.resolving || current.buffering ||
                    current.durationMs > 0L || current.positionMs > 0L
                if (loaded) {
                    if (!current.isPlaying) PlaybackService.playOrPause(context)
                    return
                }
            }
            // 已下载且文件还在：走本地 file://，离线也能播（HLS 的分片同样是本地路径）；
            // 没有本地文件才检查网络、回退在线地址
            val localUri = episode.localFileName?.let {
                PodcastDownloadManager.localFileUri(context, it)
            }
            if (localUri == null && !hasInternetOrToast()) return
            // 切集前先把上一集的进度落盘（此刻 playbackState 还指向上一集）
            saveEpisodeProgress()
            expandPlayerPanelForUserPlaybackRequest()
            PlaybackService.playEpisode(
                context = context,
                episode = Station(
                    id = episode.guid,
                    title = episode.title,
                    streamUrl = localUri ?: episode.audioUrl,
                    tags = emptyList(),
                    imageUrl = subscription.imageUrl,
                    isFavorite = false,
                    dateAdded = "",
                    // 系统播放栏的「歌手」位挂订阅名：通知/锁屏显示「单集标题 / 播客名」
                    artist = subscription.title,
                ),
                // 听完的期从头播；否则从上次位置继续
                startPositionMs = if (episode.completed) 0L else episode.positionMs,
                speed = podcastPlaybackSpeed,
            )
        }

        /**
         * 切换订阅内相邻的一期。
         *
         * [direction] 是**显示顺序**：+1 = 列表向下（下一集），-1 = 列表向上（上一集）。
         * 存储顺序固定为最新在前，所以用户切到「最旧在上」时要反过来取，
         * 否则播完自动下一集会与屏幕上看到的顺序相反。
         *
         * 当前在播的不是订阅内单集时返回 false，调用方回落到电台切台逻辑；
         * 若是单集但已到列表尽头，返回 true（不回落，避免按"下一集"却切了电台）。
         */
        fun playAdjacentEpisode(direction: Int): Boolean {
            val (subscription, episode) = currentEpisode() ?: return false
            val index = subscription.episodes.indexOfFirst { it.guid == episode.guid }
            if (index == -1) return false
            val step = if (podcastEpisodesOldestFirst) -direction else direction
            val target = subscription.episodes.getOrNull(index + step) ?: return true
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
        fun savePodcastEdit(
            subscription: PodcastSubscription,
            title: String,
            feedUrl: String,
            imageUrl: String,
        ) {
            if (podcastSubmitLoading) return
            podcastError = null
            val trimmedImageUrl = imageUrl.trim().takeIf { it.isNotBlank() }
            // 打开编辑弹窗时字段里已经有原封面，只有真的改动过才当作“手动指定”
            val artworkUnchanged = trimmedImageUrl == subscription.imageUrl?.trim()?.takeIf { it.isNotBlank() }
            val manualArtwork = if (artworkUnchanged) {
                subscription.imageUrlManual
            } else {
                trimmedImageUrl != null
            }
            // 手动填的封面先下一遍：失败只提示，不阻止保存（可能只是当前网络不好）
            val verifyArtwork: suspend (String) -> Unit = { url ->
                val loaded = runCatching { StationArtworkCache.refresh(context, url) }.getOrDefault(false)
                if (!loaded) {
                    Toast.makeText(
                        context,
                        resources.appString(R.string.toast_artwork_unavailable),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            if (feedUrl == subscription.feedUrl) {
                scope.launch {
                    podcastRepository.replaceSubscription(
                        previousFeedUrl = subscription.feedUrl,
                        subscription = subscription.copy(
                            title = title,
                            imageUrl = trimmedImageUrl,
                            imageUrlManual = manualArtwork,
                        ),
                    )
                    trimmedImageUrl?.let { verifyArtwork(it) }
                }
                editPodcastUrl = null
                return
            }
            if (!hasInternetOrToast()) return
            val request = podcastSubmitRequest + 1
            podcastSubmitRequest = request
            podcastSubmitLoading = true
            podcastSubmitJob = scope.launch {
                var savedArtworkUrl: String? = null
                try {
                    val parsed = RssParser.load(feedUrl)
                    podcastRepository.replaceSubscription(
                        previousFeedUrl = subscription.feedUrl,
                        subscription = PodcastSubscription(
                            feedUrl = feedUrl,
                            // 没改过标题就跟随新源的标题，改过则保留用户填的
                            title = if (title == subscription.title) parsed.title else title,
                            imageUrl = trimmedImageUrl ?: parsed.imageUrl,
                            imageUrlManual = manualArtwork,
                            lastFetchedAtMs = System.currentTimeMillis(),
                            episodes = parsed.episodes,
                        ),
                    )
                    if (openPodcastUrl == subscription.feedUrl) openPodcastUrl = feedUrl
                    editPodcastUrl = null
                    savedArtworkUrl = trimmedImageUrl
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    podcastError = resources.appString(
                        R.string.podcast_error_load,
                        error.message ?: error.javaClass.simpleName,
                    )
                } finally {
                    if (podcastSubmitRequest == request) {
                        podcastSubmitLoading = false
                        podcastSubmitJob = null
                    }
                }
                // 封面校验可能要十几秒，放在复位之后，别让弹窗一直停在「加载中」
                savedArtworkUrl?.let { verifyArtwork(it) }
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

        /**
         * 找封面的统一入口：先查 Radio Browser（uuid → 流地址等价写法 → 名称），
         * 再用 [StationArtworkCache.refresh] 真正下回来验证一次——只有能显示出来的地址才算找到。
         */
        suspend fun findStationArtwork(title: String, streamUrl: String, stationUuid: String?): String? {
            val lookup = radioBrowserClient.resolveArtwork(title, streamUrl, stationUuid)
            val imageUrl = (lookup as? ArtworkLookup.Found)?.imageUrl ?: return null
            return imageUrl.takeIf { StationArtworkCache.refresh(context, imageUrl) }
        }

        /** 手动填的地址先下一遍：失败只提示，不阻止保存 */
        suspend fun verifyArtwork(imageUrl: String) {
            val loaded = runCatching { StationArtworkCache.refresh(context, imageUrl) }.getOrDefault(false)
            if (!loaded) {
                Toast.makeText(
                    context,
                    resources.appString(R.string.toast_artwork_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        /** 自动抓到的封面只在没被用户手动指定时才写回 */
        suspend fun applyStationArtwork(stationId: String, imageUrl: String) {
            val current = stations.firstOrNull { it.id == stationId } ?: return
            if (current.imageUrlManual || current.imageUrl == imageUrl) return
            val nextStations = stations.map { savedStation ->
                if (savedStation.id == stationId) savedStation.copy(imageUrl = imageUrl) else savedStation
            }
            stations = nextStations
            repository.saveStations(nextStations)
        }

        suspend fun refreshStationArtwork(station: Station): Boolean {
            if (station.imageUrlManual) return false
            val imageUrl = findStationArtwork(
                station.title,
                station.streamUrl,
                station.radioBrowserUuid,
            ) ?: return false
            applyStationArtwork(station.id, imageUrl)
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
                        if (station.imageUrlManual) return@forEach
                        runCatching {
                            if (refreshStationArtwork(station)) updatedCount++
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                        }
                        delay(ARTWORK_SYNC_DELAY_MS)
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
            if (!showStationArtwork || station.imageUrlManual) return
            if (!station.imageUrl.isNullOrBlank()) return
            scope.launch {
                runCatching { findStationArtwork(station.title, station.streamUrl, station.radioBrowserUuid) }
                    .onSuccess { imageUrl ->
                        if (imageUrl != null) applyStationArtwork(station.id, imageUrl)
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
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
                // 已加载过内容（暂停中的单集/电台）就允许恢复：断网不拦——
                // 本地已下载的单集此时照样能播（服务端对 file:// 也不过网络检查）。
                // 只有「什么都没有」时才检查网络，避免从零起播一个在线流。
                val hasLoadedContent = playbackState.selectedStation != null
                if (!hasActivePlaybackRequest && !hasLoadedContent && !hasInternetOrToast()) return
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
                        onAddStation = {
                            editorState = StationEditorState(
                                stationIndex = null,
                                title = "",
                                streamUrl = "",
                                tags = "",
                                dateAdded = null,
                                imageUrl = null,
                            )
                        },
                        onSearchOnline = {
                            selectedPage = MainPage.SearchOnline
                        },
                        onAddPodcast = {
                            podcastError = null
                            addPodcastDialogVisible = true
                        },
                        onSearchPodcastOnline = {
                            podcastError = null
                            openPodcastSearch()
                        },
                        podcastSearchActive = podcastSearchOpen,
                        // 期数页才显示排序；与电台一致，排序按钮排在「+」左边
                        podcastEpisodeSortVisible = !podcastSearchOpen && openPodcastUrl != null,
                        podcastEpisodesOldestFirst = podcastEpisodesOldestFirst,
                        onPodcastEpisodesOldestFirstChange = { oldest ->
                            scope.launch { podcastRepository.saveEpisodesOldestFirst(oldest) }
                        },
                        onSearchPodcastEpisodes = { podcastEpisodeSearchOpen = true },
                        // 下载管理页入口：播客订阅列表页（非期数页 / 非搜索 / 未在下载页）
                        // 且有已下载节目时，才在「+」左边渲染下载图标；一个都没有就不占位
                        onOpenDownloads = if (
                            selectedPage == MainPage.Podcasts &&
                            openPodcastUrl == null &&
                            !podcastSearchOpen &&
                            !downloadsOpen &&
                            downloadedEpisodes.isNotEmpty()
                        ) {
                            { downloadsOpen = true }
                        } else {
                            null
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
                            showPlayerMetadata = showPlayerMetadata,
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
                                favoritesSection = favoritesSection,
                                onFavoritesSectionChange = { section ->
                                    scope.launch { repository.saveFavoritesSection(section) }
                                },
                                favoriteEpisodeCount = favoriteEpisodes.size,
                                episodeFavoriteContent = {
                                    FavoriteEpisodesList(
                                        favorites = favoriteEpisodes,
                                        playingEpisodeId = playbackState.selectedStation?.id,
                                        onEpisodeClick = ::playPodcastEpisode,
                                        onToggleFavorite = { subscription, episode ->
                                            scope.launch {
                                                podcastRepository.setEpisodeFavorite(
                                                    feedUrl = subscription.feedUrl,
                                                    guid = episode.guid,
                                                    favorite = !episode.favorite,
                                                )
                                            }
                                        },
                                        downloadStates = podcastDownloadStates,
                                        onDownload = { subscription, episode ->
                                            startEpisodeDownload(subscription, episode)
                                        },
                                        onCancelDownload = { episode ->
                                            PodcastDownloadManager.cancel(episode.guid)
                                        },
                                        onDeleteDownload = { subscription, episode ->
                                            PodcastDownloadManager.deleteDownload(
                                                context,
                                                subscription,
                                                episode,
                                            )
                                        },
                                        onExportDownload = { subscription, episode ->
                                            exportDownloadedEpisode(subscription, episode)
                                        },
                                        downloadedEpisodes = downloadedEpisodes,
                                        downloadsOpen = downloadsOpen,
                                        onOpenDownloads = { downloadsOpen = true },
                                        onCloseDownloads = { downloadsOpen = false },
                                        onDeleteAllDownloads = {
                                            PodcastDownloadManager.deleteAllDownloads(
                                                context,
                                                podcastSubscriptions,
                                            )
                                            Toast.makeText(
                                                context,
                                                resources.appString(
                                                    R.string.toast_downloads_cleared,
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                },
                                podcastContent = { contentModifier ->
                                    val selectedSearchPodcast = podcastSearchSelected
                                    when {
                                        podcastSearchOpen && selectedSearchPodcast != null -> PodcastSearchDetailPage(
                                            podcast = selectedSearchPodcast,
                                            descriptionLoading = podcastSearchDescriptionLoading,
                                            subscribed = podcastFeedKey(selectedSearchPodcast.feedUrl) in subscribedPodcastKeys,
                                            submitting = podcastSearchSubmitting,
                                            errorText = podcastSearchSubmitError,
                                            onSubscribe = { subscribeSearchedPodcast(selectedSearchPodcast) },
                                            onBack = ::closePodcastSearchDetail,
                                            modifier = contentModifier,
                                        )

                                        podcastSearchOpen -> PodcastSearchPage(
                                            query = podcastSearchQuery,
                                            results = podcastSearchResults.take(podcastSearchVisibleCount),
                                            loading = podcastSearchLoading,
                                            errorText = podcastSearchError,
                                            subscribedKeys = subscribedPodcastKeys,
                                            hasMore = podcastSearchVisibleCount < podcastSearchResults.size,
                                            onQueryChange = { podcastSearchQuery = it },
                                            onSearch = ::searchPodcasts,
                                            onLoadMore = ::loadMorePodcasts,
                                            onOpenDetail = ::openPodcastSearchDetail,
                                            onBack = ::closePodcastSearch,
                                            modifier = contentModifier,
                                        )

                                        else ->                                     PodcastTabContent(
                                        subscriptions = podcastSubscriptions,
                                        openSubscriptionUrl = openPodcastUrl,
                                        playingEpisodeId = playbackState.selectedStation?.id,
                                        loading = podcastLoading,
                                        episodesOldestFirst = podcastEpisodesOldestFirst,
                                        episodeSearchOpen = podcastEpisodeSearchOpen,
                                        episodeSearchQuery = podcastEpisodeSearchQuery,
                                        onEpisodeSearchOpenChange = { podcastEpisodeSearchOpen = it },
                                        onEpisodeSearchQueryChange = { podcastEpisodeSearchQuery = it },
                                        onOpenSubscription = ::openPodcast,
                                        onCloseSubscription = {
                                            openPodcastUrl = null
                                            podcastEpisodeSearchOpen = false
                                            podcastEpisodeSearchQuery = ""
                                        },
                                        onDeleteSubscription = { pendingPodcastRemoval = it },
                                        onToggleSubscriptionPinned = { subscription ->
                                            scope.launch {
                                                podcastRepository.setSubscriptionPinned(
                                                    feedUrl = subscription.feedUrl,
                                                    pinned = !subscription.pinned,
                                                )
                                            }
                                        },
                                        marqueeTitles = marqueeTrackTitle,
                                        compactList = podcastCompactList,
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
                                        onToggleFavorite = { subscription, episode ->
                                            scope.launch {
                                                podcastRepository.setEpisodeFavorite(
                                                    feedUrl = subscription.feedUrl,
                                                    guid = episode.guid,
                                                    favorite = !episode.favorite,
                                                )
                                            }
                                        },
                                        downloadStates = podcastDownloadStates,
                                        onDownload = { subscription, episode ->
                                            startEpisodeDownload(subscription, episode)
                                        },
                                        onCancelDownload = { episode ->
                                            PodcastDownloadManager.cancel(episode.guid)
                                        },
                                        onDeleteDownload = { subscription, episode ->
                                            PodcastDownloadManager.deleteDownload(
                                                context,
                                                subscription,
                                                episode,
                                            )
                                        },
                                        onExportDownload = { subscription, episode ->
                                            exportDownloadedEpisode(subscription, episode)
                                        },
                                        downloadedEpisodes = downloadedEpisodes,
                                        downloadsOpen = downloadsOpen,
                                        onOpenDownloads = { downloadsOpen = true },
                                        onCloseDownloads = { downloadsOpen = false },
                                        onDeleteAllDownloads = {
                                            PodcastDownloadManager.deleteAllDownloads(
                                                context,
                                                podcastSubscriptions,
                                            )
                                            Toast.makeText(
                                                context,
                                                resources.appString(
                                                    R.string.toast_downloads_cleared,
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                        onEditSubscription = {
                                            podcastError = null
                                            editPodcastUrl = it.feedUrl
                                        },
                                        onPlayLatest = ::playLatestEpisode,
                                        onPlayEpisode = ::playPodcastEpisode,
                                        modifier = contentModifier,
                                    )
                                    }
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
                                accentPalette = accentPalette,
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
                                showPlayerMetadata = showPlayerMetadata,
                                marqueeTrackTitle = marqueeTrackTitle,
                                autoExpandPlayerPanelOnPlayback = autoExpandPlayerPanelOnPlayback,
                                collapsePlayerPanelInSearch = collapsePlayerPanelInSearch,
                                addRadioBrowserTags = addRadioBrowserTags,
                                removeTrackingParameters = removeTrackingParametersFromUrls,
                                showAndroidAutoArtwork = showAndroidAutoArtwork,
                                podcastAutoRefreshOnStart = podcastAutoRefreshOnStart,
                                podcastAutoRefreshOnEnterList = podcastAutoRefreshOnEnterList,
                                podcastRefreshIntervalMinutes = podcastRefreshIntervalMinutes,
                                podcastSearchRegion = podcastSearchRegion,
                                podcastCompactList = podcastCompactList,
                                podcastWifiOnlyDownload = podcastWifiOnlyDownload,
                                onThemeModeChange = { nextThemeMode ->
                                    scope.launch { repository.saveThemeMode(nextThemeMode) }
                                },
                                onAccentPaletteChange = { nextAccentPalette ->
                                    scope.launch { repository.saveAccentPalette(nextAccentPalette) }
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
                                onShowPlayerMetadataChange = { show ->
                                    showPlayerMetadata = show
                                    scope.launch { repository.saveShowPlayerMetadata(show) }
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
                                onPodcastSearchRegionChange = { region ->
                                    scope.launch { podcastRepository.saveSearchRegion(region) }
                                },
                                onPodcastCompactListChange = { compact ->
                                    scope.launch { podcastRepository.saveCompactList(compact) }
                                },
                                onPodcastWifiOnlyDownloadChange = { enabled ->
                                    scope.launch { podcastRepository.saveWifiOnlyDownload(enabled) }
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
                // 只反映本弹窗发起的请求：后台自动刷新不再把弹窗锁在「加载中」
                loading = podcastSubmitLoading,
                errorText = podcastError,
                onSubmit = ::subscribePodcast,
                onDismiss = {
                    dismissPodcastSubmit()
                    addPodcastDialogVisible = false
                },
            )
        }

        podcastSubscriptions.firstOrNull { it.feedUrl == editPodcastUrl }?.let { subscription ->
            EditPodcastDialog(
                subscription = subscription,
                loading = podcastSubmitLoading,
                errorText = podcastError,
                onSave = { title, feedUrl, imageUrl ->
                    savePodcastEdit(subscription, title, feedUrl, imageUrl)
                },
                onDismiss = {
                    dismissPodcastSubmit()
                    editPodcastUrl = null
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

        // 仅 Wi-Fi 下载：移动数据下点下载时的轻量确认。确认只放行这一次，
        // pending 一清就恢复询问（单次覆盖，不把开关临时关掉）。
        pendingMobileDataDownload?.let { (subscription, episode) ->
            OmniConfirmDialog(
                title = resources.appString(R.string.dialog_mobile_data_download_title),
                text = resources.appString(
                    R.string.dialog_mobile_data_download_text,
                    episode.title,
                ),
                confirmText = resources.appString(R.string.dialog_mobile_data_download_confirm),
                onDismiss = { pendingMobileDataDownload = null },
                onConfirm = {
                    pendingMobileDataDownload = null
                    PodcastDownloadManager.download(context, subscription, episode)
                },
            )
        }

        editorState?.let { state ->
            val editingStation = state.stationIndex?.let { stations.getOrNull(it) }
            StationEditorDialog(
                state = state,
                showDelete = state.stationIndex != null,
                confirmStationDeletion = confirmStationDeletion,
                onDismiss = { editorState = null },
                onLookupArtwork = { title, streamUrl ->
                    radioBrowserClient.resolveArtwork(
                        title = title,
                        streamUrl = streamUrl,
                        stationUuid = editingStation?.radioBrowserUuid,
                    )
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
                onSave = { title, streamUrl, tags, imageUrl ->
                    val trimmedStreamUrl = streamUrl.trim()
                    val cleanedStreamUrl = if (state.stationIndex == null && removeTrackingParametersFromUrls) {
                        removeTrackingParameters(trimmedStreamUrl)
                    } else {
                        trimmedStreamUrl
                    }
                    val savedStreamUrl = normalizeStreamUrl(cleanedStreamUrl)
                    val trimmedImageUrl = imageUrl.trim().takeIf { it.isNotBlank() }
                    val previousImageUrl = editingStation?.imageUrl
                    // 只是改了标题/标签时不能把原有的自动封面“升级”成手动，否则它会拒绝后续自动更新
                    val artworkUnchanged = trimmedImageUrl == previousImageUrl
                    val manualArtwork = if (artworkUnchanged) {
                        editingStation?.imageUrlManual ?: false
                    } else {
                        trimmedImageUrl != null
                    }
                    val updatedStation = Station(
                        id = editingStation?.id ?: UUID.randomUUID().toString(),
                        title = title.trim().ifBlank { savedStreamUrl.take(STATION_TITLE_MAX_LENGTH) },
                        streamUrl = savedStreamUrl,
                        tags = parseStationTags(tags),
                        imageUrl = trimmedImageUrl,
                        isFavorite = editingStation?.isFavorite ?: false,
                        dateAdded = editingStation?.dateAdded ?: Instant.now().toString(),
                        imageUrlManual = manualArtwork,
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
                    scope.launch {
                        repository.saveStations(nextStations)
                        when {
                            trimmedImageUrl != null && trimmedImageUrl != previousImageUrl ->
                                verifyArtwork(trimmedImageUrl)

                            trimmedImageUrl == null -> syncNewStationArtwork(updatedStation)
                        }
                    }
                    editorState = null
                },
            )
        }
    }
}
