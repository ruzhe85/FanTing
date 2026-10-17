package omnibeat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.AtomicFile
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import omnibeat.app.network.NetworkStatus
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 电台/播客共用封面缓存（按 URL 落盘，按 URL + 目标尺寸做内存副本）。
 *
 * 失败策略是关键：只有**真的拿到响应且失败**才记一次失败，并按 30s → 1m → 2m … 最多 30m 退避重试。
 * 协程取消（列表滚动、切台导致的取消）不算失败，也不会写任何状态——早期版本把“尝试时间”写在
 * 请求之前，一次取消就会让这个封面在 24 小时内不再重试，看起来就是“封面经常加载失败”。
 */
object StationArtworkCache {
    private data class Key(val url: String, val width: Int, val height: Int)
    private data class Artwork(val image: ImageBitmap, val byteCount: Int)

    private data class Metadata(
        /** 最近一次确认可以显示（200/304）的时间，成功缓存保留 30 天不再回源 */
        val checkedAt: Long = 0,
        val failedAt: Long = 0,
        val failureCount: Int = 0,
        /** 下载成功但两种解码器都解不出来（例如 .ico/.svg），一段时间内不再重复下载 */
        val unsupported: Boolean = false,
        val etag: String? = null,
        val lastModified: String? = null,
    )

    private const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val UNSUPPORTED_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val BASE_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 30L * 60 * 1000
    private const val MAX_FAILURE_STEPS = 7
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_PARALLEL_DOWNLOADS = 4
    private const val MEDIA_SIZE = 256
    private const val USER_AGENT = "OmniBeat/1.0 (Android)"

    private fun Metadata.failed(now: Long): Metadata = copy(
        failedAt = now,
        failureCount = (failureCount + 1).coerceAtMost(MAX_FAILURE_STEPS),
    )

    private val images = object : LruCache<Key, Artwork>(12_000_000) {
        override fun sizeOf(key: Key, value: Artwork): Int = value.byteCount
    }
    private val fileLocks = Array(32) { Mutex() }
    private val downloadPermits = Semaphore(MAX_PARALLEL_DOWNLOADS)
    private val revisions = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun updates(url: String): Flow<Long> = revisions.map { it[url] ?: 0 }.distinctUntilChanged()

    fun revision(url: String): Long = revisions.value[url] ?: 0

    fun get(url: String, width: Int, height: Int): ImageBitmap? =
        images.get(Key(url, width, height))?.image

    /**
     * 强制回源一次（忽略 30 天成功缓存与失败退避），用于“刚填的封面地址是否真的能显示”。
     * 返回 false 表示这次没拿到可解码的图片（已按失败记入元数据）。
     */
    suspend fun refresh(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        if (!NetworkStatus.isOnline(context)) return@withContext false
        withFileLock(url) {
            val file = cacheFile(context, url)
            val artwork = refreshNow(context, url, file, MEDIA_SIZE, MEDIA_SIZE, force = true)
            invalidate(url)
            revisions.update { it + (url to ((it[url] ?: 0) + 1)) }
            if (artwork != null) {
                true
            } else {
                // 可能是 200 但内容不可解码，也可能直接失败：两种都算“这张图用不了”
                file.isFile && decodeOrNull(file, MEDIA_SIZE, MEDIA_SIZE) != null
            }
        }
    }

    fun load(context: Context, url: String, width: Int, height: Int): Flow<ImageBitmap> = flow {
        require(width > 0 && height > 0)
        val key = Key(url, width, height)
        withFileLock(url) {
            val file = cacheFile(context, url)
            val cached = images.get(key) ?: if (file.isFile) decodeOrNull(file, width, height) else null
            if (file.isFile && cached == null) {
                // 磁盘上的文件解不出来（旧版本留下的坏格式）：删掉当作没缓存，本次走重新下载
                runCatching { file.delete() }
                runCatching { AtomicFile(File(file.parentFile, "${file.name}.json")).baseFile.delete() }
            }
            cached?.let { artwork ->
                images.put(key, artwork)
                emit(artwork.image)
            }

            val refreshed = refreshNow(context, url, file, width, height, force = false)
            refreshed?.let { artwork ->
                invalidate(url)
                images.put(key, artwork)
                emit(artwork.image)
            }
        }
    }.flowOn(Dispatchers.IO).catch { error ->
        when (error) {
            is IOException, is IllegalArgumentException, is SecurityException, is JSONException -> Unit
            else -> throw error
        }
    }

