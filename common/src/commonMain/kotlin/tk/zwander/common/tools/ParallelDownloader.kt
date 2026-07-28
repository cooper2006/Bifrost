package tk.zwander.common.tools

import tk.zwander.common.util.BifrostLogger
import dev.zwander.kotlin.file.IPlatformFile
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import tk.zwander.common.util.globalHttpClient
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 分块并行下载器
 * 支持大文件的多连接并行下载，动态分块大小（50MB-500MB）
 *
 * 使用 FileChannel 的 positioned write 实现线程安全的并发写入，
 * 避免共享 RandomAccessFile 的 seek/write 竞争问题。
 *
 * 支持 401 自动重试：当 nonce 过期导致分块返回 401 时，
 * 通过 authProvider 刷新授权并重试该分块。
 *
 * @deprecated 多线程下载速度不稳定，已改用 FusClient.downloadFile 单线程流式下载。
 *             保留此代码以备未来可能的性能优化使用。
 */
@Deprecated("多线程下载速度不稳定，已改用单线程流式下载。保留以备未来使用。", level = DeprecationLevel.WARNING)
object ParallelDownloader {

    private const val MIN_CHUNK_SIZE = 50L * 1024 * 1024  // 50MB
    private const val MAX_CHUNK_SIZE = 500L * 1024 * 1024  // 500MB（原为 4GB，导致 OOM）
    private const val DEFAULT_CONNECTIONS = 4  // 原为 8，降低并发内存压力
    private const val READ_BUFFER_SIZE = 64 * 1024  // 64KB
    private const val PROGRESS_THROTTLE_MS = 500L  // 进度回调节流间隔
    private const val MAX_AUTH_RETRIES = 3  // 单个分块 401 重试上限

    /** 401 时抛出，用于区分其他错误 */
    private class AuthExpiredException : Exception("401 Unauthorized")

    /**
     * 根据文件大小计算分块大小
     * 分块上限 500MB，避免单个 HTTP 响应在内存中累积过大
     */
    fun calculateChunkSize(fileSize: Long): Long {
        return when {
            fileSize <= MIN_CHUNK_SIZE -> fileSize
            fileSize <= 100L * 1024 * 1024 -> MIN_CHUNK_SIZE
            fileSize <= 1L * 1024 * 1024 * 1024 -> 100L * 1024 * 1024
            fileSize <= 4L * 1024 * 1024 * 1024 -> 200L * 1024 * 1024
            fileSize <= 10L * 1024 * 1024 * 1024 -> 500L * 1024 * 1024
            else -> MAX_CHUNK_SIZE
        }
    }

    /**
     * 计算并行连接数，上限 4
     */
    fun calculateConnections(fileSize: Long): Int {
        val chunkSize = calculateChunkSize(fileSize)
        val chunks = (fileSize + chunkSize - 1) / chunkSize
        return chunks.coerceAtMost(DEFAULT_CONNECTIONS.toLong()).toInt()
    }

