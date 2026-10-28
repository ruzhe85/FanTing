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

import android.content.Context
import android.content.res.Resources
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import omnibeat.app.data.appString
import omnibeat.app.data.removeTrackingParameters
import omnibeat.app.data.normalizeStreamUrl
import omnibeat.app.model.FavoritesSection
import omnibeat.app.model.MainPage
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.PODCAST_DEFAULT_REFRESH_INTERVAL_MINUTES
import omnibeat.app.model.PODCAST_CHART_LIMIT
import omnibeat.app.model.PODCAST_DEFAULT_SEARCH_REGION
import omnibeat.app.model.PodcastListLayout
import omnibeat.app.model.PODCAST_SEARCH_FETCH_LIMIT
import omnibeat.app.model.podcastChartRegionFor
import omnibeat.app.model.AccentPalette
import omnibeat.app.model.PODCAST_SEARCH_PAGE_SIZE
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastSortMode
import omnibeat.app.model.PodcastSortState
import omnibeat.app.model.PodcastSubscription
import omnibeat.app.model.pendingRefreshFailureReason
import omnibeat.app.model.Station
import omnibeat.app.model.StationEditorState
import omnibeat.app.model.StationReorderDraft
import omnibeat.app.model.StationSortMode
import omnibeat.app.model.StationSortState
import omnibeat.app.model.ThemeMode
import omnibeat.app.model.customSortedPodcasts
import omnibeat.app.model.customSortedStations
import omnibeat.app.model.sortedPodcasts
import omnibeat.app.model.sortedStations
import omnibeat.app.network.NetworkStatus
import omnibeat.app.playback.PlaybackService
import omnibeat.app.model.podcastFeedKey
import omnibeat.app.podcast.ITunesPodcast
import omnibeat.app.podcast.ITunesPodcastClient
import omnibeat.app.podcast.PodcastChart
import omnibeat.app.podcast.PodcastChartClient
import omnibeat.app.podcast.PodcastChartEntry
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

/** 播客 Tab 内的浏览态：在线搜索 / 热门发现；null 表示停留在订阅列表 */
private enum class PodcastBrowseMode { Search, Chart }

/** 榜单补全 feedUrl：iTunes lookup 一次最多带多少个 id */
private const val CHART_FEED_LOOKUP_BATCH = 25

/** 补全批次之间的间隔，避免连着打 Apple 接口 */
private const val CHART_FEED_LOOKUP_GAP_MS = 150L

/** 榜单条目已补全 feedUrl 时直接转成详情页模型，省一次 lookup */
private fun PodcastChartEntry.asITunesPodcast(): ITunesPodcast = ITunesPodcast(
    collectionId = collectionId,
    title = title,
    author = author,
    feedUrl = requireNotNull(feedUrl),
    artworkUrl = artworkUrl,
    genre = genres.firstOrNull(),
)

