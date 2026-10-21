package omnibeat.app.playback

import omnibeat.app.data.AppLanguagePrefs
import omnibeat.app.data.appString
import omnibeat.app.R

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.AudioAttributes
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import omnibeat.app.MainActivity
import omnibeat.app.data.DEFAULT_STOP_SERVICE_AFTER_PAUSE_MINUTES
import omnibeat.app.data.StationRepository
import omnibeat.app.data.StationArtworkCache
import omnibeat.app.data.STOP_SERVICE_AFTER_PAUSE_NEVER
import omnibeat.app.model.PODCAST_DEFAULT_PLAYBACK_SPEED
import omnibeat.app.model.Station
import omnibeat.app.network.NetworkStatus
import omnibeat.app.stream.IcyMetadataParser
import omnibeat.app.stream.StreamResolver
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val PLAYER_USER_AGENT = "FanTing Android"

/** 临时诊断日志：定位「系统媒体恢复后单集变电台」，确认后删除 */
private const val PLAY_TRACE = "OBPlay"

enum class PlaybackTrackStatus {
    Stopped,
    Paused,
    LoadingStations,
    Resolving,
    WaitingMetadata,
    NoMetadata,
}

data class PlaybackState(
    val selectedIndex: Int = -1,
    val selectedStation: Station? = null,
    val previewing: Boolean = false,
    val trackText: String = "",
    val trackStatus: PlaybackTrackStatus? = PlaybackTrackStatus.Stopped,
    val resolving: Boolean = false,
    val buffering: Boolean = false,
    val isPlaying: Boolean = false,
    val errorText: String? = null,
    val streamInfo: PlaybackStreamInfo = PlaybackStreamInfo(),
    /** 点播单集的播放进度与总时长（直播恒为 0）；播放面板据此画可拖动的进度条 */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /**
     * 当前内容是否为可 seek 的点播（播客单集）；电台直播恒为 false。
     * 播放面板据此切换控制组（点播：快进快退；直播：随机/音量），
     * 不再靠 durationMs 反推 —— 切台时若残留上一集的时长，面板会错误继承点播形态。
     */
    val seekable: Boolean = false,
    /** 当前播放倍速（直播恒为 1.0）；面板按它显示倍速按钮文案 */
    val playbackSpeed: Float = PODCAST_DEFAULT_PLAYBACK_SPEED,
    /** 当前条目已播到结尾（STATE_ENDED）；准备新条目时会自动清零，供 UI 做「标记听完 + 自动下一集」 */
    val ended: Boolean = false,

    /**
     * 用户主动停止（长按停止键 / 通知栏停止）。
     * 与「启动时还没播过」区分开：两者 selectedStation 都是 null，
     * 但停止之后播放面板应显示空态，而不是回落到上次播过的内容。
     */
    val stopped: Boolean = false,
    /**
     * 系统侧（通知栏 / 锁屏 / 耳机线控）请求上一集/下一集的单调递增计数。
     * 单集模式下服务不知道整季列表，只递增计数发信号，由 App 侧执行实际切集；
     * 电台模式不走这里，仍由服务直接切台。
     */
    val previousRequest: Long = 0L,
    val nextRequest: Long = 0L,
)

