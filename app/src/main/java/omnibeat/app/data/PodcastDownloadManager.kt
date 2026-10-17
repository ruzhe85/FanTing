package omnibeat.app.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import omnibeat.app.model.PodcastEpisode
import omnibeat.app.model.PodcastSubscription

/**
 * 单个节目的下载状态（内存态；磁盘态看 [PodcastEpisode.localFileName]）。
 */
sealed interface PodcastDownloadState {
    /**
     * 下载中。[progress] 取 0..1：直文件按字节、HLS 按分片计；
     * 服务端不给 content-length 时为 -1（UI 只转圈不给百分比）。
     */
    data class Downloading(val progress: Float) : PodcastDownloadState

    /** 失败（可重试）。[message] 是可展示文案（RssParser / IO 异常的 message） */
    data class Failed(val message: String) : PodcastDownloadState
}

/**
 * 播客下载器：把单集存到 `filesDir/podcast_audio/`，供离线播放与导出。
 *
 * 设计要点：
 * - **进程级单例 + 自己的 IO Scope**：不绑 UI 生命周期，切页面/重组不打断下载；
 *   进程被杀则中断（v1 明确不做前台服务保活）
 * - **两种源一套状态**：直文件（mp3/m4a）流式落盘；HLS（gdtv:hls 合并 VOD 列表）
 *   拉播放列表 → 分片逐个下本地 → 列表里的分片地址重写为 `file://` → 存本地 `.m3u8`。
 *   不赌分片格式（TS/fMP4 都行，因为是 ExoPlayer 自己组装），加密流连同密钥一起拉本地
 * - **`.part` 临时文件**：中断/失败只留临时文件，完成才 rename——不会出现"假装完整"的半成品
 * - 文件名用 guid 的 MD5 前 16 位：稳定、无非法字符；用户可见的名称在导出时才拼
 */
object PodcastDownloadManager {
    const val AUDIO_DIR_NAME = "podcast_audio"

    private const val PART_SUFFIX = ".part"
    private const val SEGMENTS_DIR_SUFFIX = ".segments"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "OmniBeatP Android"
    private const val BUFFER_SIZE = 64 * 1024
    /** 进度上报节流：至少隔这么久推一次状态，别每 64KB 都重组 UI */
    private const val PROGRESS_INTERVAL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow<Map<String, PodcastDownloadState>>(emptyMap())

    /** guid → 下载状态；UI 收这个 Map 驱动期数行的下载按钮 */
    val states: StateFlow<Map<String, PodcastDownloadState>> = _states.asStateFlow()

    fun stateOf(guid: String): PodcastDownloadState? = states.value[guid]
    fun isDownloading(guid: String): Boolean = jobs.containsKey(guid)

    fun audioDir(context: Context): File =
        File(context.filesDir, AUDIO_DIR_NAME).apply { mkdirs() }

    fun localFile(context: Context, localFileName: String): File =
        File(audioDir(context), localFileName)

    fun localFileExists(context: Context, localFileName: String): Boolean =
        localFile(context, localFileName).isFile

    /** 本地文件的 `file://` URI；文件不在返回 null（调用方回退网络播放） */
    fun localFileUri(context: Context, localFileName: String): String? {
        val file = localFile(context, localFileName)
        return if (file.isFile) "file://${file.absolutePath}" else null
    }

    /** 开始下载（下载中 / 已下载则忽略）；失败状态留在 Map 里，UI 显示重试 */
    fun download(
        context: Context,
        subscription: PodcastSubscription,
        episode: PodcastEpisode,
    ) {
        if (jobs.containsKey(episode.guid)) return
        val downloaded = episode.localFileName
        if (downloaded != null && localFileExists(context, downloaded)) return
        val appContext = context.applicationContext
        val job = scope.launch {
            // 已取消的任务就别落地状态，避免取消与启动竞态时留下一个幽灵 spinner
            ensureActive()
            _states.update { it + (episode.guid to PodcastDownloadState.Downloading(-1f)) }
            runCatching {
                if (isHls(episode.audioUrl)) downloadHls(appContext, episode)
                else downloadDirect(appContext, episode)
            }.onSuccess { fileName ->
                PodcastRepository(appContext).setEpisodeLocalFile(
                    feedUrl = subscription.feedUrl,
                    guid = episode.guid,
                    localFileName = fileName,
                )
                _states.update { it - episode.guid }
            }.onFailure { error ->
                val cancelled = error is CancellationException
                val next = if (cancelled) null else PodcastDownloadState.Failed(error.displayMessage())
                _states.update { current ->
                    if (next == null) current - episode.guid
                    else current + (episode.guid to next)
                }
            }
        }
        jobs[episode.guid] = job
        job.invokeOnCompletion { jobs.remove(episode.guid) }
    }

