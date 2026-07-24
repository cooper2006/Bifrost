package tk.zwander.common.tools

import dev.zwander.kotlin.file.IPlatformFile
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tk.zwander.common.util.globalHttpClient
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 分块并行下载器
 * 支持大文件的多连接并行下载，动态分块大小（50MB-4GB）
 */
object ParallelDownloader {

    private const val MIN_CHUNK_SIZE = 50L * 1024 * 1024  // 50MB
    private const val MAX_CHUNK_SIZE = 4L * 1024 * 1024 * 1024  // 4GB
    private const val DEFAULT_CONNECTIONS = 8
    private const val READ_BUFFER_SIZE = 64 * 1024  // 64KB

    /**
     * 根据文件大小计算分块大小
     */
    fun calculateChunkSize(fileSize: Long): Long {
        return when {
            fileSize <= MIN_CHUNK_SIZE -> fileSize
            fileSize <= 100L * 1024 * 1024 -> MIN_CHUNK_SIZE
            fileSize <= 1L * 1024 * 1024 * 1024 -> 100L * 1024 * 1024
            fileSize <= 4L * 1024 * 1024 * 1024 -> 500L * 1024 * 1024
            fileSize <= 10L * 1024 * 1024 * 1024 -> 1L * 1024 * 1024 * 1024
            else -> MAX_CHUNK_SIZE
        }
    }

    /**
     * 计算并行连接数
     */
    fun calculateConnections(fileSize: Long): Int {
        val chunkSize = calculateChunkSize(fileSize)
        val chunks = (fileSize + chunkSize - 1) / chunkSize
        return chunks.coerceAtMost(DEFAULT_CONNECTIONS.toLong()).toInt()
    }

    /**
     * 并行下载文件
     */
    suspend fun downloadFile(
        fileName: String,
        start: Long = 0,
        size: Long,
        dest: IPlatformFile,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
        authV: String,
    ): String? {
        val url = "http://cloud-neofussvr.samsungmobile.com/NF_SmartDownloadBinaryForMass.do?file=${fileName}"

        val chunkSize = calculateChunkSize(size)
        val connections = calculateConnections(size)
        val totalChunks = ((size - start) + chunkSize - 1) / chunkSize

        println("文件大小: ${size / (1024 * 1024)}MB, 分块大小: ${chunkSize / (1024 * 1024)}MB, 并行连接数: $connections, 总分块数: $totalChunks")

        val downloadedBytes = MutableStateFlow(start)
        val destPath = dest.getAbsolutePath()
        val randomAccessFile = RandomAccessFile(destPath, "rw")

        try {
            val semaphore = Semaphore(connections)
            val chunkJobs = mutableListOf<kotlinx.coroutines.Deferred<Long>>()

            for (chunkIndex in 0L until totalChunks) {
                val chunkStart = start + chunkIndex * chunkSize
                val chunkEnd = minOf(chunkStart + chunkSize - 1, size - 1)

                val job = CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadChunk(
                            url = url,
                            authV = authV,
                            start = chunkStart,
                            end = chunkEnd,
                            chunkIndex = chunkIndex.toInt(),
                            randomAccessFile = randomAccessFile,
                            downloadedBytes = downloadedBytes,
                            progressCallback = progressCallback,
                            totalBytes = size,
                        )
                    }
                }
                chunkJobs.add(job)
            }

            val chunkSizes = chunkJobs.awaitAll()
            val totalDownloaded = chunkSizes.sum()
            println("下载完成，总共下载: ${totalDownloaded / (1024 * 1024)}MB")

            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("并行下载失败: ${e.message}")
            throw e
        } finally {
            randomAccessFile.close()
        }
    }

    /**
     * 将输出流包装为 OutputStream 以支持写入到 RandomAccessFile 的特定位置
     */
    private class PositionOutputStream(
        private val randomAccessFile: RandomAccessFile,
        private val startOffset: Long,
    ) : OutputStream() {
        private var position = startOffset

        override fun write(b: Int) {
            randomAccessFile.seek(position)
            randomAccessFile.writeByte(b)
            position++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            randomAccessFile.seek(position)
            randomAccessFile.write(b, off, len)
            position += len
        }

        fun getPosition(): Long = position
    }

    /**
     * 下载单个分块
     */
    private suspend fun downloadChunk(
        url: String,
        authV: String,
        start: Long,
        end: Long,
        chunkIndex: Int,
        randomAccessFile: RandomAccessFile,
        downloadedBytes: MutableStateFlow<Long>,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
        totalBytes: Long,
    ): Long {
        var bytesDownloaded = 0L
        val startTime = System.currentTimeMillis()

        try {
            val response = globalHttpClient.prepareGet(url) {
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
            }.execute()

            if (response.status.value != 206 && response.status.value != 200) {
                throw RuntimeException("分块下载失败，状态码: ${response.status.value}")
            }

            val channel = response.bodyAsChannel()
            val outputStream = PositionOutputStream(randomAccessFile, start)
            val inputStream = channel.toInputStream()

            val buffer = ByteArray(READ_BUFFER_SIZE)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead

                val currentDownloaded = downloadedBytes.value + bytesRead
                downloadedBytes.value = currentDownloaded
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val bps = if (elapsed > 0) (bytesDownloaded / elapsed).toLong() else 0L

                progressCallback(currentDownloaded, totalBytes, bps)
            }

            inputStream.close()
            outputStream.close()

            println("分块 $chunkIndex 下载完成: ${bytesDownloaded / (1024 * 1024)}MB")
        } catch (e: Exception) {
            println("分块 $chunkIndex 下载失败: ${e.message}")
            throw e
        }

        return bytesDownloaded
    }
}