@Composable
fun OmniBeatApp() {
    val context = LocalContext.current
    // 用 applicationContext 做 key：切语言时 LocalContext 会换成包装后的上下文，
    // 若以 context 为 key，Repository 会被连带重建、丢失内存状态。
    val repository = remember(context.applicationContext) { StationRepository(context.applicationContext) }
    // 播客订阅存在独立的 DataStore 里，与电台库互不干扰
    val podcastRepository = remember(context.applicationContext) { PodcastRepository(context.applicationContext) }
    val podcastSubscriptions by podcastRepository.subscriptions.collectAsState(initial = emptyList())
    // 订阅列表 / 刷新 / 统计只看未隐藏的；下载、收藏、播放仍用包含 hidden 的全量列表
    val podcastVisibleSubscriptions by podcastRepository.visibleSubscriptions
        .collectAsState(initial = emptyList())
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
    // 热门发现上次手动选择的地区；null = 没选过，按界面语言决定
    val savedChartRegion by podcastRepository.chartRegion.collectAsState(initial = null)
    // 期数列表排序：false = 最新在上（默认）
    val podcastEpisodesOldestFirst by podcastRepository.episodesOldestFirst
        .collectAsState(initial = false)
    // 订阅列表显示方式：标准 / 紧凑 / 纯封面
    val podcastListLayout by podcastRepository.listLayout
        .collectAsState(initial = PodcastListLayout.Standard)
    // 收藏 tab 上次停留的分段（电台 / 节目）
    val favoritesSection by repository.favoritesSection
        .collectAsState(initial = FavoritesSection.Stations)
    // 在线搜索结果的「已订阅」判定：按归一化后的 feedUrl 比较，避免协议/结尾斜杠差异漏判
    val subscribedPodcastKeys = remember(podcastVisibleSubscriptions) {
        podcastVisibleSubscriptions.mapTo(mutableSetOf()) { podcastFeedKey(it.feedUrl) }
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
    // 进行中 / 失败待重试的下载（第一个文件落盘前也能在下载管理页看到）
    val activeDownloads by PodcastDownloadManager.activeDownloads.collectAsState()
    // 下载管理页列表：进行中/失败的排前面（刚点的下载一眼能找到），已下载的按发布时间跟在后面
    val downloadsPageItems = remember(downloadedEpisodes, activeDownloads) {
        val active = activeDownloads.values.filterNot { (_, episode) ->
            downloadedEpisodes.any { it.second.guid == episode.guid }
        }
        active + downloadedEpisodes
    }
    val themeMode by repository.themeMode.collectAsState(initial = ThemeMode.System)
    val accentPalette by repository.accentPalette.collectAsState(initial = AccentPalette.Default)
    // 应用内语言：状态与本地化 Context 由 AppLanguageProvider 在根部持有，切换只重组不重建
    val appLanguage = LocalAppLanguage.current
    val useDarkTheme = shouldUseDarkTheme(themeMode)

    OmniBeatTheme(themeMode = themeMode, accentPalette = accentPalette) {
        val resources = LocalResources.current
        // App 内语言覆盖后的生效语言：名称排序（拼音 / 笔画 / 各语言常规）都按它来
        val uiLocale = resources.configuration.locales[0]
        val statusBarColor = RadioBackground
        val navigationBarColor = RadioBackground
        SideEffect {
            val activity = appLanguage.hostActivity ?: return@SideEffect
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

        if (onboardingCompleted == null) {
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
        var autoPlayNextEpisode by remember { mutableStateOf(true) }
        var stopServiceAfterPauseMinutes by remember { mutableIntStateOf(DEFAULT_STOP_SERVICE_AFTER_PAUSE_MINUTES) }
        var keepMediaCard by remember { mutableStateOf(repository.keepMediaCard.value) }
        var showStationArtwork by remember { mutableStateOf(true) }
        var syncingStationArtwork by remember { mutableStateOf(false) }
        // 默认空收藏页不显示：要收藏 tab 常驻，可在设置里打开
        var showEmptyFavoritesTab by remember { mutableStateOf(false) }
        var confirmStationDeletion by remember { mutableStateOf(true) }
        var showBitrateInControlPanel by remember { mutableStateOf(true) }
        var showPlayerMetadata by remember { mutableStateOf(true) }
        var marqueeTrackTitle by remember { mutableStateOf(true) }
        var autoExpandPlayerPanelOnPlayback by remember { mutableStateOf(false) }
        var collapsePlayerPanelInSearch by remember { mutableStateOf(true) }
        var addRadioBrowserTags by remember { mutableStateOf(true) }
        var removeTrackingParametersFromUrls by remember { mutableStateOf(true) }
        var showAndroidAutoArtwork by remember { mutableStateOf(true) }
        var resumeAfterInterruption by remember { mutableStateOf(true) }
        var playerPanelCollapsed by remember { mutableStateOf(false) }
        var editorState by remember { mutableStateOf<StationEditorState?>(null) }
        var selectedPage by remember { mutableStateOf(MainPage.Stations) }
        var lastMainPage by remember { mutableStateOf(MainPage.Stations) }
        var sortState by remember { mutableStateOf(StationSortState()) }
        var customStationOrder by remember { mutableStateOf(emptyList<String>()) }
        var customFavoriteOrder by remember { mutableStateOf(emptyList<String>()) }
        var reorderDraft by remember { mutableStateOf<StationReorderDraft?>(null) }
        // 播客订阅列表：排序偏好 + 拖动排序草稿（与电台共用顶栏的“取消/确认”形态）
        var podcastSortState by remember { mutableStateOf(PodcastSortState()) }
        var podcastCustomOrder by remember { mutableStateOf(emptyList<String>()) }
        var podcastReorderDraft by remember { mutableStateOf<List<PodcastSubscription>?>(null) }
        LaunchedEffect(podcastRepository) {
            podcastRepository.sortState.collect { podcastSortState = it }
        }
        LaunchedEffect(podcastRepository) {
            podcastRepository.customOrder.collect { podcastCustomOrder = it }
        }
        // 一键刷新期间冻结列表顺序：每刷完一个订阅的最新一期发布时间可能变，
        // 若不冻结，列表会跟着「最近更新」排序连续跳动，看起来像抽风
        var podcastOrderSnapshot by remember { mutableStateOf<List<String>?>(null) }
        // 拖动中的草稿优先，否则按当前排序偏好展示
        val podcastList = remember(
            podcastVisibleSubscriptions,
            podcastSortState,
            podcastCustomOrder,
            podcastReorderDraft,
            podcastOrderSnapshot,
            // 切语言后名称排序规则会变，必须重新排
            uiLocale,
        ) {
            // 用局部 val 接住：直接引用委托属性时智能转换不生效，返回值会变成可空
            val draft = podcastReorderDraft
            val snapshot = podcastOrderSnapshot
            when {
                draft != null -> draft
                // 刷新中：按快照顺序摆放，新订阅接在末尾
                snapshot != null -> {
                    val byUrl = podcastVisibleSubscriptions.associateBy { it.feedUrl }
                    val ordered = snapshot.mapNotNull(byUrl::get)
                    val known = ordered.mapTo(mutableSetOf()) { it.feedUrl }
                    ordered + podcastVisibleSubscriptions.filter { it.feedUrl !in known }
                }
                else -> sortedPodcasts(
                    source = podcastVisibleSubscriptions,
                    sortState = podcastSortState,
                    customOrder = podcastCustomOrder,
                    locale = uiLocale,
                )
            }
        }
        var scrollToSelectedRequest by remember { mutableIntStateOf(0) }
        var scrollToStationId by remember { mutableStateOf<String?>(null) }
        var lastPlayedStationId by remember { mutableStateOf<String?>(null) }
        var recentlyPlayedStationIds by remember { mutableStateOf(emptyList<String>()) }
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
        // 自动刷新失败的明细（播客名 + 原因）：提示条与明细弹窗共用同一份，只在内存里
        var podcastRefreshFailures by remember {
            mutableStateOf(emptyList<PodcastRefreshFailure>())
        }
        var podcastRefreshFailuresVisible by remember { mutableStateOf(false) }
        val podcastSnackbarHostState = remember { SnackbarHostState() }
        // 仅 Wi-Fi 下载：移动数据下点下载时先记下这一集，等弹窗确认（单次放行，不持久化）
        var pendingMobileDataDownload by remember {
            mutableStateOf<Pair<PodcastSubscription, PodcastEpisode>?>(null)
        }
        // 多选批量下载的同一套 Wi-Fi 确认
        var pendingBatchDownload by remember {
            mutableStateOf<Pair<PodcastSubscription, List<PodcastEpisode>>?>(null)
        }
        // 下载管理页是否打开（播客 Tab 内的二级页，顶栏图标进入）；与 openPodcastUrl 互斥显示
        var downloadsOpen by remember { mutableStateOf(false) }
        // 期数页的本地搜索：只过滤当前订阅的期数，不联网
        var podcastEpisodeSearchOpen by remember { mutableStateOf(false) }
        var podcastEpisodeSearchQuery by remember { mutableStateOf("") }
        // 顶栏 ⋮「多选」的触发计数：期数页监听其变化进入多选（打开订阅时归零，
        // 否则新开的期数页首帧就会读到旧计数、意外进入多选）
        var podcastMultiSelectRequest by remember { mutableIntStateOf(0) }
        // 在线搜索 / 热门发现：两者都是播客 Tab 内的二级页，用同一个浏览态互斥
        var podcastBrowseMode by remember { mutableStateOf<PodcastBrowseMode?>(null) }
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
        // 热门发现（Apple 榜单）：地区按界面语言现算，列表按地区缓存 6 小时
        val podcastChartClient = remember { PodcastChartClient(context.applicationContext) }
        var podcastChartRegion by remember { mutableStateOf("us") }
        var podcastChartEntries by remember { mutableStateOf(emptyList<PodcastChartEntry>()) }
        var podcastChartLoading by remember { mutableStateOf(false) }
        var podcastChartError by remember { mutableStateOf<String?>(null) }
        // 正在 lookup 的榜单项：行内转圈 + 阻止连点
        var podcastChartResolvingId by remember { mutableStateOf<Long?>(null) }
        var podcastChartJob by remember { mutableStateOf<Job?>(null) }
        var podcastChartLookupJob by remember { mutableStateOf<Job?>(null) }
        var podcastChartResolveJob by remember { mutableStateOf<Job?>(null) }
        var podcastChartRequest by remember { mutableIntStateOf(0) }
        // 列表滚动位置提升到这里：从期数页返回发现页时保持原位
        val podcastChartListState = rememberLazyListState()
        // 从发现页订阅后，关闭期数页时回到发现页而不是订阅列表
        var podcastReturnToChartFor by remember { mutableStateOf<String?>(null) }
        // 会话内二级缓存：切页面/重进时先出内容，避免每次都读盘
        val podcastChartMemoryCache = remember { mutableMapOf<String, PodcastChart>() }
        // 榜单条目 id → 已补全的订阅信息，重复点击不必再 lookup
        val podcastChartFeedCache = remember { mutableMapOf<Long, ITunesPodcast>() }
        // 发现页「已订阅」：榜单条目补全 feedUrl 后与订阅集合比对
        val subscribedChartIds = remember(podcastChartEntries, subscribedPodcastKeys) {
            podcastChartEntries.asSequence()
                .filter { entry ->
                    entry.feedUrl?.let { podcastFeedKey(it) in subscribedPodcastKeys } == true
                }
                .mapTo(mutableSetOf()) { it.collectionId }
        }
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
            onAutoPlayNextEpisodeChange = { autoPlayNextEpisode = it },
            onStopServiceAfterPauseMinutesChange = { stopServiceAfterPauseMinutes = it },
            onKeepMediaCardChange = { keepMediaCard = it },
            onShowStationArtworkChange = { showStationArtwork = it },
            onShowEmptyFavoritesTabChange = { showEmptyFavoritesTab = it },
            onConfirmStationDeletionChange = { confirmStationDeletion = it },
            onShowBitrateInControlPanelChange = { showBitrateInControlPanel = it },
            onShowPlayerMetadataChange = { showPlayerMetadata = it },
            onMarqueeTrackTitleChange = { marqueeTrackTitle = it },
            onAutoExpandPlayerPanelOnPlaybackChange = { autoExpandPlayerPanelOnPlayback = it },
            onCollapsePlayerPanelInSearchChange = { collapsePlayerPanelInSearch = it },
            onAddRadioBrowserTagsChange = { addRadioBrowserTags = it },
            onRemoveTrackingParametersChange = { removeTrackingParametersFromUrls = it },
            onShowAndroidAutoArtworkChange = { showAndroidAutoArtwork = it },
            onResumeAfterInterruptionChange = { resumeAfterInterruption = it },
            onPlayerPanelCollapsedChange = { playerPanelCollapsed = it },
            onLastMainPageChange = { lastMainPage = it },
            onSelectedPageRestore = { selectedPage = it },
            onStationSortStateChange = { sortState = it },
            onCustomStationOrderChange = { customStationOrder = it },
            onCustomFavoriteOrderChange = { customFavoriteOrder = it },
            onLastPlayedStationIdChange = { lastPlayedStationId = it },
            onRecentlyPlayedStationIdsChange = { recentlyPlayedStationIds = it },
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
            val nextPodcasts = if (importData.isFullBackup) {
                when (mode) {
                    StationImportMode.Replace -> importData.podcasts
                    StationImportMode.Merge -> StationExportCodec.mergePodcasts(
                        current = podcastSubscriptions,
                        imported = importData.podcasts,
                    )
                }
            } else {
                podcastSubscriptions
            }
            val nextPlayback = if (importData.isFullBackup) {
                when (mode) {
                    StationImportMode.Replace ->
                        importData.lastPlayedStationId to importData.recentlyPlayedStationIds
                    StationImportMode.Merge -> StationExportCodec.mergePlaybackHistory(
                        localLastPlayedStationId = lastPlayedStationId,
                        localRecentlyPlayedStationIds = recentlyPlayedStationIds,
                        importedLastPlayedStationId = importData.lastPlayedStationId,
                        importedRecentlyPlayedStationIds = importData.recentlyPlayedStationIds,
                    )
                }
            } else {
                lastPlayedStationId to recentlyPlayedStationIds
            }
            pendingImportData = null
            stations = importResult.stations
            sortState = importResult.sortState
            customStationOrder = importResult.customStationOrder
            customFavoriteOrder = importResult.customFavoriteOrder
            lastPlayedStationId = nextPlayback.first
            recentlyPlayedStationIds = nextPlayback.second
            reorderDraft = null
            if (
                playbackState.selectedStation != null &&
                importResult.stations.none { it.id == playbackState.selectedStation?.id }
            ) {
                PlaybackService.stop(context)
            }
            scope.launch {
                repository.saveImportedLibrary(importResult)
                if (importData.isFullBackup) {
                    podcastRepository.restoreAll(nextPodcasts)
                    repository.restorePlaybackHistory(nextPlayback.first, nextPlayback.second)
                }
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
            // 抽屉开/关过程中（含关闭动画进行中）程序化切页不播横滑动画：
            // 否则「抽屉滑走 + 整页横向滑回」两个动画叠在一起，方向感很怪。
            // 顶栏点 tab 时抽屉已关闭，仍保留横滑手感。
            animate = drawerState.isClosed,
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
                    podcasts = podcastSubscriptions,
                    lastPlayedStationId = lastPlayedStationId,
                    recentlyPlayedStationIds = recentlyPlayedStationIds,
                ),
            )
            val exportDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))
            jsonExportLauncher.launch("fanting_backup_$exportDate.json")
        }

        fun exportSimpleText() {
            if (stations.isEmpty()) {
                pendingExportContent = resources
                    .openRawResource(R.raw.fanting_export_example)
                    .bufferedReader()
                    .use { it.readText() }
                textExportLauncher.launch("fanting_export_example.txt")
            } else {
                pendingExportContent = SimpleStationTextCodec.encode(stations)
                val exportDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))
                textExportLauncher.launch("fanting_stations_$exportDate.txt")
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
            podcastBrowseMode = PodcastBrowseMode.Search
            podcastSearchSelected = null
            podcastSearchError = null
            podcastSearchSubmitError = null
            podcastSearchDescriptionLoading = false
        }

        /** 退出二级页（搜索 / 热门发现 / 详情），不动在途请求本身（订阅成功跳转时用） */
        fun leavePodcastBrowse() {
            podcastDetailRequest++
            podcastDetailJob?.cancel()
            podcastDetailJob = null
            podcastSearchDescriptionLoading = false
            podcastSearchSubmitError = null
            podcastSearchSelected = null
            podcastBrowseMode = null
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
            leavePodcastBrowse()
        }

        /** 把补全的 feedUrl 合并进列表 / 内存缓存 / 磁盘缓存，后续打开不再重复 lookup */
        fun mergeChartFeedUrls(country: String, feedUrls: Map<Long, String>) {
            if (feedUrls.isEmpty()) return
            fun merge(entries: List<PodcastChartEntry>): List<PodcastChartEntry> = entries.map { entry ->
                val feedUrl = feedUrls[entry.collectionId]
                if (feedUrl != null && entry.feedUrl != feedUrl) entry.copy(feedUrl = feedUrl) else entry
            }
            podcastChartMemoryCache[country]?.let { chart ->
                val updated = chart.copy(entries = merge(chart.entries))
                podcastChartMemoryCache[country] = updated
                scope.launch { podcastChartClient.saveChart(updated) }
            }
            if (podcastChartRegion == country) {
                podcastChartEntries = merge(podcastChartEntries)
            }
        }

        /**
         * 榜单接口没有 feedUrl：有订阅时后台分批补全，用于显示「已订阅」与免 lookup 进详情。
         * 只补当前地区缺失的条目，结果写回缓存；没有订阅时直接跳过，不发任何请求。
         */
        fun resolveChartFeedUrls(country: String, entries: List<PodcastChartEntry>) {
            if (subscribedPodcastKeys.isEmpty()) return
            val unresolved = entries.asSequence()
                .filter { it.feedUrl == null }
                .map { it.collectionId }
                .distinct()
                .toList()
            if (unresolved.isEmpty()) return
            podcastChartResolveJob?.cancel()
            podcastChartResolveJob = scope.launch {
                val resolved = mutableMapOf<Long, String>()
                for (ids in unresolved.chunked(CHART_FEED_LOOKUP_BATCH)) {
                    if (!NetworkStatus.isOnline(context)) break
                    try {
                        resolved.putAll(ITunesPodcastClient.lookupMany(ids, country))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 单批失败不影响其它批次；未补全的条目下次打开再试
                    }
                    delay(CHART_FEED_LOOKUP_GAP_MS)
                }
                if (resolved.isNotEmpty()) {
                    mergeChartFeedUrls(country, resolved)
                }
            }
        }

        /**
         * 读取并（必要时）刷新热门榜单。
         *
         * 先出缓存（内存 → 磁盘 → 无）：新鲜且非强制刷新时直接返回；过期则后台刷新，
         * 刷新失败时只要列表里有旧数据就不打扰用户。
         */
        fun loadPodcastChart(region: String, force: Boolean) {
            val request = podcastChartRequest + 1
            podcastChartRequest = request
            podcastChartJob?.cancel()
            podcastChartJob = scope.launch {
                try {
                    val cached = podcastChartMemoryCache[region]
                        ?: podcastChartClient.cached(region)?.also { podcastChartMemoryCache[region] = it }
                    if (cached != null) {
                        if (podcastChartEntries.isEmpty()) {
                            podcastChartEntries = cached.entries
                        }
                        podcastChartError = null
                        if (podcastChartRequest == request) {
                            resolveChartFeedUrls(region, cached.entries)
                        }
                    }
                    if (!force && cached?.isFresh() == true) {
                        podcastChartLoading = false
                        return@launch
                    }
                    if (!NetworkStatus.isOnline(context)) {
                        if (force) {
                            Toast.makeText(
                                context,
                                resources.appString(R.string.toast_no_internet),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else if (cached == null) {
                            podcastChartError = resources.appString(R.string.toast_no_internet)
                        }
                        podcastChartLoading = false
                        return@launch
                    }
                    podcastChartLoading = true
                    val chart = podcastChartClient.fetch(region, PODCAST_CHART_LIMIT)
                    // 刷新会带回一批没有 feedUrl 的新条目：按 id 从当前内存缓存搬回来，
                    // 否则每 6 小时刷新后都要重新批量 lookup 一次。
                    // 用内存缓存而不是 cached，是为了带上后台补全可能刚写进去的 feedUrl。
                    val previousFeedUrls = podcastChartMemoryCache[region]?.entries
                        ?.mapNotNull { entry -> entry.feedUrl?.let { entry.collectionId to it } }
                        ?.toMap()
                        .orEmpty()
                    val fresh = if (previousFeedUrls.isEmpty()) {
                        chart
                    } else {
                        chart.copy(
                            entries = chart.entries.map { entry ->
                                val feedUrl = previousFeedUrls[entry.collectionId]
                                if (entry.feedUrl == null && feedUrl != null) {
                                    entry.copy(feedUrl = feedUrl)
                                } else {
                                    entry
                                }
                            },
                        )
                    }
                    podcastChartMemoryCache[region] = fresh
                    if (podcastChartRequest == request) {
                        podcastChartClient.saveChart(fresh)
                        podcastChartEntries = fresh.entries
                        podcastChartError = null
                        resolveChartFeedUrls(region, fresh.entries)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (podcastChartRequest == request && podcastChartEntries.isEmpty()) {
                        podcastChartError = error.message ?: error.javaClass.simpleName
                    }
                } finally {
                    if (podcastChartRequest == request) {
                        podcastChartLoading = false
                        podcastChartJob = null
                    }
                }
            }
        }

        /** 打开热门发现：地区按当前界面语言现算，先出缓存再决定是否联网 */
        fun openPodcastChart() {
            podcastSearchSelected = null
            podcastSearchError = null
            podcastSearchSubmitError = null
            podcastSearchDescriptionLoading = false
            // 手动选过就固定用最后选择的地区；没选过才按界面语言现算
            val region = savedChartRegion ?: podcastChartRegionFor(resources.configuration.locales[0])
            podcastChartRegion = region
            // 切语言后内存里可能还是别的地区，先换成本地区的缓存（没有就清空）。
            // 清空后立刻进入加载态，避免读磁盘缓存前闪一下「榜单为空」。
            val cachedEntries = podcastChartMemoryCache[region]?.entries.orEmpty()
            podcastChartEntries = cachedEntries
            podcastChartLoading = cachedEntries.isEmpty()
            podcastChartError = null
            // 地区与列表都就位后再切页面，避免首帧闪一下默认地区的空列表
            podcastBrowseMode = PodcastBrowseMode.Chart
            loadPodcastChart(region, force = false)
        }

        /** 手动切换地区：优先用该地区的新鲜缓存，过期才后台刷新；列表回到顶部 */
        fun selectPodcastChartRegion(region: String) {
            if (region == podcastChartRegion) return
            podcastChartRegion = region
            podcastChartRequest++
            podcastChartJob?.cancel()
            podcastChartJob = null
            podcastChartResolveJob?.cancel()
            podcastChartResolveJob = null
            podcastChartLookupJob?.cancel()
            podcastChartLookupJob = null
            podcastChartResolvingId = null
            val cachedEntries = podcastChartMemoryCache[region]?.entries.orEmpty()
            podcastChartEntries = cachedEntries
            podcastChartLoading = cachedEntries.isEmpty()
            podcastChartError = null
            scope.launch {
                podcastRepository.saveChartRegion(region)
                podcastChartListState.scrollToItem(0)
            }
            loadPodcastChart(region, force = false)
        }

        /** 关闭热门发现：取消在途请求，保留缓存以便快速重进 */
        fun closePodcastChart() {
            podcastChartRequest++
            podcastChartJob?.cancel()
            podcastChartJob = null
            podcastChartResolveJob?.cancel()
            podcastChartResolveJob = null
            podcastChartLookupJob?.cancel()
            podcastChartLookupJob = null
            podcastChartResolvingId = null
            podcastReturnToChartFor = null
            podcastChartLoading = false
            podcastChartError = null
            podcastSearchSelected = null
            podcastBrowseMode = null
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

        /**
         * 订阅成功后立刻把 feedUrl 回填到榜单条目与缓存：
         * 返回发现页时该项显示「已订阅」，也免得下次再 lookup。
         */
        fun markChartEntrySubscribed(podcast: ITunesPodcast) {
            podcastChartFeedCache[podcast.collectionId] = podcast
            mergeChartFeedUrls(podcastChartRegion, mapOf(podcast.collectionId to podcast.feedUrl))
        }

        /**
         * 点击榜单项：榜单没有 feedUrl，先用 collectionId 做一次 lookup。
         * 成功后复用现有详情页（订阅确认），会话内结果会缓存，重复点击不再请求。
         */
        fun openPodcastFromChart(entry: PodcastChartEntry) {
            podcastChartFeedCache[entry.collectionId]?.let { podcast ->
                openPodcastSearchDetail(podcast)
                return
            }
            // 之前已经批量补全过 feedUrl：直接进详情，不再请求
            if (entry.feedUrl != null) {
                val podcast = entry.asITunesPodcast()
                podcastChartFeedCache[entry.collectionId] = podcast
                openPodcastSearchDetail(podcast)
                return
            }
            if (podcastChartResolvingId != null) return
            if (!hasInternetOrToast()) return
            podcastChartResolvingId = entry.collectionId
            podcastChartLookupJob?.cancel()
            podcastChartLookupJob = scope.launch {
                try {
                    val podcast = ITunesPodcastClient.lookup(entry.collectionId, podcastChartRegion)
                    if (podcast == null) {
                        Toast.makeText(
                            context,
                            resources.appString(R.string.podcast_chart_lookup_failed, entry.title),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        podcastChartFeedCache[entry.collectionId] = podcast
                        mergeChartFeedUrls(
                            podcastChartRegion,
                            mapOf(entry.collectionId to podcast.feedUrl),
                        )
                        openPodcastSearchDetail(podcast)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Toast.makeText(
                        context,
                        resources.appString(
                            R.string.podcast_chart_lookup_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                } finally {
                    if (podcastChartResolvingId == entry.collectionId) {
                        podcastChartResolvingId = null
                        podcastChartLookupJob = null
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
            val fromChart = podcastBrowseMode == PodcastBrowseMode.Chart
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
                        // 订阅成功：关掉搜索/发现页，直接进入这个订阅的期数页
                        leavePodcastBrowse()
                        openPodcastUrl = feedUrl
                        // 从发现页进来的：关掉期数页时回到发现页原位
                        podcastReturnToChartFor = feedUrl.takeIf { fromChart }
                        if (fromChart) markChartEntrySubscribed(podcast)
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

        /**
         * 距上次拉取（成功或失败）超过设置的刷新间隔才算「可以再拉」。
         *
         * 失败也要算基准：失败时 [PodcastSubscription.lastFetchedAtMs] 不前进，
         * 只看它的话失败的订阅永远「该刷新了」——启动与每次回到播客列表都会重拉它、
         * 再失败，用户看到的就是同一个源反复刷新失败。
         */
        fun refreshIntervalElapsed(subscription: PodcastSubscription): Boolean {
            val intervalMs = podcastRefreshIntervalMinutes.coerceAtLeast(1) * 60_000L
            val lastAttemptMs = maxOf(
                subscription.lastFetchedAtMs,
                subscription.lastRefreshFailedAtMs,
            )
            return System.currentTimeMillis() - lastAttemptMs >= intervalMs
        }

        /** 失败原因的可展示文案（RssParser 的异常 message 本身就是给用户看的） */
        fun refreshFailureReason(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

        /** 刷新失败后记下这次尝试的时间与原因；写库失败不影响刷新流程本身 */
        suspend fun markPodcastRefreshFailed(subscription: PodcastSubscription, error: Throwable) {
            try {
                podcastRepository.markRefreshFailed(subscription.feedUrl, refreshFailureReason(error))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 记不上就下次再试，不能让写库异常盖住刷新失败本身
            }
        }

        /** 单个订阅刷新失败的提示（RssParser 的异常 message 就是可展示文案） */
        fun showPodcastRefreshError(error: Throwable) {
            Toast.makeText(
                context,
                resources.appString(R.string.podcast_refresh_failed, refreshFailureReason(error)),
                Toast.LENGTH_LONG,
            ).show()
        }

        /**
         * 自动刷新失败的应用内提示。
         *
         * 提示条只报「第一个失败的播客 + 失败个数」，不往列表里加任何标记；
         * 「查看」打开明细（名字 + 原因，可逐个重试）。明细留在内存，仅本次会话有效。
         */
        fun showPodcastRefreshFailures(failures: List<PodcastRefreshFailure>) {
            if (failures.isEmpty()) return
            podcastRefreshFailures = failures
            val firstName = failures.first().subscription.title
            val message = if (failures.size == 1) {
                resources.appString(R.string.podcast_refresh_failure_one, firstName)
            } else {
                resources.appString(
                    R.string.podcast_refresh_failure_many,
                    firstName,
                    failures.size,
                )
            }
            scope.launch {
                val result = podcastSnackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = resources.appString(R.string.podcast_refresh_failure_view),
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    podcastRefreshFailuresVisible = true
                }
            }
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
                            // 拉成功了就把上次的失败原因清掉，期数页的失败窄条随之消失
                            lastRefreshFailureReason = null,
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
                    // 失败的时间与原因一律入库：既用于节流，也用于期数页顶部的失败窄条
                    markPodcastRefreshFailed(subscription, error)
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
            // 冻结当前顺序：刷新过程中每项的最新一期发布时间可能变，不冻结会连续重排
            podcastOrderSnapshot = podcastList.map { it.feedUrl }
            podcastRefreshAllTotal = pending.size
            podcastRefreshAllDone = 0
            scope.launch {
                var succeeded = 0
                var failed = 0
                var newEpisodes = 0
                // 本次失败的明细：自动刷新结束后用它弹提示条与明细（手动刷新另有汇总 toast）
                val failures = mutableListOf<PodcastRefreshFailure>()
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
                                    // 拉成功了就把上次的失败原因清掉，期数页的失败窄条随之消失
                                    lastRefreshFailureReason = null,
                                    episodes = parsed.episodes,
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failed++
                            failures += PodcastRefreshFailure(
                                subscription = subscription,
                                reason = refreshFailureReason(error),
                            )
                            // 失败也记尝试时间与原因：否则这批源永远「间隔已到」，
                            // 每次启动 / 回到播客列表都会重新拉、重新失败
                            markPodcastRefreshFailed(subscription, error)
                        }
                        podcastRefreshAllDone++
                    }
                } finally {
                    // 被取消或写库抛异常时也要复位，否则刷新入口会永久失效
                    podcastLoading = false
                    podcastRefreshAllTotal = 0
                    podcastRefreshAllDone = 0
                    // 顺序解冻：这时才按新的「最新一期发布时间」重新排一次
                    podcastOrderSnapshot = null
                }
                when {
                    // 自动刷新不打扰：只在有失败时给一条可展开的提示（不往列表里加标记）
                    silent && failures.isNotEmpty() -> showPodcastRefreshFailures(failures)
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
            // 多选触发计数归零：新期数页首帧不应读到旧计数而意外进多选
            podcastMultiSelectRequest = 0
            // 手动打开别的订阅时，不再回发现页
            podcastReturnToChartFor = null
            // 打开订阅时的节流刷新也归「进入自动刷新」管：关掉开关后不再自动拉取，
            // 需要新期数时用下拉刷新（force）或 FAB 一键刷新，这两条始终可用。
            // 这里用 state 而不是读 DataStore：点击发生时设置早已加载完毕。
            if (podcastAutoRefreshOnEnterList) refreshPodcast(subscription)
        }

        fun removePodcast(subscription: PodcastSubscription) {
            if (openPodcastUrl == subscription.feedUrl) openPodcastUrl = null
            if (podcastReturnToChartFor == subscription.feedUrl) podcastReturnToChartFor = null
            // 退订只隐藏并裁剪数据：正在下载的期要保留，下载完成时才能写回 localFileName
            val activeGuids = PodcastDownloadManager.activeDownloads.value.values
                .filter { (activeSubscription, _) -> activeSubscription.feedUrl == subscription.feedUrl }
                .mapTo(mutableSetOf()) { (_, episode) -> episode.guid }
            scope.launch {
                podcastRepository.hideSubscription(subscription.feedUrl, activeGuids)
            }
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

        fun enqueueEpisodeDownloads(
            subscription: PodcastSubscription,
            episodes: List<PodcastEpisode>,
        ) {
            episodes.forEach { episode ->
                PodcastDownloadManager.download(context, subscription, episode)
            }
            Toast.makeText(
                context,
                resources.appString(R.string.podcast_batch_download_queued, episodes.size),
                Toast.LENGTH_SHORT,
            ).show()
        }

        /** 多选批量下载：过滤已下载/下载中，移动数据下一次确认整批 */
        fun downloadEpisodes(
            subscription: PodcastSubscription,
            episodes: List<PodcastEpisode>,
        ) {
            val targets = episodes.filter { episode ->
                // 没有播放地址的期（解析失败）不下：否则只会收获一串失败态
                episode.audioUrl.isNotBlank() &&
                    episode.localFileName == null &&
                    !PodcastDownloadManager.isDownloading(episode.guid)
            }
            if (targets.isEmpty()) {
                Toast.makeText(
                    context,
                    resources.appString(R.string.podcast_batch_download_none),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            if (podcastWifiOnlyDownload && !NetworkStatus.isWifi(context)) {
                pendingBatchDownload = subscription to targets
            } else {
                enqueueEpisodeDownloads(subscription, targets)
            }
        }

        /** 多选批量标记已听 / 未听 */
        fun markEpisodesPlayed(
            subscription: PodcastSubscription,
            episodes: List<PodcastEpisode>,
            completed: Boolean,
        ) {
            if (episodes.isEmpty()) return
            scope.launch {
                podcastRepository.setEpisodesCompleted(
                    feedUrl = subscription.feedUrl,
                    guids = episodes.mapTo(mutableSetOf()) { it.guid },
                    completed = completed,
                )
                Toast.makeText(
                    context,
                    resources.appString(R.string.podcast_batch_marked, episodes.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // 启动时按设置自动刷新全部订阅（静默：只拉间隔已到的，成功不提示、失败才提示）。
        // 开关与订阅列表都从 DataStore 现读：collectAsState 的 initial 是 true，
        // 冷启动首帧还没读到用户关掉的设置，用 state 判断会先按默认值刷一次。
        // 注意：本地函数必须先声明后使用，这两个 effect 放在刷新函数之后。
        LaunchedEffect(Unit) {
            if (!podcastRepository.autoRefreshOnStart.first()) return@LaunchedEffect
            val saved = podcastRepository.subscriptions.first().filterNot { it.hidden }
            if (saved.isNotEmpty()) {
                refreshAllPodcasts(saved, silent = true)
            }
        }

        // 下载完成的应用内提示：合并器保证一次下多集时只出一条 toast，不刷屏。
        // 走应用内 toast 而不是系统通知——播客单集不大、下载一般很快，不值得要通知权限
        val downloadToaster = remember(context) {
            DownloadCompletionToaster(resources, context, scope)
        }
        LaunchedEffect(Unit) {
            PodcastDownloadManager.completions.collect { title ->
                downloadToaster.onCompleted(title)
            }
        }

        // 进入播客列表页（Tab 页且没打开某个订阅）时按设置自动刷新，同样静默 + 节流。
        // 打开期数页（openPodcastUrl != null）不触发，那边走打开订阅时的单个节流刷新。
        LaunchedEffect(selectedPage, openPodcastUrl) {
            if (selectedPage != MainPage.Podcasts || openPodcastUrl != null) return@LaunchedEffect
            // 同上：开关从 DataStore 现读，避免 initial 默认值把「已关闭」误判成开启
            if (!podcastRepository.autoRefreshOnEnterList.first()) return@LaunchedEffect
            val saved = podcastRepository.subscriptions.first().filterNot { it.hidden }
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

        /** 把订阅里的一期包成播放描述：面板预显示与实际起播共用同一套字段 */
        fun stationForEpisode(
            subscription: PodcastSubscription,
            episode: PodcastEpisode,
            streamUrl: String? = null,
        ): Station = Station(
            id = episode.guid,
            title = episode.title,
            streamUrl = streamUrl ?: episode.audioUrl,
            tags = emptyList(),
            imageUrl = subscription.imageUrl,
            isFavorite = false,
            dateAdded = "",
            // 系统播放栏的「歌手」位挂订阅名：通知/锁屏显示「单集标题 / 播客名」
            artist = subscription.title,
        )

        /**
         * 上次播放的是播客单集时，从订阅里把那一期找回来。
         *
         * lastPlayedStationId 对电台和单集都会写，两边 id 空间不同：
         * 先在电台列表里找，找不到才按单集 guid 搜订阅。
         */
        fun findResumeEpisode(): Pair<PodcastSubscription, PodcastEpisode>? {
            val id = lastPlayedStationId ?: return null
            if (stations.any { it.id == id }) return null
            return podcastSubscriptions.asSequence()
                .filter { !it.hidden }
                .flatMap { subscription -> subscription.episodes.asSequence().map { subscription to it } }
                .firstOrNull { (_, episode) -> episode.guid == id }
        }

        /**
         * 播放单集：交给 PlaybackService 的独立入口（点播语义、可拖进度、可倍速）。
         * 单集只作为「播放描述」传过去，不写进电台库。
         */
        fun playPodcastEpisode(subscription: PodcastSubscription, episode: PodcastEpisode) {
            if (episode.audioUrl.isBlank()) return
            // 点的就是当前这一集：不能重建播放器。重建会拿磁盘里的旧进度（往往还是 0）起播，
            // 表现为「正听着再点一下就从头上重播」。已加载就改为切换播放面板的收起/展开
            // （与面板头部的折叠按钮同一动作），正在播/暂停中都走这条，行为可预期。
            val current = playbackState
            if (current.seekable && current.selectedStation?.id == episode.guid) {
                val loaded = current.isPlaying || current.resolving || current.buffering ||
                    current.durationMs > 0L || current.positionMs > 0L
                if (loaded) {
                    val collapsed = !playerPanelCollapsed
                    playerPanelCollapsed = collapsed
                    scope.launch { repository.savePlayerPanelCollapsed(collapsed) }
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
                episode = stationForEpisode(subscription, episode, streamUrl = localUri),
                // 听完的期从头播；否则从上次位置继续
                startPositionMs = if (episode.completed) 0L else episode.positionMs,
                speed = podcastPlaybackSpeed,
            )
        }

        /** 从指定的一期切相邻一期；面板预显示「上次播放的单集」时也用这条 */
        fun playAdjacentEpisodeOf(
            subscription: PodcastSubscription,
            episode: PodcastEpisode,
            direction: Int,
        ): Boolean {
            val index = subscription.episodes.indexOfFirst { it.guid == episode.guid }
            if (index == -1) return false
            val step = if (podcastEpisodesOldestFirst) -direction else direction
            val target = subscription.episodes.getOrNull(index + step) ?: return true
            playPodcastEpisode(subscription, target)
            return true
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
            return playAdjacentEpisodeOf(subscription, episode, direction)
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
                locale = resources.configuration.locales[0],
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

        fun selectPodcastSortMode(nextMode: PodcastSortMode) {
            if (nextMode == PodcastSortMode.Custom) {
                val draft = if (podcastCustomOrder.isEmpty()) {
                    podcastList
                } else {
                    customSortedPodcasts(podcastVisibleSubscriptions, podcastCustomOrder)
                }
                val nextSortState = PodcastSortState(mode = PodcastSortMode.Custom, ascending = false)
                podcastSortState = nextSortState
                podcastReorderDraft = draft
                scope.launch { podcastRepository.saveSortState(nextSortState) }
                return
            }
            val nextSortState = if (podcastSortState.mode == nextMode) {
                podcastSortState.copy(ascending = !podcastSortState.ascending)
            } else {
                PodcastSortState(mode = nextMode, ascending = false)
            }
            podcastSortState = nextSortState
            scope.launch { podcastRepository.saveSortState(nextSortState) }
        }

        fun movePodcastReorderDraft(fromIndex: Int, toIndex: Int) {
            val currentDraft = podcastReorderDraft ?: return
            if (
                fromIndex !in currentDraft.indices ||
                toIndex !in currentDraft.indices ||
                fromIndex == toIndex
            ) {
                return
            }
            val nextList = currentDraft.toMutableList().also { list ->
                val moved = list.removeAt(fromIndex)
                list.add(toIndex, moved)
            }
            podcastReorderDraft = nextList
        }

        fun cancelPodcastReorder() {
            podcastReorderDraft = null
        }

        fun confirmPodcastReorder() {
            val draft = podcastReorderDraft ?: return
            val nextOrder = draft.map { it.feedUrl }
            val nextSortState = PodcastSortState(mode = PodcastSortMode.Custom, ascending = false)
            podcastCustomOrder = nextOrder
            podcastSortState = nextSortState
            podcastReorderDraft = null
            scope.launch {
                podcastRepository.saveCustomOrder(nextOrder)
                podcastRepository.saveSortState(nextSortState)
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
            // 面板上预显示的是「上次播放的单集」：点播放直接续播那一期
            if (!hasActivePlaybackRequest && playbackState.selectedStation == null) {
                val resume = findResumeEpisode()
                if (resume != null) {
                    playPodcastEpisode(resume.first, resume.second)
                    return
                }
            }
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
                // 在线搜索页点播放应播当前结果的第一条，其余页面接着上次的电台
                val rememberedStation = if (selectedPage != MainPage.SearchOnline) {
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

        BackHandler(
            enabled = drawerState.isOpen ||
                reorderDraft != null ||
                podcastReorderDraft != null ||
                selectedPage !in visibleTabPages,
        ) {
            when {
                drawerState.isOpen -> scope.launch { drawerState.close() }
                reorderDraft != null -> reorderDraft = null
                podcastReorderDraft != null -> podcastReorderDraft = null
                else -> selectedPage = lastMainPage
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                // 注意：抽屉里凡是跳到「可见 tab」（电台 / 收藏 / 播客……）的项，
                // 都必须走 selectMainPage——它会同步 lastMainPage 与持久化值。
                // 裸设 selectedPage 会被 RepositoryStateEffects 用持久化旧值覆盖，
                // 表现为「抽屉点了没反应」。跳到非 tab 页（设置 / 关于）不受影响。
                DrawerContent(
                    selectedPage = selectedPage,
                    onStationsClick = {
                        // lastMainPage 可能是播客页（它不属于电台区）：那就回到电台首页。
                        // 必须走 selectMainPage：它同步更新 lastMainPage 与持久化值，
                        // 否则 RepositoryStateEffects 会在切页后用持久化的旧值（Podcasts）
                        // 把 selectedPage 覆盖回去，表现为「抽屉点电台没反应」
                        val target = lastMainPage.takeIf { it in MainPage.tabPages }
                            ?: MainPage.Stations
                        selectMainPage(target)
                        scope.launch { drawerState.close() }
                    },
                    onPodcastClick = {
                        selectMainPage(MainPage.Podcasts)
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
                        appLanguage.hostActivity?.finishAffinity()
                    },
                )
            },
        ) {
            Scaffold(
                containerColor = RadioBackground,
                snackbarHost = {
                    SnackbarHost(hostState = podcastSnackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = RadioSurface,
                            contentColor = RadioText,
                            actionContentColor = RadioPrimary,
                            dismissActionContentColor = RadioTextMuted,
                            shape = RoundedCornerShape(RadioCorners.medium),
                        )
                    }
                },
                topBar = {
                    MainTopBar(
                        selectedPage = selectedPage,
                        visualSelectedPage = visualSelectedPage,
                        tabPages = visibleTabPages,
                        sortState = sortState,
                        reordering = reorderDraft != null || podcastReorderDraft != null,
                        onPageSelected = { page ->
                            if (reorderDraft == null && podcastReorderDraft == null) {
                                selectMainPage(page)
                            }
                        },
                        onSortModeSelected = ::selectSortMode,
                        onCancelReorder = {
                            reorderDraft = null
                            podcastReorderDraft = null
                        },
                        onConfirmReorder = {
                            if (podcastReorderDraft != null) {
                                confirmPodcastReorder()
                            } else {
                                confirmReorder()
                            }
                        },
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
                        onOpenPodcastChart = ::openPodcastChart,
                        podcastBrowseActive = podcastBrowseMode != null,
                        // 期数页：顶栏放搜索与 ⋮ 菜单（排序 / 刷新 / 编辑 / 多选 / 退订）
                        podcastEpisodesPageVisible =
                            podcastBrowseMode == null && openPodcastUrl != null,
                        onOpenEpisodeSearch = { podcastEpisodeSearchOpen = true },
                        episodesOldestFirst = podcastEpisodesOldestFirst,
                        onEpisodeOldestFirstChange = { oldest ->
                            scope.launch {
                                podcastRepository.saveEpisodesOldestFirst(oldest)
                            }
                        },
                        onRefreshEpisodeFeed = {
                            podcastList
                                .firstOrNull { it.feedUrl == openPodcastUrl }
                                ?.let { refreshPodcast(it, force = true) }
                        },
                        onEditPodcast = {
                            podcastList
                                .firstOrNull { it.feedUrl == openPodcastUrl }
                                ?.let {
                                    podcastError = null
                                    editPodcastUrl = it.feedUrl
                                }
                        },
                        onEpisodeMultiSelect = { podcastMultiSelectRequest += 1 },
                        onUnsubscribePodcast = {
                            podcastList
                                .firstOrNull { it.feedUrl == openPodcastUrl }
                                ?.let { pendingPodcastRemoval = it }
                        },
                        podcastSortState = podcastSortState,
                        onPodcastSortModeSelected = ::selectPodcastSortMode,
                        podcastListLayout = podcastListLayout,
                        onPodcastListLayoutSelected = { layout ->
                            scope.launch { podcastRepository.saveListLayout(layout) }
                        },
                        // 下载管理页入口：播客订阅列表页（非期数页 / 非搜索 / 未在下载页）
                        // 且有下载任务或已下载节目时才在「+」左边渲染图标——进行中的下载
                        // 也要能进管理页看到，否则第一个文件落盘前点了下载哪儿都找不到
                        onOpenDownloads = if (
                            selectedPage == MainPage.Podcasts &&
                            openPodcastUrl == null &&
                            podcastBrowseMode == null &&
                            !downloadsOpen &&
                            downloadsPageItems.isNotEmpty()
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
                    // 面板只在「有内容可播」时出现：正在播/暂停中的内容，或上次播过且该电台还在列表里。
                    // 上次播放的内容（电台或播客单集）直接作为面板内容显示，
                    // 不显示「未在播放 / 点播放键继续上次的电台」这类占位提示。
                    val playing = playbackState.selectedStation != null
                    // 主动停止后不回落到「上次播放」：面板显示空态，按键置灰
                    val explicitlyStopped = playbackState.stopped
                    val resumeEpisode = if (playing || explicitlyStopped) null else findResumeEpisode()
                    val resumeStation = if (playing || explicitlyStopped) {
                        null
                    } else {
                        lastPlayedStationId?.let { id -> stations.firstOrNull { it.id == id } }
                    }
                    val panelStation = playbackState.selectedStation
                        ?: resumeStation
                        ?: resumeEpisode?.let { (subscription, episode) ->
                            stationForEpisode(subscription, episode)
                        }
                    // 预显示单集时按点播形态渲染：封面 / 副行 / 进度都取订阅里的那一期
                    val panelSeekable = if (playing) playbackState.seekable else resumeEpisode != null
                    val panelDurationMs = if (playing) {
                        playbackState.durationMs
                    } else {
                        resumeEpisode?.second?.durationSec?.times(1000L) ?: 0L
                    }
                    val panelPositionMs = if (playing) {
                        playbackState.positionMs
                    } else {
                        resumeEpisode?.second?.positionMs ?: 0L
                    }
                    // 停止后仍显示面板（空态「未在播放」），避免底部突然消失造成跳动
                    val showPlayerPanel =
                        (selectedPage in visibleTabPages || selectedPage == MainPage.SearchOnline) &&
                            (panelStation != null || explicitlyStopped)
                    // 播客单集：点面板信息区直接回到它所属订阅的期数列表；电台保持原来的「复制曲目」
                    val openEpisodeSubscription: (() -> Unit)? = if (panelSeekable) {
                        {
                            val pair = currentEpisode() ?: findResumeEpisode()
                            if (pair != null) {
                                if (selectedPage != MainPage.Podcasts) {
                                    selectMainPage(MainPage.Podcasts)
                                }
                                openPodcast(pair.first)
                            }
                        }
                    } else {
                        null
                    }
                    if (showPlayerPanel) {
                        PlayerPanel(
                            station = panelStation,
                            trackText = playbackState.trackText,
                            trackStatus = playbackState.trackStatus,
                            errorText = playbackState.errorText,
                            streamInfo = playbackState.streamInfo,
                            loading = playbackState.resolving || playbackState.buffering,
                            isPlaying = playbackState.isPlaying,
                            showBitrate = showBitrateInControlPanel,
                            showPlayerMetadata = showPlayerMetadata,
                            marqueeTrackTitle = marqueeTrackTitle,
                            collapsed = playerPanelCollapsed,
                            positionMs = panelPositionMs,
                            durationMs = panelDurationMs,
                            seekable = panelSeekable,
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
                            // 单集时切相邻一期；预显示上次单集时也切它所在订阅的相邻一期；
                            // 其余情况维持原来的电台切台
                            onPreviousStation = {
                                val resume = if (playbackState.selectedStation == null) findResumeEpisode() else null
                                if (resume != null) {
                                    playAdjacentEpisodeOf(resume.first, resume.second, -1)
                                } else if (!playAdjacentEpisode(-1)) {
                                    playAdjacentStation(-1)
                                }
                            },
                            onNextStation = {
                                val resume = if (playbackState.selectedStation == null) findResumeEpisode() else null
                                if (resume != null) {
                                    playAdjacentEpisodeOf(resume.first, resume.second, 1)
                                } else if (!playAdjacentEpisode(1)) {
                                    playAdjacentStation(1)
                                }
                            },
                            onRandomStation = { playRandomStation() },
                            isFavorite = !panelSeekable && panelStation?.isFavorite == true,
                            onToggleFavorite = {
                                val target = panelStation
                                if (target != null && !panelSeekable) toggleFavorite(target)
                            },
                            onContentClick = openEpisodeSubscription,
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
                                    )
                                },
                                podcastContent = { contentModifier ->
                                    val selectedSearchPodcast = podcastSearchSelected
                                    when {
                                        podcastBrowseMode != null && selectedSearchPodcast != null -> PodcastSearchDetailPage(
                                            podcast = selectedSearchPodcast,
                                            descriptionLoading = podcastSearchDescriptionLoading,
                                            subscribed = podcastFeedKey(selectedSearchPodcast.feedUrl) in subscribedPodcastKeys,
                                            submitting = podcastSearchSubmitting,
                                            errorText = podcastSearchSubmitError,
                                            onSubscribe = { subscribeSearchedPodcast(selectedSearchPodcast) },
                                            onBack = ::closePodcastSearchDetail,
                                            modifier = contentModifier,
                                        )

                                        podcastBrowseMode == PodcastBrowseMode.Search -> PodcastSearchPage(
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

                                        podcastBrowseMode == PodcastBrowseMode.Chart -> PodcastChartPage(
                                            entries = podcastChartEntries,
                                            region = podcastChartRegion,
                                            subscribedIds = subscribedChartIds,
                                            listState = podcastChartListState,
                                            loading = podcastChartLoading,
                                            errorText = podcastChartError,
                                            resolvingId = podcastChartResolvingId,
                                            onRegionChange = ::selectPodcastChartRegion,
                                            onOpen = ::openPodcastFromChart,
                                            onRefresh = { loadPodcastChart(podcastChartRegion, force = true) },
                                            onRetry = { loadPodcastChart(podcastChartRegion, force = true) },
                                            onBack = ::closePodcastChart,
                                            modifier = contentModifier,
                                        )

                                        else -> PodcastTabContent(
                                        subscriptions = podcastList,
                                        openSubscriptionUrl = openPodcastUrl,
                                        playingEpisodeId = playbackState.selectedStation?.id,
                                        loading = podcastLoading,
                                        episodesOldestFirst = podcastEpisodesOldestFirst,
                                        onEpisodesOldestFirstChange = { oldest ->
                                            scope.launch {
                                                podcastRepository.saveEpisodesOldestFirst(oldest)
                                            }
                                        },
                                        episodeSearchOpen = podcastEpisodeSearchOpen,
                                        episodeSearchQuery = podcastEpisodeSearchQuery,
                                        episodeMultiSelectRequest = podcastMultiSelectRequest,
                                        onEpisodeSearchOpenChange = { podcastEpisodeSearchOpen = it },
                                        onEpisodeSearchQueryChange = { podcastEpisodeSearchQuery = it },
                                        onOpenSubscription = ::openPodcast,
                                        onCloseSubscription = {
                                            // 从发现页订阅进来的：关掉期数页时回到发现页原位置
                                            val returnToChart = podcastReturnToChartFor != null &&
                                                podcastReturnToChartFor == openPodcastUrl
                                            podcastReturnToChartFor = null
                                            openPodcastUrl = null
                                            podcastEpisodeSearchOpen = false
                                            podcastEpisodeSearchQuery = ""
                                            if (returnToChart) openPodcastChart()
                                        },
                                        onDeleteSubscription = { pendingPodcastRemoval = it },
                                        reordering = podcastReorderDraft != null,
                                        onMoveSubscription = ::movePodcastReorderDraft,
                                        marqueeTitles = marqueeTrackTitle,
                                        listLayout = podcastListLayout,
                                        onRefreshSubscription = { refreshPodcast(it, force = true) },
                                        // 期数页顶部失败窄条：只显示还没被点掉的那一次失败
                                        refreshFailureReason = podcastSubscriptions
                                            .firstOrNull { it.feedUrl == openPodcastUrl }
                                            ?.pendingRefreshFailureReason,
                                        onRetryRefreshFailure = {
                                            podcastSubscriptions
                                                .firstOrNull { it.feedUrl == openPodcastUrl }
                                                ?.let { refreshPodcast(it, force = true) }
                                        },
                                        onDismissRefreshFailure = {
                                            openPodcastUrl?.let { url ->
                                                scope.launch {
                                                    podcastRepository.dismissRefreshFailure(url)
                                                }
                                            }
                                        },
                                        onRefreshAll = {
                                            refreshAllPodcasts(
                                                podcastVisibleSubscriptions,
                                                silent = false,
                                            )
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
                                        onDownloadEpisodes = ::downloadEpisodes,
                                        onMarkEpisodesPlayed = ::markEpisodesPlayed,
                                        downloads = downloadsPageItems,
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
                                appLanguageTag = appLanguage.tag,
                                autoPlayNextEpisode = autoPlayNextEpisode,
                                stopServiceAfterPauseMinutes = stopServiceAfterPauseMinutes,
                                keepMediaCard = keepMediaCard,
                                showStationArtwork = showStationArtwork,
                                syncingStationArtwork = syncingStationArtwork,
                                showEmptyFavoritesTab = showEmptyFavoritesTab,
                                confirmStationDeletion = confirmStationDeletion,
                                showBitrateInControlPanel = showBitrateInControlPanel,
                                showPlayerMetadata = showPlayerMetadata,
                                marqueeTrackTitle = marqueeTrackTitle,
                                autoExpandPlayerPanelOnPlayback = autoExpandPlayerPanelOnPlayback,
                                collapsePlayerPanelInSearch = collapsePlayerPanelInSearch,
                                addRadioBrowserTags = addRadioBrowserTags,
                                removeTrackingParameters = removeTrackingParametersFromUrls,
                                showAndroidAutoArtwork = showAndroidAutoArtwork,
                                resumeAfterInterruption = resumeAfterInterruption,
                                podcastAutoRefreshOnStart = podcastAutoRefreshOnStart,
                                podcastAutoRefreshOnEnterList = podcastAutoRefreshOnEnterList,
                                podcastRefreshIntervalMinutes = podcastRefreshIntervalMinutes,
                                podcastSearchRegion = podcastSearchRegion,
                                podcastWifiOnlyDownload = podcastWifiOnlyDownload,
                                stationCount = stations.size,
                                podcastCount = podcastVisibleSubscriptions.size,
                                episodeCount = podcastVisibleSubscriptions.sumOf { it.episodes.size },
                                onThemeModeChange = { nextThemeMode ->
                                    scope.launch { repository.saveThemeMode(nextThemeMode) }
                                },
                                onAccentPaletteChange = { nextAccentPalette ->
                                    scope.launch { repository.saveAccentPalette(nextAccentPalette) }
                                },
                                onAppLanguageChange = { tag ->
                                    if (tag != appLanguage.tag) appLanguage.select(tag)
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
                                onShowPlayerMetadataChange = { show ->
                                    showPlayerMetadata = show
                                    scope.launch { repository.saveShowPlayerMetadata(show) }
                                },
                                onResumeAfterInterruptionChange = { resume ->
                                    resumeAfterInterruption = resume
                                    scope.launch { repository.saveResumeAfterInterruption(resume) }
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
                                onPodcastWifiOnlyDownloadChange = { enabled ->
                                    scope.launch { podcastRepository.saveWifiOnlyDownload(enabled) }
                                },
                                onExportBackup = { exportStations() },
                                onImportBackup = {
                                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                                },
                                onExportSimpleText = { exportSimpleText() },
                                onImportSimpleText = {
                                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
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
                podcastCount = importData.podcasts.size,
                episodeCount = importData.episodeCount,
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
                title = resources.appString(R.string.podcast_remove_title, subscription.title),
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

        // 自动刷新失败的明细（提示条「查看」打开）：列出哪个源失败、原因是什么，可逐个重试
        if (podcastRefreshFailuresVisible && podcastRefreshFailures.isNotEmpty()) {
            PodcastRefreshFailuresDialog(
                failures = podcastRefreshFailures,
                onRetry = { failure ->
                    podcastRefreshFailures = podcastRefreshFailures.filterNot {
                        it.subscription.feedUrl == failure.subscription.feedUrl
                    }
                    if (podcastRefreshFailures.isEmpty()) podcastRefreshFailuresVisible = false
                    // 用库里最新的对象重试：提示时的快照可能已落后于刷新期间写入的进度
                    val latest = podcastList.firstOrNull {
                        it.feedUrl == failure.subscription.feedUrl
                    } ?: failure.subscription
                    refreshPodcast(latest, force = true)
                },
                onClose = { podcastRefreshFailuresVisible = false },
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

        pendingBatchDownload?.let { (subscription, episodes) ->
            OmniConfirmDialog(
                title = resources.appString(R.string.dialog_mobile_data_download_title),
                text = resources.appString(
                    R.string.podcast_batch_mobile_data_text,
                    episodes.size,
                ),
                confirmText = resources.appString(R.string.dialog_mobile_data_download_confirm),
                onDismiss = { pendingBatchDownload = null },
                onConfirm = {
                    pendingBatchDownload = null
                    enqueueEpisodeDownloads(subscription, episodes)
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

/**
 * 下载完成提示的合并器。
 *
 * 首条完成立即显示（单个下载零延迟）；窗口期内再完成就整体替换成批量文案
 * （「已下载 N 个节目」）——一次下多集时只出一条 toast，不会一条接一条刷屏。
 * 纯应用内提示：不要通知权限，杀进程后未展示的完成事件自然丢失（可接受）。
 */
private class DownloadCompletionToaster(
    private val resources: Resources,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val titles = mutableListOf<String>()
    private var toast: Toast? = null
    private var windowJob: Job? = null

    fun onCompleted(title: String) {
        titles.add(title)
        // 窗口已在跑：只累加，等窗口结束统一替换
        if (windowJob != null) return
        toast = Toast.makeText(
            context,
            resources.appString(R.string.toast_download_completed, title),
            Toast.LENGTH_SHORT,
        ).apply { show() }
        windowJob = scope.launch {
            delay(TOAST_WINDOW_MS)
            val count = titles.size
            if (count > 1) {
                toast?.cancel()
                toast = Toast.makeText(
                    context,
                    resources.appString(R.string.toast_download_completed_batch, count),
                    Toast.LENGTH_SHORT,
                ).apply { show() }
            }
            titles.clear()
            toast = null
            windowJob = null
        }
    }

    private companion object {
        /** 合并窗口：多集连续完成在这个时长内只出一条提示 */
        const val TOAST_WINDOW_MS = 3_000L
    }
}