    /** 预热：只负责把文件下到磁盘，不关心渲染尺寸 */
    suspend fun warm(context: Context, url: String, size: Int = MEDIA_SIZE) {
        load(context, url, size, size).collect { }
    }

    /**
     * 提供给 Android Auto / 通知栏的封面文件。
     * 文件缺失时现下（忽略退避），失败抛 [IOException] 交给 ContentProvider 转成 FileNotFoundException。
     */
    suspend fun fileForMedia(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val file = cacheFile(context, url)
        if (file.isFile && file.length() > 0) return@withContext file
        withFileLock(url) {
            if (file.isFile && file.length() > 0) return@withFileLock
            refreshNow(context, url, file, MEDIA_SIZE, MEDIA_SIZE, force = false)
        }
        if (!file.isFile || file.length() == 0L) throw IOException("Artwork unavailable")
        file
    }

    private suspend fun <T> withFileLock(url: String, block: suspend () -> T): T =
        fileLocks[(url.hashCode() and Int.MAX_VALUE) % fileLocks.size].withLock { block() }

    private fun backoffMillis(failureCount: Int): Long {
        if (failureCount <= 0) return 0
        val steps = failureCount.coerceAtMost(MAX_FAILURE_STEPS) - 1
        return (BASE_BACKOFF_MS shl steps).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** 调用方必须已持有该 URL 的文件锁 */
    private suspend fun refreshNow(
        context: Context,
        url: String,
        file: File,
        width: Int,
        height: Int,
        force: Boolean,
    ): Artwork? {
        val metadataFile = AtomicFile(File(file.parentFile, "${file.name}.json"))
        val metadata = readMetadata(metadataFile)
        val now = System.currentTimeMillis()
        if (!force) {
            // 已知这个地址解不出来：文件可能不存在，所以这条判断不能依赖 file.isFile
            if (metadata.unsupported && now - metadata.checkedAt in 0 until UNSUPPORTED_TTL_MS) return null
            if (file.isFile && now - metadata.checkedAt in 0 until SUCCESS_TTL_MS) return null
            if (!NetworkStatus.isOnline(context)) return null
            if (now - metadata.failedAt in 0 until backoffMillis(metadata.failureCount)) return null
        }

        return try {
            val result = fetch(url, file, metadata, width, height, conditional = !force)
            writeMetadata(metadataFile, result.metadata)
            result.artwork
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            writeMetadata(metadataFile, metadata.failed(now))
            null
        } catch (error: IllegalArgumentException) {
            writeMetadata(metadataFile, metadata.failed(now))
            null
        } catch (error: SecurityException) {
            writeMetadata(metadataFile, metadata.failed(now))
            null
        }
    }

    private class FetchResult(val metadata: Metadata, val artwork: Artwork?)

    private suspend fun fetch(
        url: String,
        file: File,
        metadata: Metadata,
        width: Int,
        height: Int,
        conditional: Boolean,
    ): FetchResult = downloadPermits.withPermit {
        val connection = openConnection(url)
        try {
            if (conditional && file.isFile) {
                metadata.etag?.let { connection.setRequestProperty("If-None-Match", it) }
                metadata.lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            }
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    if (!file.isFile) throw IOException("Artwork is missing for HTTP 304 response")
                    FetchResult(
                        metadata.copy(checkedAt = System.currentTimeMillis(), failedAt = 0, failureCount = 0),
                        null,
                    )
                }

                in 200..299 -> {
                    val contentType = connection.contentType.orEmpty().lowercase()
                    if (isRejectedContentType(contentType)) {
                        throw IOException("Unexpected artwork content type: $contentType")
                    }
                    val temporary = File.createTempFile("artwork-", ".tmp", file.parentFile)
                    try {
                        downloadToFile(connection, temporary)
                        currentCoroutineContext().ensureActive()
                        val artwork = decodeOrNull(temporary, width, height)
                        if (artwork == null) {
                            // 下载成功但解不出来：不落盘（避免留坏文件），标记后一段时间内不再重下
                            FetchResult(
                                Metadata(checkedAt = System.currentTimeMillis(), unsupported = true),
                                null,
                            )
                        } else {
                            moveInto(temporary, file)
                            FetchResult(
                                Metadata(
                                    checkedAt = System.currentTimeMillis(),
                                    etag = connection.getHeaderField("ETag"),
                                    lastModified = connection.getHeaderField("Last-Modified"),
                                ),
                                artwork,
                            )
                        }
                    } finally {
                        temporary.delete()
                    }
                }

                else -> throw IOException("Artwork request failed: HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isRejectedContentType(contentType: String): Boolean {
        if (contentType.isBlank()) return false
        return contentType.startsWith("text/") ||
            contentType.startsWith("application/json") ||
            contentType.startsWith("application/xml") ||
            contentType.startsWith("application/xhtml")
    }

    private fun moveInto(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }

    private fun cacheFile(context: Context, url: String): File {
        val directory = File(context.applicationContext.cacheDir, "station_artwork")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create station artwork cache directory")
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, hash)
    }

    private fun invalidate(url: String) {
        images.snapshot().keys.filter { it.url == url }.forEach { images.remove(it) }
    }

    private fun readMetadata(file: AtomicFile): Metadata {
        if (!file.baseFile.exists()) return Metadata()
        return runCatching {
            val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            Metadata(
                checkedAt = json.optLong("checkedAt", 0),
                failedAt = json.optLong("failedAt", 0),
                failureCount = json.optInt("failureCount", 0),
                unsupported = json.optBoolean("unsupported", false),
                etag = if (json.isNull("etag")) null else json.optString("etag").takeIf { it.isNotBlank() },
                lastModified = if (json.isNull("lastModified")) null else
                    json.optString("lastModified").takeIf { it.isNotBlank() },
            )
        }.getOrElse {
            // 元数据写坏时直接丢掉：否则这个 URL 会永久拿不到图，只能清应用数据才恢复
            runCatching { file.baseFile.delete() }
            Metadata()
        }
    }

    private fun writeMetadata(file: AtomicFile, metadata: Metadata) {
        runCatching {
            val json = JSONObject()
                .put("checkedAt", metadata.checkedAt)
                .put("failedAt", metadata.failedAt)
                .put("failureCount", metadata.failureCount)
                .put("unsupported", metadata.unsupported)
                .put("etag", metadata.etag ?: JSONObject.NULL)
                .put("lastModified", metadata.lastModified ?: JSONObject.NULL)
            val output = file.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }

    private fun decodeOrNull(file: File, width: Int, height: Int): Artwork? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            decode(file, width, height)
        } catch (_: Exception) {
            // ImageDecoder 认不出的格式（部分 WebP/AVIF/ICO）再给 BitmapFactory 一次机会
            decodeWithBitmapFactory(file, width, height)
        }
    }

    private fun decode(file: File, width: Int, height: Int): Artwork {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = max(
                width.toFloat() / info.size.width,
                height.toFloat() / info.size.height,
            ).coerceAtMost(1f)
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
        return Artwork(bitmap.asImageBitmap(), bitmap.allocationByteCount)
    }

    private fun decodeWithBitmapFactory(file: File, width: Int, height: Int): Artwork? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            Artwork(bitmap.asImageBitmap(), bitmap.allocationByteCount)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Unsupported station artwork URL")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "image/*,image/webp,*/*;q=0.8")
        return connection
    }

    private suspend fun downloadToFile(connection: HttpURLConnection, file: File) = withContext(Dispatchers.IO) {
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) throw IOException("Artwork is too large")
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