    /** 取消下载：协程取消 + 清状态；半成品临时文件由下载协程退出时删掉 */
    fun cancel(guid: String) {
        jobs.remove(guid)?.cancel()
        _states.update { it - guid }
    }

    /** 删除下载：停任务、删音频（HLS 连同分片目录）、清 episode 上的标记 */
    fun deleteDownload(
        context: Context,
        subscription: PodcastSubscription,
        episode: PodcastEpisode,
    ) {
        jobs.remove(episode.guid)?.cancel()
        _states.update { it - episode.guid }
        val appContext = context.applicationContext
        scope.launch {
            episode.localFileName?.let { name ->
                val dir = audioDir(appContext)
                File(dir, name).delete()
                File(dir, "$name$SEGMENTS_DIR_SUFFIX").deleteRecursively()
            }
            PodcastRepository(appContext).setEpisodeLocalFile(
                feedUrl = subscription.feedUrl,
                guid = episode.guid,
                localFileName = null,
            )
        }
    }

    /**
     * 某个已下载单集占用的磁盘字节：正文件 + HLS 的分片目录（`.segments` 递归求和）。
     * 下载管理页的总占用 = 各期求和；纯本地实时值，删除/新增后经数据回流即刷新。
     */
    fun episodeDownloadedBytes(context: Context, localFileName: String): Long {
        val dir = audioDir(context)
        val main = File(dir, localFileName).length()
        val segments = File(dir, "$localFileName$SEGMENTS_DIR_SUFFIX")
        val segmentsBytes = if (segments.isDirectory) {
            segments.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        return main + segmentsBytes
    }

    /**
     * 全部删除：删所有已落盘的音频（HLS 连同分片目录）+ 一次清掉所有 localFileName 标记。
     * 先删文件再清标记，不出现「标记没了文件还在」；不动下载中任务
     * （进行中的任务完成后会重新写回标记，属正确行为）。
     */
    fun deleteAllDownloads(context: Context, subscriptions: List<PodcastSubscription>) {
        val appContext = context.applicationContext
        val fileNames = subscriptions
            .flatMap { it.episodes }
            .mapNotNull { it.localFileName }
            .distinct()
        scope.launch {
            val dir = audioDir(appContext)
            fileNames.forEach { name ->
                File(dir, name).delete()
                File(dir, "$name$SEGMENTS_DIR_SUFFIX").deleteRecursively()
            }
            PodcastRepository(appContext).clearAllLocalFiles()
        }
    }

    // ---------------------------------------------------------------- 下载实现

    /** 直文件：流式写 `.part`，完成才 rename 成正名 */
    private fun downloadDirect(context: Context, episode: PodcastEpisode): String {
        val dir = audioDir(context)
        val fileName = "ep_${hashOf(episode.guid)}.${extensionOf(episode.audioUrl)}"
        val target = File(dir, fileName)
        val part = File(dir, "$fileName$PART_SUFFIX")
        part.delete()
        try {
            downloadFile(episode.audioUrl, part) { copied, total ->
                reportProgress(
                    episode.guid,
                    if (total != null) copied.toFloat() / total.toFloat() else -1f,
                )
            }
            if (!part.renameTo(target)) throw IOException("保存文件失败")
            return fileName
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /**
     * HLS：拉播放列表 → 分片（与可能的密钥）逐个下本地 → 重写列表成本地路径。
     * 失败时连分片目录一起清掉，不留垃圾。
     */
    private fun downloadHls(context: Context, episode: PodcastEpisode): String {
        val dir = audioDir(context)
        val fileName = "ep_${hashOf(episode.guid)}.m3u8"
        val segmentsDir = File(dir, "$fileName$SEGMENTS_DIR_SUFFIX")
        val part = File(dir, "$fileName$PART_SUFFIX")
        try {
            segmentsDir.apply { deleteRecursively(); mkdirs() }
            part.delete()

            val playlistUrl = episode.audioUrl
            val lines = readText(playlistUrl).lines()

            // 分片地址：非空且不以 # 开头的行；相对地址按列表地址解析
            val segmentUrls = lines
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { URL(URL(playlistUrl), it).toString() }
            if (segmentUrls.isEmpty()) throw IOException("播放列表里没有分片")

            // EXT-X-KEY 的密钥也拉成本地：否则加密流断网放不了，离线就破了功
            val keyLocalPaths = mutableMapOf<String, String>()
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("URI=\"")) {
                    val keyUri = trimmed.substringAfter("URI=\"").substringBefore('"')
                    if (keyUri.isNotEmpty() && !keyUri.startsWith("file:")) {
                        val resolved = URL(URL(playlistUrl), keyUri).toString()
                        val keyFile = File(segmentsDir, "key_${keyLocalPaths.size}")
                        downloadFile(resolved, keyFile) { _, _ -> }
                        keyLocalPaths[resolved] = keyFile.absolutePath
                    }
                }
            }

            val localSegmentPaths = ArrayList<String>(segmentUrls.size)
            segmentUrls.forEachIndexed { index, url ->
                val segmentFile = File(segmentsDir, "seg_${index.toString().padStart(5, '0')}")
                downloadFile(url, segmentFile) { copied, total ->
                    val segmentFraction =
                        if (total != null && total > 0L) copied.toFloat() / total.toFloat() else 0f
                    reportProgress(
                        episode.guid,
                        (index + segmentFraction) / segmentUrls.size,
                    )
                }
                localSegmentPaths += segmentFile.absolutePath
            }

            // 重写列表：# 行原样（密钥 URI 换本地），分片行换本地 file:// 路径
            val rewritten = StringBuilder()
            var segmentIndex = 0
            lines.forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> rewritten.append(line).append('\n')
                    trimmed.startsWith("#") -> {
                        val rewrittenLine = keyLocalPaths.entries.fold(line) { acc, (uri, path) ->
                            acc.replace(uri, "file://$path")
                        }
                        rewritten.append(rewrittenLine).append('\n')
                    }

                    else -> {
                        rewritten.append("file://").append(localSegmentPaths[segmentIndex])
                            .append('\n')
                        segmentIndex++
                    }
                }
            }
            part.writeText(rewritten.toString())
            if (!part.renameTo(File(dir, fileName))) throw IOException("保存播放列表失败")
            return fileName
        } catch (e: Throwable) {
            part.delete()
            segmentsDir.deleteRecursively()
            throw e
        }
    }

    /** 流式下载单个 URL 到 [target]（临时/半成品由调用方清理）；进度按节流回调 */
    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (copied: Long, total: Long?) -> Unit,
    ) {
        val connection = openConnection(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("服务器返回 HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0L }
            var copied = 0L
            var lastReport = 0L
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            onProgress(copied, total)
                        }
                    }
                }
            }
            onProgress(copied, total)
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(url: String): String {
        val connection = openConnection(url)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("服务器返回 HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
    }

    /** 取消后 Map 里已没有这个 guid，迟到的进度上报直接丢弃，不复活幽灵态 */
    private fun reportProgress(guid: String, progress: Float) {
        _states.update { current ->
            if (!current.containsKey(guid)) current
            else current + (guid to PodcastDownloadState.Downloading(progress))
        }
    }

    private fun Throwable.displayMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun isHls(url: String): Boolean = url.lowercase(Locale.US).contains(".m3u8")

    /** 从 URL 猜扩展名（去 query；白名单外按 mp3 兜底） */
    private fun extensionOf(url: String): String {
        val ext = url.substringBefore('?')
            .substringAfterLast('/', "mp3")
            .substringAfterLast('.', "mp3")
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }
        return ext.ifEmpty { "mp3" }
    }

    /** guid → 稳定文件名片段：MD5 前 8 字节，避开 guid 里的斜杠冒号等非法字符 */
    private fun hashOf(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

private val FILE_NAME_ILLEGAL = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * 清洗成安全文件名：非法字符与控制字符变 `_`、压缩连续 `_`、去首尾空格和点。
 * 给导出建议名用（下载的本地名是 hash，用户看不到，但导出必须可读）。
 */
internal fun sanitizeFileName(name: String): String {
    val cleaned = buildString(name.length) {
        name.forEach { ch ->
            append(if (ch < ' ' || ch in FILE_NAME_ILLEGAL) '_' else ch)
        }
    }
    return cleaned
        .replace(Regex("_{2,}"), "_")
        .trim(' ', '.', '_')
        .ifEmpty { "episode" }
}

/**
 * 导出建议名：`<订阅名> - <单集名>.<ext>`。
 * SAF 里用户还能自己改，这只是默认值；总长截到 100 字符且保留扩展名。
 */
fun buildDownloadExportName(
    subscription: PodcastSubscription,
    episode: PodcastEpisode,
): String {
    val ext = episode.localFileName
        ?.substringAfterLast('.', "mp3")
        ?.takeIf { it.isNotBlank() }
        ?: "mp3"
    val base = sanitizeFileName("${subscription.title} - ${episode.title}")
    val maxBase = (100 - ext.length - 1).coerceAtLeast(1)
    return "${base.take(maxBase)}.$ext"
}