    /**
     * 并行下载文件
     *
     * @param authProvider 授权提供者，首次调用获取初始 auth，401 时调用刷新 nonce 并返回新 auth
     */
    suspend fun downloadFile(
        fileName: String,
        start: Long = 0,
        size: Long,
        dest: IPlatformFile,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
        authProvider: suspend () -> String,
    ): String? {
        val url = "http://cloud-neofussvr.samsungmobile.com/NF_SmartDownloadBinaryForMass.do?file=${fileName}"

        val chunkSize = calculateChunkSize(size)
        val connections = calculateConnections(size)
        val totalChunks = ((size - start) + chunkSize - 1) / chunkSize

        BifrostLogger.download.info("Parallel start: size=${size}bytes (${size / (1024 * 1024)}MB), chunkSize=${chunkSize / (1024 * 1024)}MB, connections=$connections, totalChunks=$totalChunks, dest=${dest.getAbsolutePath()}")
        BifrostLogger.download.info("Parallel url=$url")

        val downloadedBytes = MutableStateFlow(start)
        val destPath = dest.getAbsolutePath()

        // 预分配文件大小，避免写入时反复扩展
        val raf = RandomAccessFile(destPath, "rw")
        raf.setLength(size)
        val fileChannel = raf.channel

        // 共享 auth 状态，所有分块使用最新 auth
        val currentAuth = MutableStateFlow(authProvider())
        // Mutex 防止多分块同时刷新 nonce
        val authMutex = Mutex()

        try {
            val semaphore = Semaphore(connections)
            val chunkJobs = mutableListOf<kotlinx.coroutines.Deferred<Long>>()

            coroutineScope {
                for (chunkIndex in 0L until totalChunks) {
                    val chunkStart = start + chunkIndex * chunkSize
                    val chunkEnd = minOf(chunkStart + chunkSize - 1, size - 1)

                    val job = async(Dispatchers.IO) {
                        semaphore.withPermit {
                            downloadChunk(
                                url = url,
                                start = chunkStart,
                                end = chunkEnd,
                                chunkIndex = chunkIndex.toInt(),
                                fileChannel = fileChannel,
                                downloadedBytes = downloadedBytes,
                                progressCallback = progressCallback,
                                totalBytes = size,
                                currentAuth = currentAuth,
                                authProvider = authProvider,
                                authMutex = authMutex,
                            )
                        }
                    }
                    chunkJobs.add(job)
                }
            }

            val chunkSizes = chunkJobs.awaitAll()
            val totalDownloaded = chunkSizes.sum()
            BifrostLogger.download.info("Parallel done: totalDownloaded=${totalDownloaded}bytes (${totalDownloaded / (1024 * 1024)}MB), expected=$size")

            return null
        } catch (e: CancellationException) {
            BifrostLogger.download.info("Parallel cancelled")
            throw e
        } catch (e: Exception) {
            BifrostLogger.download.info("Parallel failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            fileChannel.close()
            raf.close()
            BifrostLogger.download.info("Parallel: file channels closed")
        }
    }

    /**
     * 下载单个分块，支持 401 自动重试
     *
     * 使用 FileChannel.write(src, position) 进行 positioned write，
     * 该操作不改变 channel 的 position 指针，因此多线程并发调用是安全的。
     * 直接从 ByteReadChannel 读取，避免 toInputStream() 的桥接开销。
     */
    private suspend fun downloadChunk(
        url: String,
        start: Long,
        end: Long,
        chunkIndex: Int,
        fileChannel: FileChannel,
        downloadedBytes: MutableStateFlow<Long>,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
        totalBytes: Long,
        currentAuth: MutableStateFlow<String>,
        authProvider: suspend () -> String,
        authMutex: Mutex,
    ): Long {
        val startTime = System.currentTimeMillis()
        val expectedBytes = end - start + 1

        for (attempt in 1..MAX_AUTH_RETRIES) {
            var bytesDownloaded = 0L
            var chunkContrib = 0L  // 本轮对 downloadedBytes 的贡献，用于 401 回滚
            var writePosition = start
            val authV = currentAuth.value

            if (attempt == 1) {
                BifrostLogger.download.info("Chunk $chunkIndex start: range=$start-$end (${expectedBytes / 1024}KB)")
            } else {
                BifrostLogger.download.info("Chunk $chunkIndex retry $attempt/$MAX_AUTH_RETRIES")
            }

            try {
                globalHttpClient.prepareGet(url) {
                    method = HttpMethod.Get
                    header("Authorization", authV)
                    header("User-Agent", "SMART 2.0")
                    header("Range", "bytes=$start-$end")
                    header("Cache-Control", "no-cache")
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        connectTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                }.execute { response ->
                    BifrostLogger.download.info("Chunk $chunkIndex response status=${response.status.value}")

                    if (response.status.value == 401) {
                        throw AuthExpiredException()
                    }
                    if (response.status.value != 206 && response.status.value != 200) {
                        throw RuntimeException("分块下载失败，状态码: ${response.status.value}")
                    }

                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    var lastLogTime = startTime
                    var lastProgressTime = startTime

                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead <= 0) break

                        // positioned write：线程安全，不影响 channel 全局 position
                        byteBuffer.clear()
                        byteBuffer.limit(bytesRead)
                        fileChannel.write(byteBuffer, writePosition)
                        writePosition += bytesRead
                        bytesDownloaded += bytesRead
                        chunkContrib += bytesRead

                        // 原子更新全局进度
                        downloadedBytes.update { it + bytesRead }
                        val now = System.currentTimeMillis()
                        val elapsed = (now - startTime) / 1000.0
                        val bps = if (elapsed > 0) (bytesDownloaded / elapsed).toLong() else 0L

                        // 每5秒打印一次分块进度
                        if (now - lastLogTime > 5000) {
                            BifrostLogger.download.info("Chunk $chunkIndex progress: ${bytesDownloaded / 1024}KB / ${expectedBytes / 1024}KB, bps=${bps / 1024}KB/s")
                            lastLogTime = now
                        }

                        // 进度回调节流，避免每 64KB 都触发 StateFlow 发射
                        if (now - lastProgressTime > PROGRESS_THROTTLE_MS) {
                            progressCallback(downloadedBytes.value, totalBytes, bps)
                            lastProgressTime = now
                        }
                    }
                }

                // 最终进度回调
                progressCallback(downloadedBytes.value, totalBytes, 0L)

                val elapsedTotal = (System.currentTimeMillis() - startTime) / 1000.0
                BifrostLogger.download.info("Chunk $chunkIndex done: ${bytesDownloaded / 1024}KB in ${String.format("%.1f", elapsedTotal)}s")
                return bytesDownloaded

            } catch (e: AuthExpiredException) {
                // 回滚本轮对全局进度的贡献，避免重试时重复计数
                if (chunkContrib > 0) {
                    downloadedBytes.update { (it - chunkContrib).coerceAtLeast(0) }
                }

                if (attempt < MAX_AUTH_RETRIES) {
                    BifrostLogger.download.info("Chunk $chunkIndex got 401, refreshing auth (attempt ${attempt + 1}/$MAX_AUTH_RETRIES)")
                    refreshAuthSafely(authMutex, authProvider, currentAuth, authV)
                    continue
                }
                BifrostLogger.download.info("Chunk $chunkIndex: auth retries exhausted")
                throw RuntimeException("分块 $chunkIndex 授权过期，重试 $MAX_AUTH_RETRIES 次后仍失败")

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BifrostLogger.download.info("Chunk $chunkIndex failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

        throw RuntimeException("Chunk $chunkIndex exhausted retries")
    }

    /**
     * 线程安全地刷新 auth。
     * 如果 auth 已被其他分块刷新过（currentAuth != staleAuth），则跳过刷新，
     * 避免多分块同时 401 时重复刷新 nonce 导致级联失效。
     */
    private suspend fun refreshAuthSafely(
        authMutex: Mutex,
        authProvider: suspend () -> String,
        currentAuth: MutableStateFlow<String>,
        staleAuth: String,
    ) {
        authMutex.withLock {
            if (currentAuth.value != staleAuth) {
                BifrostLogger.download.info("Auth already refreshed by another chunk, skipping")
                return@withLock
            }
            val newAuth = authProvider()
            currentAuth.value = newAuth
            BifrostLogger.download.info("Auth refreshed: ${staleAuth.take(20)}... -> ${newAuth.take(20)}...")
        }
    }
}