data class PlaybackStreamInfo(
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val formatLabel: String? = null,
) {
    val sampleRateText: String?
        get() = sampleRateHz?.takeIf { it > 0 }?.let { "${it / 1000} kHz" }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    // 通知栏 / 媒体会话文案跟随应用内选择的语言。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrapContext(newBase))
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private lateinit var repository: StationRepository
    private lateinit var player: ExoPlayer
    private lateinit var sessionPlayer: OmniBeatSessionPlayer
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var library: StationMediaLibrary
    private var stations: List<Station> = emptyList()
    private var resolveJob: Job? = null
    private var noMetadataJob: Job? = null
    private var artworkUpdatesJob: Job? = null
    private var playbackRequested = false
    private var recordedStationId: String? = null
    private var currentStreamIsHls = false

    /**
     * 当前播放的是点播内容（播客单集）。
     * 为 true 时不带直播语义、会话报告可 seek —— 这是「进度条能拖」的关键。
     */
    private var currentItemSeekable = false
    /**
     * 暂停时抓下的播放位置（毫秒）：恢复播放时带回去。
     * 单集是点播，暂停后恢复必须从原位置继续且保持可 seek 语义，
     * 不能走「重新起播」那条路（那会从头播并把面板退化成直播形态）。
     */
    private var pausedPositionMs: Long = 0L
    private var lastPlayedStationId: String? = null
    private var stopServiceAfterPauseMinutes = DEFAULT_STOP_SERVICE_AFTER_PAUSE_MINUTES
    private var showAndroidAutoArtwork = false
    private var navigationQueueIds: List<String> = emptyList()
    private var stopAfterPauseJob: Job? = null

    /** 点播单集的进度轮询（每秒把播放位置同步给 UI）；直播不启用 */
    private var positionJob: Job? = null

    private fun trackText(status: PlaybackTrackStatus): String {
        return when (status) {
            PlaybackTrackStatus.Stopped -> resources.appString(R.string.track_text_stopped)
            PlaybackTrackStatus.Paused -> resources.appString(R.string.track_text_paused)
            PlaybackTrackStatus.LoadingStations -> resources.appString(R.string.track_text_loading_stations)
            PlaybackTrackStatus.Resolving -> resources.appString(R.string.track_text_resolving)
            PlaybackTrackStatus.WaitingMetadata -> resources.appString(R.string.track_text_waiting_metadata)
            PlaybackTrackStatus.NoMetadata -> resources.appString(R.string.track_text_no_metadata)
        }
    }

    private fun PlaybackState.withTrackStatus(status: PlaybackTrackStatus): PlaybackState {
        return copy(trackText = trackText(status), trackStatus = status)
    }

    override fun onCreate() {
        super.onCreate()
        repository = StationRepository(applicationContext)
        player = buildPlayer()
        // 应用内音量已移除：播放器固定满增益输出，音量完全交给系统媒体音量（STREAM_MUSIC）。
        // 显式写一次，避免任何默认值或旧链路残留把增益压在 1.0 以下。
        player.volume = 1f
        sessionPlayer = OmniBeatSessionPlayer()
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        library = StationMediaLibrary(
            this, repository, scope,
            onStationsLoaded = { stations = it },
            onPlaybackRejected = { if (!playbackRequested) stopPlaybackService() },
        )
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_play_arrow)
        })
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, library)
            .setSessionActivity(activityPendingIntent()).build()
        addSession(requireNotNull(mediaSession))
        scope.launch { state.collect { sessionPlayer.syncState() } }
        scope.launch {
            combine(state, repository.showAndroidAutoArtwork) { playback, show ->
                playback.selectedStation?.imageUrl?.takeIf { show }
            }.distinctUntilChanged().collect { url ->
                if (url == null) return@collect
                // 预热放在服务作用域里：切台/换封面不会把正在下的那张图取消掉（取消不再算失败，但白下一半）
                scope.launch { runCatching { StationArtworkCache.warm(this@PlaybackService, url, 256) } }
                artworkUpdatesJob?.cancel()
                artworkUpdatesJob = scope.launch {
                    StationArtworkCache.updates(url).collect {
                        syncSession()
                        mediaSession?.let { library.notifyChanged(it) }
                    }
                }
            }
        }

        scope.launch {
            repository.stations.distinctUntilChanged().collect { savedStations ->
                stations = savedStations
                mediaSession?.let { library.notifyChanged(it) }
                val currentStation = state.value.selectedStation
                if (currentStation == null) {
                    syncSession()
                    return@collect
                }
                if (state.value.previewing) {
                    syncSession()
                    return@collect
                }
                val current = savedStations.indexOfFirst { it.id == currentStation.id }
                Log.w(
                    PLAY_TRACE,
                    "stations.collect: current=${currentStation.id} seekable=${state.value.seekable} " +
                        "index=$current",
                )
                if (current == -1) {
                    // 播客单集用的是临时 Station，本来就不在电台库里。
                    // 早先这里无条件 stopPlayback()，于是电台库任意一次变化（收藏、
                    // 播放记录、导入导出）都会把单集停掉，面板随即退回电台形态。
                    if (!state.value.seekable) stopPlayback()
                } else {
                    _state.update {
                        it.copy(
                            selectedIndex = current,
                            selectedStation = savedStations[current],
                        )
                    }
                    syncSession()
                }
            }
        }
        scope.launch {
            repository.lastPlayedStationId.collect { stationId ->
                lastPlayedStationId = stationId
            }
        }
        scope.launch {
            repository.stopServiceAfterPauseMinutes.collect { minutes ->
                stopServiceAfterPauseMinutes = minutes
            }
        }
        scope.launch {
            repository.showAndroidAutoArtwork.distinctUntilChanged().collect { show ->
                showAndroidAutoArtwork = show
                syncSession()
                mediaSession?.let { library.notifyChanged(it) }
            }
        }
        scope.launch {
            repository.recentlyPlayedStationIds.distinctUntilChanged().collect {
                mediaSession?.let { library.notifyChanged(it) }
            }
        }
        scope.launch {
            combine(
                repository.stationSortState,
                repository.customStationOrder,
                repository.customFavoriteOrder,
            ) { sortState, stationOrder, favoriteOrder ->
                Triple(sortState, stationOrder, favoriteOrder)
            }.distinctUntilChanged().collect {
                mediaSession?.let { library.notifyChanged(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_STATION -> playStationAt(
                index = intent.getIntExtra(EXTRA_INDEX, -1),
                queueIds = intent.getStringArrayListExtra(EXTRA_QUEUE_IDS).orEmpty(),
            )
            ACTION_PLAY_PREVIEW -> playPreviewStation(intent.toPreviewStation())
            ACTION_PLAY_EPISODE -> playEpisodeStation(
                station = intent.toEpisodeStation(),
                startPositionMs = intent.getLongExtra(EXTRA_EPISODE_POSITION_MS, 0L),
                speed = intent.getFloatExtra(EXTRA_EPISODE_SPEED, PODCAST_DEFAULT_PLAYBACK_SPEED),
            )
            ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_SEEK_POSITION_MS, 0L))
            ACTION_SET_PLAYBACK_SPEED -> if (currentItemSeekable) {
                applyPlaybackSpeed(intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, PODCAST_DEFAULT_PLAYBACK_SPEED))
            }
            ACTION_PLAY_PAUSE -> playOrPause()
            ACTION_STOP -> stopPlaybackService()
        }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing || !sessionPlayer.isPlaying) stopPlaybackService()
    }

    override fun onDestroy() {
        resolveJob?.cancel()
        noMetadataJob?.cancel()
        stopAfterPauseJob?.cancel()
        player.removeAnalyticsListener(analyticsListener)
        player.removeListener(playerListener)
        mediaSession?.release()
        sessionPlayer.release()
        player.release()
        _state.update { it.copy(isPlaying = false, resolving = false, buffering = false) }
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun playOrPause() {
        val current = state.value
        if (player.isPlaying || current.resolving || current.buffering) {
            pausePlayback()
        } else {
            playFromCurrentState()
        }
        syncSession()
    }

    private fun playFromCurrentState() {
        val current = state.value
        Log.w(
            PLAY_TRACE,
            "playFromCurrentState: seekable=${current.seekable} curItemSeekable=$currentItemSeekable " +
                "mediaItems=${player.mediaItemCount} status=${current.trackStatus} " +
                "station=${current.selectedStation?.id} pausedPos=$pausedPositionMs",
        )
        if (player.isPlaying || current.resolving) return
        if (player.mediaItemCount > 0 && current.errorText == null &&
            current.trackStatus != PlaybackTrackStatus.Paused
        ) {
            playbackRequested = true
            player.play()
            Log.w(PLAY_TRACE, "playFromCurrentState -> branch=player.play()")
            return
        }
        if (current.selectedStation == null) {
            playLastOrFirstStation()
        } else {
            playStation(
                station = current.selectedStation,
                index = current.selectedIndex,
                previewing = current.previewing,
                // 保持原来的点播语义与续播位置：暂停后恢复不能退化成直播
                // （否则进度条 / 倍速 / 快进全没，还从头播）
                seekable = current.seekable,
                startPositionMs = if (current.seekable) pausedPositionMs else 0L,
                speed = current.playbackSpeed,
            )
        }
    }

    private fun playLastOrFirstStation() {
        scope.launch {
            if (stations.isEmpty()) {
                stations = repository.stations.first()
            }
            // 没选中电台时固定接着上次播放的电台；取不到（首次使用 / 已删除）从第一个开始
            val nextIndex = lastPlayedStationId
                ?.let { stationId -> stations.indexOfFirst { it.id == stationId } }
                ?.takeIf { it >= 0 }
                ?: 0
            playStationAt(nextIndex)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(PLAYER_USER_AGENT)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun playStationAt(
        index: Int,
        queueIds: List<String> = navigationQueueIds,
    ) {
        navigationQueueIds = queueIds
        val station = stations.getOrNull(index)
        if (station == null) {
            _state.update {
                it.copy(
                    selectedIndex = index,
                    selectedStation = null,
                    previewing = false,
                    resolving = true,
                    buffering = false,
                    errorText = null,
                ).withTrackStatus(PlaybackTrackStatus.LoadingStations)
            }
            scope.launch {
                stations = repository.stations.first()
                if (stations.getOrNull(index) == null) {
                    stopPlaybackService()
                } else {
                    playStationAt(index, queueIds)
                }
            }
            return
        }
        playStation(station = station, index = index, previewing = false)
    }

    private fun playPreviewStation(station: Station?) {
        if (station == null) return
        playStation(station = station, index = -1, previewing = true)
    }

    /**
     * 播放播客单集：点播内容，进度必须可拖。
     *
     * previewing = true 表示它不在电台库里 —— 不写播放历史、不缓存封面、队列里只有它自己，
     * 这正是单集需要的语义。
     */
    private fun playEpisodeStation(station: Station?, startPositionMs: Long, speed: Float) {
        if (station == null) return
        // 单集同样要记「最后播放」：previewing 只表示「不在电台库」，
        // 不该顺带把恢复依据也去掉，否则退出重进只能恢复上次的电台。
        lastPlayedStationId = station.id
        scope.launch { repository.saveLastPlayedStationId(station.id) }
        playStation(
            station = station,
            index = -1,
            previewing = true,
            seekable = true,
            startPositionMs = startPositionMs,
            speed = speed,
        )
    }

    /** 单集倍速：只在点播单集上生效，电台直播恒为 1.0× */
    private fun applyPlaybackSpeed(speed: Float) {
        val target = if (speed > 0f) speed else PODCAST_DEFAULT_PLAYBACK_SPEED
        if (player.playbackParameters.speed == target) return
        player.setPlaybackParameters(PlaybackParameters(target))
    }

    /** 条目内跳转进度：只对点播单集生效，直播忽略 */
    private fun seekTo(positionMs: Long) {
        if (!currentItemSeekable) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        // 立刻把新位置同步进 state：轮询最多慢 1 秒，期间若暂停 / 切集，
        // 断点保存用的还是跳转前的旧位置（表现为「跳转了但已听时间变回去」）
        _state.update { it.copy(positionMs = player.currentPosition) }
    }

    private fun playStation(
        station: Station,
        index: Int,
        previewing: Boolean,
        startPlayback: Boolean = true,
        seekable: Boolean = false,
        startPositionMs: Long = 0L,
        speed: Float = PODCAST_DEFAULT_PLAYBACK_SPEED,
    ) {
        Log.w(
            PLAY_TRACE,
            "playStation: id=${station.id} seekable=$seekable startPos=$startPositionMs " +
                "startPlayback=$startPlayback previewing=$previewing",
        )
        resolveJob?.cancel()
        stopAfterPauseJob?.cancel()
        noMetadataJob?.cancel()
        // 进度轮询只属于点播单集：换内容时立刻停掉，否则旧任务的 tick 会把
        // 上一集的 positionMs/durationMs 写回来，面板据此错误继承点播形态
        positionJob?.cancel()
        playbackRequested = startPlayback
        currentItemSeekable = seekable
        // 倍速只跟随单集；切回电台时立即恢复 1.0×，直播行为不受影响
        applyPlaybackSpeed(if (seekable) speed else PODCAST_DEFAULT_PLAYBACK_SPEED)
        if (!previewing) {
            lastPlayedStationId = station.id
            scope.launch { repository.saveLastPlayedStationId(station.id) }
        }
        recordedStationId = null
        currentStreamIsHls = false
        // 已下载的播客单集是本地 file:// 地址：飞行模式也要能放，不过断网检查。
        // （下载管理器只在文件落盘后才写 localFileName，UI 侧播放前也做过存在性兜底；
        //  本地 HLS 的分片与密钥同样在本地，整条链路不碰网络）
        val localPlayback = station.streamUrl.startsWith("file:")
        if (!localPlayback && !NetworkStatus.isOnline(this)) {
            playbackRequested = false
            player.stop()
            _state.update {
                it.copy(
                    selectedIndex = index,
                    selectedStation = station,
                    previewing = previewing,
                    resolving = false,
                    buffering = false,
                    isPlaying = false,
                    errorText = resources.appString(R.string.toast_no_internet),
                    streamInfo = PlaybackStreamInfo(),
                    // 新内容立即就位：清掉上一集的进度/倍速，面板随之切回对应形态
                    seekable = seekable,
                    positionMs = 0L,
                    durationMs = 0L,
                    playbackSpeed = if (seekable) speed else PODCAST_DEFAULT_PLAYBACK_SPEED,
                    ended = false,
                ).withTrackStatus(PlaybackTrackStatus.Stopped)
            }
            stopPlaybackService(errorText = state.value.errorText)
            return
        }
        _state.update {
            it.copy(
                selectedIndex = index,
                selectedStation = station,
                previewing = previewing,
                resolving = true,
                buffering = false,
                isPlaying = false,
                errorText = null,
                streamInfo = PlaybackStreamInfo(),
                // 新内容立即就位：清掉上一集的进度/倍速，面板随之切回对应形态
                seekable = seekable,
                stopped = false,
                positionMs = 0L,
                durationMs = 0L,
                playbackSpeed = if (seekable) speed else PODCAST_DEFAULT_PLAYBACK_SPEED,
                ended = false,
            ).withTrackStatus(PlaybackTrackStatus.Resolving)
        }
        player.stop()
        syncSession()

        resolveJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    StreamResolver.resolveStream(station.streamUrl)
                }
            }.onSuccess { resolvedStream ->
                currentStreamIsHls = resolvedStream.playableUrl.lowercase().contains(".m3u8")
                _state.update {
                    it.copy(
                        streamInfo = PlaybackStreamInfo(
                            bitrateKbps = resolvedStream.bitrateKbps,
                        ),
                    ).withTrackStatus(PlaybackTrackStatus.WaitingMetadata)
                }
                player.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(StationMediaLibrary.stationItem(this@PlaybackService, station).mediaId)
                        .setUri(resolvedStream.playableUrl)
                        .setMimeType(resolvedStream.playableUrl.mediaMimeType())
                        .apply {
                            // 只有电台直播才标记 LiveConfiguration；
                            // 点播单集标了会被播放器当成直播流，时长未知且进度不可拖
                            if (!seekable) {
                                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                            }
                        }
                        .build(),
                )
                player.prepare()
                player.playWhenReady = playbackRequested
                // 断点续播：必须在 prepare 之后 seek，否则会被忽略而从头发起
                if (seekable && startPositionMs > 0L) {
                    player.seekTo(startPositionMs)
                }
                _state.update { it.copy(resolving = false) }
                // 点播单集：开始把播放位置同步给 UI（进度条）；直播不轮询
                // （positionJob 的取消已提前到 playStation 入口）
                if (seekable) startPositionUpdates()
                scheduleNoMetadataIfPlaybackStarted()
                syncSession()
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                playbackRequested = false
                _state.update {
                    it.copy(
                        errorText = "Could not resolve stream: ${error.message}",
                        resolving = false,
                        buffering = false,
                    )
                }
                stopPlaybackService(errorText = state.value.errorText)
            }
        }
    }

    private fun playAdjacentStation(direction: Int) {
        val navigationStations = navigationStations()
        if (navigationStations.isEmpty()) return
        val currentStationId = state.value.selectedStation?.id
        val currentIndex = navigationStations.indexOfFirst { it.id == currentStationId }
        val nextStation = if (currentIndex in navigationStations.indices) {
            navigationStations[(currentIndex + direction + navigationStations.size) % navigationStations.size]
        } else if (direction > 0) {
            navigationStations.first()
        } else {
            navigationStations.last()
        }
        stations.indexOfFirst { it.id == nextStation.id }
            .takeIf { it >= 0 }
            ?.let { playStationAt(it) }
    }

    private fun navigationStations(): List<Station> {
        if (navigationQueueIds.isEmpty()) {
            return stations
        }
        val stationById = stations.associateBy { it.id }
        val queuedStations = navigationQueueIds.mapNotNull(stationById::get)
        return queuedStations.ifEmpty { stations }
    }

    /**
     * 点播单集才开始轮询进度：面板与系统媒体控件靠它画进度条。
     * 直播没有进度，保持不启用（避免无意义的每秒 StateFlow 更新）。
     */
    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                val duration = player.duration
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (duration == C.TIME_UNSET || duration < 0) 0L else duration,
                    )
                }
                delay(1000)
            }
        }
    }

    private fun stopPlayback() {
        // 带 throwable 打一次调用栈：用来确认是谁把单集状态清成了电台
        Log.w(PLAY_TRACE, "stopPlayback: seekable=${state.value.seekable}", Throwable("stopPlayback caller"))
        resolveJob?.cancel()
        noMetadataJob?.cancel()
        stopAfterPauseJob?.cancel()
        positionJob?.cancel()
        playbackRequested = false
        currentStreamIsHls = false
        currentItemSeekable = false
        navigationQueueIds = emptyList()
        player.stop()
        player.clearMediaItems()
        _state.update {
            it.copy(
                selectedIndex = -1,
                selectedStation = null,
                previewing = false,
                resolving = false,
                buffering = false,
                isPlaying = false,
                errorText = null,
                streamInfo = PlaybackStreamInfo(),
                positionMs = 0L,
                durationMs = 0L,
                seekable = false,
                stopped = true,
                playbackSpeed = PODCAST_DEFAULT_PLAYBACK_SPEED,
                ended = false,
            ).withTrackStatus(PlaybackTrackStatus.Stopped)
        }
    }

    private fun pausePlayback() {
        Log.w(
            PLAY_TRACE,
            "pausePlayback: seekable=${state.value.seekable} curItemSeekable=$currentItemSeekable " +
                "station=${state.value.selectedStation?.id}",
        )
        resolveJob?.cancel()
        playbackRequested = false
        noMetadataJob?.cancel()
        currentStreamIsHls = false
        // 停之前把当前位置同步进 state 并抓存：暂停保存断点 / 恢复续播都不能用
        // 最多 1 秒前的轮询值
        if (currentItemSeekable) {
            _state.update { it.copy(positionMs = player.currentPosition) }
            pausedPositionMs = player.currentPosition
        }
        player.stop()
        player.clearMediaItems()
        _state.update {
            it.copy(
                resolving = false,
                buffering = false,
                isPlaying = false,
                errorText = null,
            ).withTrackStatus(PlaybackTrackStatus.Paused)
        }
        syncSession()
        scheduleStopAfterPause()
    }

    private fun stopPlaybackService(errorText: String? = null) {
        val current = state.value
        stopPlayback()
        if (errorText != null) {
            _state.value = current.copy(
                resolving = false,
                buffering = false,
                isPlaying = false,
                errorText = errorText,
                streamInfo = PlaybackStreamInfo(),
            ).withTrackStatus(PlaybackTrackStatus.Stopped)
        }
        syncSession()
        mediaSession?.release()
        mediaSession = null
        pauseAllPlayersAndStopSelf()
    }

    private fun scheduleStopAfterPause() {
        stopAfterPauseJob?.cancel()
        val minutes = stopServiceAfterPauseMinutes
        if (minutes == STOP_SERVICE_AFTER_PAUSE_NEVER) return
        stopAfterPauseJob = scope.launch {
            delay(minutes.minutes)
            val current = state.value
            if (!current.isPlaying && !current.resolving && !current.buffering && current.trackStatus == PlaybackTrackStatus.Paused) {
                stopPlaybackService()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    isPlaying = player.isPlaying,
                    // 播到结尾时置位；准备下一条目时会回到 BUFFERING/READY，从而自动清零
                    ended = playbackState == Player.STATE_ENDED,
                )
            }
            scheduleNoMetadataIfPlaybackStarted()
            syncSession()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            scheduleNoMetadataIfPlaybackStarted()
            val current = state.value
            val stationId = current.selectedStation?.id
            if (isPlaying && !current.previewing && stationId != null && recordedStationId != stationId) {
                recordedStationId = stationId
                scope.launch { repository.recordStationPlayed(stationId) }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(playbackSpeed = playbackParameters.speed) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason in listOf(
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                )
            ) {
                playbackRequested = false
                // 这里不停播放器（只置暂停态）：同样把当前位置同步进 state 并抓存，
                // 断点保存与恢复续播都不用最多 1 秒前的轮询值
                if (currentItemSeekable) {
                    _state.update { it.copy(positionMs = player.currentPosition) }
                    pausedPositionMs = player.currentPosition
                }
                _state.update { it.withTrackStatus(PlaybackTrackStatus.Paused) }
                scheduleStopAfterPause()
            } else if (playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_GAIN
            ) {
                // 别的 App（视频、通话）释放焦点：ExoPlayer 自己会把 playWhenReady 恢复，
                // 但这里的状态还停在 Paused、playbackRequested 也是 false，
                // 于是面板显示暂停、也不接着播。把意图与状态一起拉回来。
                playbackRequested = true
                _state.update { it.copy(trackStatus = null) }
                if (!player.isPlaying && player.mediaItemCount > 0) {
                    player.play()
                }
                scheduleNoMetadataIfPlaybackStarted()
            }
            syncSession()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            syncSession()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (currentStreamIsHls) {
                return
            }

            val selectedBitrates = tracks.groups
                .asSequence()
                .flatMap { group ->
                    (0 until group.length)
                        .asSequence()
                        .filter { group.isTrackSelected(it) }
                        .map { group.getTrackFormat(it).bitrate }
                }
                .filter { it > 0 }
                .distinct()
                .toList()

            if (selectedBitrates.size == 1) {
                updateStreamInfo(bitrate = selectedBitrates.first())
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }?.let { imageUrl ->
                saveCurrentStationImageUrl(imageUrl)
            }
            val title = mediaMetadata.title?.toString().orEmpty()
            val artist = mediaMetadata.artist?.toString().orEmpty()
            val stationTitle = state.value.selectedStation?.title.orEmpty()
            if (title.isBlank() || title == stationTitle) {
                return
            }
            val nextTrackText = if (artist.isNotBlank()) "$artist - $title" else title
            noMetadataJob?.cancel()
            _state.update { it.copy(trackText = nextTrackText, trackStatus = null) }
            syncSession()
        }

        override fun onMetadata(metadata: Metadata) {
            repeat(metadata.length()) { index ->
                val streamTitle = IcyMetadataParser.readStreamTitle(metadata[index].toString())
                if (!streamTitle.isNullOrBlank()) {
                    noMetadataJob?.cancel()
                    _state.update { it.copy(trackText = streamTitle, trackStatus = null) }
                    syncSession()
                    return
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            resolveJob?.cancel()
            noMetadataJob?.cancel()
            playbackRequested = false
            currentStreamIsHls = false
            player.stop()
            _state.update {
                it.copy(
                    errorText = error.message ?: "Playback error",
                    resolving = false,
                    buffering = false,
                    isPlaying = false,
                    streamInfo = PlaybackStreamInfo(),
                )
            }
            stopPlaybackService(errorText = state.value.errorText)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            mediaLoadData.trackFormat?.let { format ->
                val codecLabel = format.sampleMimeType?.audioCodecLabel()
                if (currentStreamIsHls && format.sampleRate <= 0 && codecLabel == null) {
                    return
                }
                updateStreamInfo(format, codecLabel)
            }
        }
    }

    private fun updateStreamInfo(bitrate: Int) {
        val bitrateKbps = bitrateKbps(bitrate) ?: return
        if (state.value.streamInfo.bitrateKbps != bitrateKbps) {
            _state.update { it.copy(streamInfo = it.streamInfo.copy(bitrateKbps = bitrateKbps)) }
            syncSession()
        }
    }

    private fun updateStreamInfo(format: Format, codecLabel: String? = format.sampleMimeType?.audioCodecLabel()) {
        val nextInfo = state.value.streamInfo.copy(
            bitrateKbps = bitrateKbps(format.bitrate) ?: state.value.streamInfo.bitrateKbps,
            sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: state.value.streamInfo.sampleRateHz,
            formatLabel = codecLabel ?: state.value.streamInfo.formatLabel,
        )
        if (state.value.streamInfo != nextInfo) {
            _state.update { it.copy(streamInfo = nextInfo) }
            syncSession()
        }
    }

    private fun bitrateKbps(bitrate: Int): Int? {
        return bitrate.takeIf { it > 0 }?.let { it / 1000 }
    }

    private fun scheduleNoMetadataIfPlaybackStarted() {
        val current = state.value
        if (!player.isPlaying || current.resolving || current.buffering || current.trackStatus != PlaybackTrackStatus.WaitingMetadata) return
        if (noMetadataJob?.isActive == true) return
        noMetadataJob = scope.launch {
            delay(METADATA_WAIT_TIMEOUT)
            val latest = state.value
            if (
                player.isPlaying &&
                !latest.resolving &&
                !latest.buffering &&
                latest.trackStatus == PlaybackTrackStatus.WaitingMetadata
            ) {
                _state.update { it.withTrackStatus(PlaybackTrackStatus.NoMetadata) }
                syncSession()
            }
            noMetadataJob = null
        }
    }


    private fun String.audioCodecLabel(): String? {
        return when {
            contains("aac", ignoreCase = true) || contains("mp4a", ignoreCase = true) -> "AAC"
            contains("mpeg", ignoreCase = true) -> "MP3"
            contains("opus", ignoreCase = true) -> "Opus"
            contains("vorbis", ignoreCase = true) -> "Vorbis"
            contains("flac", ignoreCase = true) -> "FLAC"
            else -> null
        }
    }

    private fun String.mediaMimeType(): String? {
        return when {
            contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private fun syncSession() {
        sessionPlayer.syncState()
    }

    private fun buildSessionMetadata(station: Station, trackText: String?): MediaMetadata {
        val metadata = StationMediaLibrary.stationMetadata(this, station, trackText, showAndroidAutoArtwork)
        return if (state.value.previewing) metadata.buildUpon()
            .setArtworkUri(station.imageUrl?.takeIf { showAndroidAutoArtwork }?.let(Uri::parse)).build() else metadata
    }

    private fun saveCurrentStationImageUrl(imageUrl: String) {
        val current = state.value
        val station = current.selectedStation ?: return
        // 用户手动指定的封面不允许被流内嵌图覆盖
        if (current.previewing || station.imageUrl == imageUrl || station.imageUrlManual) {
            return
        }
        val stationId = station.id
        _state.update { playbackState ->
            playbackState.copy(selectedStation = playbackState.selectedStation?.copy(imageUrl = imageUrl))
        }
        scope.launch { repository.saveStationImageUrl(stationId, imageUrl) }
    }

    private fun activityPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private inner class OmniBeatSessionPlayer : SimpleBasePlayer(mainLooper) {
        private var reportedError: PlaybackException? = null

        fun syncState() = invalidateState()

        override fun getState(): State {
            val current = PlaybackService.state.value
            val selected = current.selectedStation
            val queue = when {
                selected == null -> emptyList()
                current.previewing -> listOf(selected)
                else -> navigationStations().let { stations ->
                    if (stations.any { it.id == selected.id }) stations else listOf(selected)
                }
            }
            if (current.errorText != reportedError?.message) {
                reportedError = current.errorText?.let {
                    player.playerError ?: PlaybackException(it, null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                }
            }
            val error = reportedError
            // 点播单集：报告真实时长与播放位置，系统媒体控件/播放面板的进度条才会跟着走、才能拖
            val seekable = currentItemSeekable
            val positionMs = if (seekable) player.currentPosition else 0L
            val durationMs = player.duration
            return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAll(
                    COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
                    COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
                    COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                ).apply {
                    // 只有点播单集暴露「条目内 seek」：电台直播保持不可拖，避免行为变化
                    if (seekable) add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }.build())
                .setPlaylist(queue.map { station ->
                    MediaItemData.Builder(station.id)
                        .setMediaItem(StationMediaLibrary.stationItem(
                            this@PlaybackService, station, showArtwork = showAndroidAutoArtwork,
                        ))
                        .setMediaMetadata(buildSessionMetadata(
                            station, current.trackText.takeIf { station.id == selected?.id && current.trackStatus == null },
                        ))
                        .apply {
                            // 点播单集：不带直播配置，改为报告真实时长，并标记为可 seek
                            if (seekable) {
                                if (durationMs != C.TIME_UNSET && durationMs > 0) setDurationUs(durationMs * 1000)
                            } else {
                                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                            }
                        }
                        .setIsDynamic(!seekable)
                        .setIsSeekable(seekable)
                        .build()
                })
                .setCurrentMediaItemIndex(queue.indexOfFirst { it.id == selected?.id })
                .setRepeatMode(REPEAT_MODE_ALL)
                .setPlayWhenReady(
                    error == null && playbackRequested && (current.resolving || player.playWhenReady),
                    PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                )
                .setPlaybackState(when {
                    error != null || queue.isEmpty() -> STATE_IDLE
                    current.resolving -> STATE_BUFFERING
                    else -> player.playbackState
                })
                .setPlaybackSuppressionReason(player.playbackSuppressionReason)
                .setPlayerError(error)
                .setAudioAttributes(player.audioAttributes)
                .setVolume(player.volume)
                .setContentPositionMs(positionMs)
                .build()
        }

        override fun handleSetMediaItems(
            mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<*> {
            val selected = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            val incomingId = selected?.mediaId?.let(StationMediaLibrary::stationId)
            val currentState = PlaybackService.state.value
            // 系统媒体控件「继续播放」时会把队列再推一次。若推回来的仍是当前这一期，
            // 只当同步处理：否则下面的 seekable = false 会把面板打回电台形态，
            // 进度条与倍速一起消失。
            if (currentItemSeekable && incomingId == currentState.selectedStation?.id) {
                return Futures.immediateVoidFuture()
            }
            val station = stations.find { it.id == incomingId }
            Log.w(
                PLAY_TRACE,
                "handleSetMediaItems: incoming=$incomingId curItemSeekable=$currentItemSeekable " +
                    "station=${currentState.selectedStation?.id} found=${station?.id} count=${mediaItems.size}",
            )
            if (station == null) {
                stopPlayback()
                return Futures.immediateVoidFuture()
            }
            resolveJob?.cancel()
            stopAfterPauseJob?.cancel()
            noMetadataJob?.cancel()
            positionJob?.cancel()
            playbackRequested = false
            currentItemSeekable = false
            // 系统侧起播的队列只可能是电台：清掉点播语义，倍速回到 1.0×
            applyPlaybackSpeed(PODCAST_DEFAULT_PLAYBACK_SPEED)
            player.stop()
            player.clearMediaItems()
            navigationQueueIds = mediaItems.mapNotNull { StationMediaLibrary.stationId(it.mediaId) }
            _state.update {
                it.copy(
                    selectedStation = station, selectedIndex = stations.indexOfFirst { item -> item.id == station.id },
                    previewing = false, resolving = false, buffering = false, isPlaying = false,
                    errorText = null, streamInfo = PlaybackStreamInfo(),
                    seekable = false,
                    stopped = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    playbackSpeed = PODCAST_DEFAULT_PLAYBACK_SPEED,
                    ended = false,
                ).withTrackStatus(PlaybackTrackStatus.Paused)
            }
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            val current = PlaybackService.state.value
            if (!current.resolving && player.playbackState == STATE_IDLE) {
                current.selectedStation?.let {
                    // 必须带上原来的点播语义与断点：系统媒体「继续」会先发 prepare，
                    // 暂停时已经 stop + clearMediaItems，这里是唯一的重建入口。
                    // 早先没传这两个参数，单集被按电台重建 —— 面板变 LIVE、进度归零。
                    playStation(
                        station = it,
                        index = current.selectedIndex,
                        previewing = current.previewing,
                        startPlayback = playbackRequested,
                        seekable = current.seekable,
                        startPositionMs = if (current.seekable) pausedPositionMs else 0L,
                        speed = current.playbackSpeed,
                    )
                }
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            Log.w(
                PLAY_TRACE,
                "handleSetPlayWhenReady($playWhenReady): seekable=${PlaybackService.state.value.seekable} " +
                    "curItemSeekable=$currentItemSeekable station=${PlaybackService.state.value.selectedStation?.id}",
            )
            if (playWhenReady) {
                if (PlaybackService.state.value.resolving) {
                    playbackRequested = true
                } else {
                    playFromCurrentState()
                }
            } else {
                pausePlayback()
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                // 单集：服务不知道整季列表，只递增计数把请求转给 App 侧；电台仍按原逻辑切台
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> if (currentItemSeekable) {
                    _state.update { it.copy(previousRequest = it.previousRequest + 1L) }
                } else {
                    playAdjacentStation(-1)
                }

                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> if (currentItemSeekable) {
                    _state.update { it.copy(nextRequest = it.nextRequest + 1L) }
                } else {
                    playAdjacentStation(1)
                }
                COMMAND_SEEK_TO_MEDIA_ITEM -> navigationStations().getOrNull(mediaItemIndex)?.let { station ->
                    val index = stations.indexOfFirst { it.id == station.id }
                    if (index >= 0) playStationAt(index)
                }
                // 点播单集：条目内跳转进度（真正的 seek，不是切台）
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> if (currentItemSeekable) player.seekTo(positionMs)
                else -> Unit
            }
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            stopPlayback()
            return Futures.immediateVoidFuture()
        }

        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    companion object {
        private val METADATA_WAIT_TIMEOUT = 15.seconds
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_QUEUE_IDS = "queue_ids"
        private const val EXTRA_PREVIEW_ID = "preview_id"
        private const val EXTRA_PREVIEW_TITLE = "preview_title"
        private const val EXTRA_PREVIEW_STREAM_URL = "preview_stream_url"
        private const val EXTRA_PREVIEW_TAGS = "preview_tags"
        private const val EXTRA_PREVIEW_IMAGE_URL = "preview_image_url"
        private const val EXTRA_EPISODE_ID = "episode_id"
        private const val EXTRA_EPISODE_TITLE = "episode_title"
        private const val EXTRA_EPISODE_STREAM_URL = "episode_stream_url"
        private const val EXTRA_EPISODE_IMAGE_URL = "episode_image_url"
        private const val EXTRA_EPISODE_ARTIST = "episode_artist"
        private const val EXTRA_EPISODE_POSITION_MS = "episode_position_ms"
        private const val EXTRA_EPISODE_SPEED = "episode_speed"
        private const val EXTRA_SEEK_POSITION_MS = "seek_position_ms"
        private const val EXTRA_PLAYBACK_SPEED = "playback_speed"
        private const val ACTION_PLAY_STATION = "omnibeat.app.action.PLAY_STATION"
        private const val ACTION_PLAY_PREVIEW = "omnibeat.app.action.PLAY_PREVIEW"
        private const val ACTION_PLAY_EPISODE = "omnibeat.app.action.PLAY_EPISODE"
        private const val ACTION_SEEK = "omnibeat.app.action.SEEK"
        private const val ACTION_SET_PLAYBACK_SPEED = "omnibeat.app.action.SET_PLAYBACK_SPEED"
        private const val ACTION_PLAY_PAUSE = "omnibeat.app.action.PLAY_PAUSE"
        private const val ACTION_STOP = "omnibeat.app.action.STOP"

        private val _state = MutableStateFlow(PlaybackState())
        val state: StateFlow<PlaybackState> = _state.asStateFlow()

        fun playStation(
            context: Context,
            index: Int,
            queueIds: List<String> = emptyList(),
        ) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PLAY_STATION)
                    .putExtra(EXTRA_INDEX, index)
                    .putStringArrayListExtra(EXTRA_QUEUE_IDS, ArrayList(queueIds)),
            )
        }

        fun playPreview(context: Context, station: Station) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PLAY_PREVIEW)
                    .putExtra(EXTRA_PREVIEW_ID, station.id)
                    .putExtra(EXTRA_PREVIEW_TITLE, station.title)
                    .putExtra(EXTRA_PREVIEW_STREAM_URL, station.streamUrl)
                    .putExtra(EXTRA_PREVIEW_TAGS, station.tags.joinToString(","))
                    .putExtra(EXTRA_PREVIEW_IMAGE_URL, station.imageUrl.orEmpty()),
            )
        }

        /**
         * 播放一个播客单集。
         *
         * [episode] 只是「播放描述」：id 用单集 guid、title 用单集标题、
         * streamUrl 用已选好的音源地址（优先 `gdtv:hls`，兜底 `enclosure`）、imageUrl 用订阅封面。
         * 它**不会**写进电台库。
         *
         * 与 [playPreview] 的关键区别：单集是点播内容，按可拖动进度播放。
         */
        fun playEpisode(
            context: Context,
            episode: Station,
            startPositionMs: Long = 0L,
            speed: Float = PODCAST_DEFAULT_PLAYBACK_SPEED,
        ) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PLAY_EPISODE)
                    .putExtra(EXTRA_EPISODE_ID, episode.id)
                    .putExtra(EXTRA_EPISODE_TITLE, episode.title)
                    .putExtra(EXTRA_EPISODE_STREAM_URL, episode.streamUrl)
                    .putExtra(EXTRA_EPISODE_IMAGE_URL, episode.imageUrl.orEmpty())
                    .putExtra(EXTRA_EPISODE_ARTIST, episode.artist.orEmpty())
                    .putExtra(EXTRA_EPISODE_POSITION_MS, startPositionMs)
                    .putExtra(EXTRA_EPISODE_SPEED, speed),
            )
        }

        /** 切换单集播放倍速（电台直播会忽略） */
        fun setPlaybackSpeed(context: Context, speed: Float) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_SET_PLAYBACK_SPEED)
                    .putExtra(EXTRA_PLAYBACK_SPEED, speed),
            )
        }

        /** 拖动播放面板的进度条：跳转到指定位置（仅对播客单集生效） */
        fun seekTo(context: Context, positionMs: Long) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_SEEK)
                    .putExtra(EXTRA_SEEK_POSITION_MS, positionMs),
            )
        }

        fun playOrPause(context: Context) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_PLAY_PAUSE),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(ACTION_STOP),
            )
        }

        private fun Intent.toPreviewStation(): Station? {
            val title = extras?.getString(EXTRA_PREVIEW_TITLE)?.trim().orEmpty()
            val streamUrl = extras?.getString(EXTRA_PREVIEW_STREAM_URL)?.trim().orEmpty()
            if (title.isBlank() || streamUrl.isBlank()) {
                return null
            }
            return Station(
                id = extras?.getString(EXTRA_PREVIEW_ID)?.takeIf { it.isNotBlank() } ?: "preview:$streamUrl",
                title = title,
                streamUrl = streamUrl,
                tags = extras?.getString(EXTRA_PREVIEW_TAGS)
                    .orEmpty()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                imageUrl = extras?.getString(EXTRA_PREVIEW_IMAGE_URL)?.trim()?.takeIf { it.isNotBlank() },
                isFavorite = false,
                dateAdded = "",
            )
        }

        /** 把 Intent 里的单集信息还原成「播放描述」（不落库） */
        private fun Intent.toEpisodeStation(): Station? {
            val title = extras?.getString(EXTRA_EPISODE_TITLE)?.trim().orEmpty()
            val streamUrl = extras?.getString(EXTRA_EPISODE_STREAM_URL)?.trim().orEmpty()
            if (title.isBlank() || streamUrl.isBlank()) {
                return null
            }
            return Station(
                id = extras?.getString(EXTRA_EPISODE_ID)?.takeIf { it.isNotBlank() } ?: "episode:$streamUrl",
                title = title,
                streamUrl = streamUrl,
                tags = emptyList(),
                imageUrl = extras?.getString(EXTRA_EPISODE_IMAGE_URL)?.trim()?.takeIf { it.isNotBlank() },
                // 歌手位：播客单集挂订阅名（通知/锁屏显示「单集标题 / 播客名」）。
                // 必须经 extras 过界——UI 设的 artist 不到这里，系统播放栏就是空的
                artist = extras?.getString(EXTRA_EPISODE_ARTIST)?.trim()?.takeIf { it.isNotBlank() },
                isFavorite = false,
                dateAdded = "",
            )
        }
    }
}
