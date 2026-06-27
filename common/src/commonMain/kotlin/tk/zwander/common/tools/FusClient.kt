@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

import com.fleeksoft.io.exception.ArrayIndexOutOfBoundsException
import com.fleeksoft.ksoup.Ksoup
import com.linroid.ketch.api.KetchError
import dev.zwander.kotlin.file.IPlatformFile
import io.github.andreypfau.kotlinx.crypto.CRC32
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.io.Source
import kotlinx.io.Sink
import tk.zwander.common.data.ChunkRetryPolicy
import tk.zwander.common.data.ChunkState
import tk.zwander.common.data.ChunkStatus
import tk.zwander.common.data.ChunkSizeCalculator
import tk.zwander.common.data.DownloadState
import tk.zwander.common.data.DownloadStage
import tk.zwander.common.data.NoncePolicy
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.DEFAULT_CHUNK_SIZE
import tk.zwander.common.util.DownloadStateManager
import tk.zwander.common.util.firstElementByTagName
import tk.zwander.common.util.globalHttpClient
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.max

/**
 * Manage communications with Samsung's server.
 */
object FusClient {
    enum class Request(val value: String, val cloud: Boolean) {
        GENERATE_NONCE("NF_SmartDownloadGenerateNonce.do", false),
        BINARY_INFORM("NF_SmartDownloadBinaryInform.do", false),
        BINARY_INIT("NF_SmartDownloadBinaryInitForMass.do", false),
        HISTORY("SmartHistory.do", false),
    }

    private var nonce = ""

    private var auth: String = ""
    private var sessionId: String = ""

    suspend fun getNonce(): String {
        if (nonce.isBlank()) {
            generateNonce()
        }

        return nonce
    }

    suspend fun refreshNonce() {
        generateNonce()
    }

    private suspend fun generateNonce() {
        BugsnagUtils.addBreadcrumb(
            message = "Generating nonce.",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        makeReq(Request.GENERATE_NONCE)
        BugsnagUtils.addBreadcrumb(
            message = "Nonce: $nonce, Auth: $auth",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
    }

    private suspend fun makeSignatureHash(signature: String?): String? {
        if (signature == null) return null

        val hasher = CryptUtils.md5Provider.hasher()
        val a = hasher.hash("auth:$nonce:00000001".toByteArray()).toHexString()
        val b = hasher.hash("interface:$signature".toByteArray()).toHexString()

        return hasher.hash("$a:FUS:$b".toByteArray()).toHexString()
    }

    private suspend fun getAuthV(includeNonce: Boolean = true, signature: String? = null, cloud: Boolean = false): String {
        val hasSignature = !signature.isNullOrBlank()
        val nonce = when {
            includeNonce && hasSignature -> {
                val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
                CharArray(16) { chars.random() }.joinToString("")
            }
            includeNonce -> nonce
            else -> ""
        }
        return "FUS nonce=\"${if (cloud) nonce else this.nonce}\", " +
                "signature=\"${makeSignatureHash(signature?.takeIf { !it.isBlank() }) ?: this.auth}\", " +
                "nc=\"${if (hasSignature) "00000001" else ""}\", " +
                "type=\"${if (hasSignature) "auth" else ""}\", " +
                "realm=\"${if (hasSignature) "interface" else ""}\""
    }

    private fun getDownloadUrl(path: String): String {
        return "http://cloud-neofussvr.samsungmobile.com/NF_SmartDownloadBinaryForMass.do?file=${path}"
    }

    /**
     * Make a request to Samsung, automatically inserting authorization data.
     * @param request the request to make.
     * @param data any body data that needs to go into the request.
     * @return the response body data, as text. Usually XML.
     */
    suspend fun makeReq(request: Request, data: String = "", signature: String? = null): String {
        if (nonce.isBlank() && request != Request.GENERATE_NONCE) {
            generateNonce()
        }

        val authV = getAuthV(cloud = request.cloud, signature = signature)

        val response =
            globalHttpClient.request("https://neofussvr.sslcs.cdngc.net/${request.value}") {
                method = HttpMethod.Post
                headers {
                    append("Authorization", authV)
                    append("User-Agent", "SMART 2.0")
                    append("Cookie", "JSESSIONID=${sessionId};SESSION=${sessionId}")
                    append("Set-Cookie", "JSESSIONID=${sessionId};SESSION=${sessionId}")
                    append(HttpHeaders.ContentLength, "${data.toByteArray().size}")
                }
                setBody(data)
            }

        val body = response.bodyAsText()

        if (request != Request.GENERATE_NONCE && response.is401(body)) {
            generateNonce()

            return makeReq(request, data)
        }

        if (response.headers["NONCE"] != null || response.headers["nonce"] != null) {
            try {
                nonce = response.headers["NONCE"] ?: response.headers["nonce"] ?: ""

                try {
                    auth = CryptUtils.decryptNonce(nonce.take(16).padEnd((16 - nonce.length).coerceAtLeast(0), '0'))
                } catch (_: Exception) {}
            } catch (e: ArrayIndexOutOfBoundsException) {
                BugsnagUtils.addBreadcrumb(
                    message = "Error generating nonce.",
                    data = mapOf("error" to e),
                    type = BreadcrumbType.ERROR,
                )
                e.printStackTrace()
            }
        }

        if (response.headers["Set-Cookie"] != null || response.headers["set-cookie"] != null) {
            sessionId = response.headers.entries()
                .firstNotNullOfOrNull { headers ->
                    headers.value.find { value ->
                        value.contains("JSESSIONID=") ||
                                value.contains("SESSION=")
                    }
                }
                ?.replace("JSESSIONID=", "")
                ?.replace("SESSION=", "")
                ?.replace(Regex(";.*$"), "")
                ?: sessionId
        }

        return body
    }

    /** Number of parallel chunks for firmware download. */
    private const val DownloadChunkCount = 4

    /** Max retries per chunk on non-auth failure. */
    private const val MaxChunkRetries = 3

    /** Progress update interval in bytes (10MB). */
    private const val ProgressUpdateInterval = 10L * 1024 * 1024

    /**
     * Stream download using Java HttpURLConnection to avoid OkHttp buffering issues.
     * This is essential for very large files (10GB+) that would otherwise cause OOM.
     */
    private suspend fun streamDownloadWithHttpUrlConnection(
        urlString: String,
        authV: String,
        start: Long,
        size: Long,
        dest: IPlatformFile,
        isPaused: suspend () -> Boolean,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit
    ): String? {
        val logFile = java.io.File(System.getProperty("user.home"), "bifrost_fusclient_debug.log")
        
        fun log(msg: String) {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val logMsg = "[$timestamp] $msg"
            println(logMsg)
            logFile.appendText(logMsg + "\n")
        }
        
        log("DEBUG: streamDownloadWithHttpUrlConnection called")
        log("DEBUG: URL: $urlString, size: $size")
        
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        
        try {
            connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", authV)
            connection.setRequestProperty("User-Agent", "SMART 2.0")
            connection.setRequestProperty("Range", "bytes=$start-${size - 1}")
            connection.connectTimeout = 30000
            connection.readTimeout = 0  // No timeout for large files
            connection.instanceFollowRedirects = true
            
            log("DEBUG: Response code: ${connection.responseCode}")
            
            if (connection.responseCode >= 400) {
                throw RuntimeException("HTTP error: ${connection.responseCode} ${connection.responseMessage}")
            }
            
            inputStream = connection.inputStream
            
            val startTime = System.nanoTime()
            var totalWritten = 0L
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
            
            log("DEBUG: About to open output stream...")
            val outputStream = dest.openOutputStream(false)
            log("DEBUG: openOutputStream result: ${outputStream != null}")
            
            if (outputStream == null) {
                val errorMsg = "Failed to open output stream for file: $dest"
                log("DEBUG: ERROR: $errorMsg")
                throw IOException(errorMsg)
            }
            
            outputStream.use { fos ->
                var bytesRead: Int
                var lastProgressLog = System.nanoTime()
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // Check pause state
                    while (isPaused()) {
                        kotlinx.coroutines.delay(100)
                    }
                    
                    fos.write(buffer, 0, bytesRead)
                    totalWritten += bytesRead
                    
                    // Report progress every second
                    val now = System.nanoTime()
                    if (now - lastProgressLog > 1_000_000_000L) {
                        val elapsed = (now - startTime) / 1_000_000.0
                        val bps = if (elapsed > 0) (totalWritten * 1000.0 / elapsed).toLong() else 0L
                        progressCallback(totalWritten, size, bps)
                        lastProgressLog = now
                    }
                }
            }
            
            log("DEBUG: Download completed, total bytes written: $totalWritten")
            return null  // MD5 validation skipped for streaming download
        } catch (e: Exception) {
            log("DEBUG: Stream download failed: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            try {
                inputStream?.close()
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Download a single chunk and return the updated chunk state.
     * Uses HttpURLConnection for streaming to avoid memory issues.
     */
    /**
     * Download a single chunk with retry support.
     */
    private suspend fun downloadSingleChunkWithRetry(
        urlString: String,
        chunk: ChunkState,
        destFile: IPlatformFile,
        isPaused: suspend () -> Boolean,
        onProgress: suspend (bytesRead: Long) -> Unit,
        retryPolicy: ChunkRetryPolicy,
        onAuthError: suspend () -> Unit,
    ): ChunkState {
        val logFile = java.io.File(System.getProperty("user.home"), "bifrost_debug.log")

        fun log(msg: String) {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val logMsg = "[$timestamp] [Chunk ${chunk.chunkId}] $msg"
            println(logMsg)
            logFile.appendText(logMsg + "\n")
        }

        var retryCount = 0
        
        while (retryCount <= retryPolicy.maxRetries) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var bytesDownloaded = 0L
            
            try {
                val currentAuthV = getAuthV(cloud = true)
                
                connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", currentAuthV)
                connection.setRequestProperty("User-Agent", "SMART 2.0")
                connection.setRequestProperty("Range", "bytes=${chunk.startByte}-${chunk.endByte}")
                connection.connectTimeout = 30000
                connection.readTimeout = 0
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                
                if (retryCount == 0) {
                    log("Start: ${chunk.startByte}, End: ${chunk.endByte}, Size: ${chunk.endByte - chunk.startByte + 1}")
                    log("Response code: $responseCode")
                }

                // Handle 401 Unauthorized - refresh nonce and retry
                if (responseCode == 401) {
                    log("Auth error (401), refreshing nonce...")
                    connection.disconnect()
                    onAuthError()
                    retryCount++
                    if (retryCount <= retryPolicy.maxRetries) {
                        val delay = retryPolicy.getDelayForAttempt(retryCount - 1)
                        log("Retrying after ${delay}ms (attempt $retryCount/${retryPolicy.maxRetries})")
                        kotlinx.coroutines.delay(delay)
                        continue
                    } else {
                        log("Max retries exceeded for auth error")
                        return chunk.copy(
                            downloadedBytes = bytesDownloaded,
                            status = ChunkStatus.FAILED,
                        )
                    }
                }

                if (responseCode >= 400) {
                    throw IOException("HTTP error: $responseCode ${connection.responseMessage}")
                }

                inputStream = connection.inputStream

                val outputStream = destFile.openOutputStream(false)
                    ?: throw IOException("Failed to open output stream for chunk ${chunk.chunkId}")

                val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
                var bytesRead: Int
                var lastProgress = 0L

                outputStream.use { fos ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        while (isPaused()) {
                            kotlinx.coroutines.delay(100)
                        }

                        fos.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        if (bytesDownloaded - lastProgress >= ProgressUpdateInterval) {
                            onProgress(bytesDownloaded - lastProgress)
                            lastProgress = bytesDownloaded
                        }
                    }
                }

                if (bytesDownloaded > lastProgress) {
                    onProgress(bytesDownloaded - lastProgress)
                }

                log("Completed, downloaded $bytesDownloaded bytes, file size: ${destFile.getLength()}")
                return chunk.copy(
                    downloadedBytes = bytesDownloaded,
                    status = ChunkStatus.COMPLETED,
                )
            } catch (e: Exception) {
                log("Attempt $retryCount failed: ${e.message}")
                
                // Close resources
                try {
                    inputStream?.close()
                    connection?.disconnect()
                } catch (_: Exception) {}
                
                retryCount++
                if (retryCount <= retryPolicy.maxRetries) {
                    val delay = retryPolicy.getDelayForAttempt(retryCount - 1)
                    log("Retrying after ${delay}ms (attempt $retryCount/${retryPolicy.maxRetries})")
                    kotlinx.coroutines.delay(delay)
                    // Continue to next iteration
                } else {
                    log("Max retries exceeded, marking chunk as failed")
                    return chunk.copy(
                        downloadedBytes = bytesDownloaded,
                        status = ChunkStatus.FAILED,
                    )
                }
            }
        }
        
        // Should not reach here, but return failed state as fallback
        return chunk.copy(status = ChunkStatus.FAILED)
    }

    private suspend fun downloadSingleChunk(
        urlString: String,
        authV: String,
        chunk: ChunkState,
        destFile: IPlatformFile,
        isPaused: suspend () -> Boolean,
        onProgress: suspend (bytesRead: Long) -> Unit,
    ): ChunkState {
        val logFile = java.io.File(System.getProperty("user.home"), "bifrost_debug.log")

        fun log(msg: String) {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val logMsg = "[$timestamp] [Chunk ${chunk.chunkId}] $msg"
            println(logMsg)
            logFile.appendText(logMsg + "\n")
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var bytesDownloaded = 0L

        return try {
            connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", authV)
            connection.setRequestProperty("User-Agent", "SMART 2.0")
            connection.setRequestProperty("Range", "bytes=${chunk.startByte}-${chunk.endByte}")
            connection.connectTimeout = 30000
            connection.readTimeout = 0
            connection.instanceFollowRedirects = true

            log("Start: ${chunk.startByte}, End: ${chunk.endByte}, Size: ${chunk.endByte - chunk.startByte + 1}")
            log("Response code: ${connection.responseCode}, content length: ${connection.contentLength}")

            if (connection.responseCode >= 400) {
                throw IOException("HTTP error: ${connection.responseCode} ${connection.responseMessage}")
            }

            inputStream = connection.inputStream
            
            val contentLength = connection.contentLength
            log("Content length from connection: $contentLength, inputStream available: ${inputStream.available()}")

            val outputStream = destFile.openOutputStream(false)
                ?: throw IOException("Failed to open output stream for chunk ${chunk.chunkId}")

            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
            var bytesRead: Int
            var lastProgress = 0L

            outputStream.use { fos ->
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    while (isPaused()) {
                        kotlinx.coroutines.delay(100)
                    }

                    fos.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    if (bytesDownloaded - lastProgress >= ProgressUpdateInterval) {
                        onProgress(bytesDownloaded - lastProgress)
                        lastProgress = bytesDownloaded
                    }
                }
            }

            if (bytesDownloaded > lastProgress) {
                onProgress(bytesDownloaded - lastProgress)
            }

            log("Completed, downloaded $bytesDownloaded bytes, file size: ${destFile.getLength()}")
            chunk.copy(
                downloadedBytes = bytesDownloaded,
                status = ChunkStatus.COMPLETED,
            )
        } catch (e: Exception) {
            log("Failed: ${e.message}")
            chunk.copy(
                downloadedBytes = bytesDownloaded,
                status = ChunkStatus.FAILED,
            )
        } finally {
            try {
                inputStream?.close()
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Merge all chunk files into the final destination file.
     */
    private suspend fun mergeChunkFiles(
        chunks: List<ChunkState>,
        chunkFileProvider: (Int) -> IPlatformFile?,
        dest: IPlatformFile,
    ): Boolean {
        val outputStream = dest.openOutputStream(false) ?: return false

        return try {
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
            var totalMerged = 0L
            for (chunk in chunks.sortedBy { it.chunkId }) {
                val chunkFile = chunkFileProvider(chunk.chunkId) ?: continue
                val inputStream = chunkFile.openInputStream() ?: continue

                try {
                    var bytesRead: Int
                    while (inputStream.readAtMostTo(buffer, 0, buffer.size).also { bytesRead = it } > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalMerged += bytesRead
                    }
                } finally {
                    inputStream.close()
                }
            }
            outputStream.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Download a file using chunked parallel transfers with resume support.
     *
     * @param fileName the name of the file to download.
     * @param size the total size of the file to download.
     * @param dest the destination file.
     * @param destDir the parent directory of the destination file (for creating chunk temp files).
     * @param firmwareId unique identifier for the firmware (for resume state).
     * @param model device model.
     * @param region device region.
     * @param fw firmware version.
     * @param crc32 optional CRC32 checksum.
     * @param v4KeyBase64 optional base64-encoded V4 decryption key.
     * @param parallelThreads number of parallel download threads.
     * @param isPaused callback to check if download should pause.
     * @param progressCallback reports (current, max, bps) during download.
     * @return MD5 hash (null for now).
     */
    suspend fun downloadFileChunked(
        fileName: String,
        size: Long,
        dest: IPlatformFile,
        destDir: IPlatformFile,
        firmwareId: String,
        model: String,
        region: String,
        fw: String,
        crc32: String? = null,
        v4KeyBase64: String? = null,
        parallelThreads: Int = DownloadChunkCount,
        isPaused: suspend () -> Boolean = { false },
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
        chunkProgressCallback: suspend (completedChunks: Int, totalChunks: Int) -> Unit = { _, _ -> },
        retryPolicy: ChunkRetryPolicy = ChunkRetryPolicy.DEFAULT,
        onNonceRefresh: suspend () -> Unit = {},
    ): String? {
        val logFile = java.io.File(System.getProperty("user.home"), "bifrost_debug.log")

        fun log(msg: String) {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val logMsg = "[$timestamp] $msg"
            println(logMsg)
            logFile.appendText(logMsg + "\n")
        }

        log("=== Chunked Download Start ===")
        log("File: $fileName, Size: $size, Threads: $parallelThreads")

        val overallStartTime = System.currentTimeMillis()

        val url = getDownloadUrl(fileName)
        val chunkSize = ChunkSizeCalculator.calculate(size)

        log("Calculated chunk size: $chunkSize bytes")
        
        // Check nonce expiration and refresh if needed
        val existingState = DownloadStateManager.loadState(firmwareId)
        if (existingState != null && NoncePolicy.isNonceExpired(existingState.downloadStartTime)) {
            log("Nonce expired (age: ${NoncePolicy.getNonceAge(existingState.downloadStartTime)}ms), refreshing...")
            onNonceRefresh()
            log("Nonce refreshed successfully")
        }

        if (dest.getLength() >= size) {
            log("File already fully downloaded (${dest.getLength()} >= $size), skipping")
            return null
        }

        log("Starting chunked download for file: $fileName, size: $size, dest: ${dest.getAbsolutePath()}")

        val destName = dest.getName()
        
        // Create chunk directory - need to ensure it exists
        val chunkDirPath = java.io.File(destDir.getAbsolutePath(), ".bifrost_chunks")
        if (!chunkDirPath.exists()) {
            chunkDirPath.mkdirs()
        }
        val chunkDir = destDir.child(".bifrost_chunks", true)
            ?: throw IOException("Failed to create chunk temp directory")
        log("Chunk dir: ${chunkDir.getAbsolutePath()}, exists on disk: ${chunkDirPath.exists()}")

        fun getChunkFile(chunkId: Int): IPlatformFile? {
            return chunkDir.child("${destName}.chunk_${chunkId}.tmp", false)
        }

        val initialState = existingState?.let { state ->
            log("Resuming existing download, completed chunks: ${state.completedChunks}/${state.totalChunks}")
            state
        } ?: run {
            log("Starting new download")
            val newState = DownloadState.create(
                firmwareId = firmwareId,
                fileName = fileName,
                filePath = dest.getAbsolutePath(),
                fileSize = size,
                model = model,
                region = region,
                fw = fw,
                crc32 = crc32,
                v4KeyBase64 = v4KeyBase64,
                chunkSize = chunkSize,
            )
            DownloadStateManager.saveState(newState)
            newState
        }

        val stateMutex = Mutex()
        var currentState = initialState
        var totalDownloaded = initialState.downloadedBytes
        val startTime = System.nanoTime()
        var lastSaveBytes = totalDownloaded

        suspend fun updateProgress() {
            val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
            val bps = if (elapsed > 0) (totalDownloaded * 1000.0 / elapsed).toLong() else 0L
            progressCallback(totalDownloaded, size, bps)
        }

        suspend fun saveProgressIfNeeded() {
            if (totalDownloaded - lastSaveBytes >= ProgressUpdateInterval) {
                stateMutex.withLock {
                    DownloadStateManager.saveState(currentState)
                }
                lastSaveBytes = totalDownloaded
            }
        }

        suspend fun onChunkProgress(chunkId: Int, bytesDelta: Long) {
            totalDownloaded += bytesDelta
            stateMutex.withLock {
                val chunks = currentState.chunks.toMutableList()
                val chunkIndex = chunks.indexOfFirst { it.chunkId == chunkId }
                if (chunkIndex >= 0) {
                    val chunk = chunks[chunkIndex]
                    chunks[chunkIndex] = chunk.copy(downloadedBytes = chunk.downloadedBytes + bytesDelta)
                    currentState = currentState.copy(
                        downloadedBytes = totalDownloaded,
                        chunks = chunks,
                        lastUpdateTime = System.currentTimeMillis(),
                    )
                }
            }
            updateProgress()
            saveProgressIfNeeded()
        }

        val pendingChunks = currentState.chunks.filter {
            it.status == ChunkStatus.PENDING || it.status == ChunkStatus.FAILED
        }

        log("Pending chunks: ${pendingChunks.size}, total chunks: ${currentState.chunks.size}")
        log("Chunk directory: ${chunkDir.getAbsolutePath()}")
        log("Dest file: ${dest.getAbsolutePath()}, dest name: $destName")

        if (pendingChunks.isEmpty()) {
            log("All chunks already downloaded, merging...")
        } else {
            val chunkIterator = pendingChunks.iterator()
            val mutex = Mutex()
            val nonceRefreshMutex = Mutex()
            var lastNonceRefreshTime = 0L
            val minNonceRefreshInterval = 5000L  // 最小刷新间隔 5 秒

            suspend fun refreshNonceIfNeeded(): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastNonceRefreshTime < minNonceRefreshInterval) {
                    return true  // 最近刚刷新过，直接返回
                }
                
                if (nonceRefreshMutex.tryLock()) {
                    try {
                        if (System.currentTimeMillis() - lastNonceRefreshTime < minNonceRefreshInterval) {
                            return true  // 等待锁期间已经被其他线程刷新了
                        }
                        log("Refreshing nonce (global)...")
                        onNonceRefresh()
                        lastNonceRefreshTime = System.currentTimeMillis()
                        log("Nonce refreshed successfully (global)")
                        return true
                    } finally {
                        nonceRefreshMutex.unlock()
                    }
                } else {
                    // 等待另一个线程刷新完成
                    kotlinx.coroutines.delay(1000)
                    return true
                }
            }

            supervisorScope {
                val jobs = (1..parallelThreads).map { threadId ->
                    async(Dispatchers.IO) {
                        while (true) {
                            val chunk = mutex.withLock {
                                if (chunkIterator.hasNext()) chunkIterator.next() else null
                            } ?: break

                            log("Thread $threadId starting chunk ${chunk.chunkId}")

                            val chunkFile = getChunkFile(chunk.chunkId) ?: continue
                            log("Chunk file ${chunk.chunkId}: ${chunkFile.getAbsolutePath()}")
                            
                            // Create auth error callback for nonce refresh
                            val authErrorCallback: suspend () -> Unit = {
                                log("Auth error detected in chunk ${chunk.chunkId}, triggering global nonce refresh...")
                                refreshNonceIfNeeded()
                            }
                            
                            val result = downloadSingleChunkWithRetry(
                                urlString = url,
                                chunk = chunk,
                                destFile = chunkFile,
                                isPaused = isPaused,
                                onProgress = { bytesDelta ->
                                    onChunkProgress(chunk.chunkId, bytesDelta)
                                },
                                retryPolicy = retryPolicy,
                                onAuthError = authErrorCallback,
                            )
                            log("Chunk ${chunk.chunkId} result: status=${result.statusString}, downloaded=${result.downloadedBytes}")

                            stateMutex.withLock {
                                val chunks = currentState.chunks.toMutableList()
                                val chunkIndex = chunks.indexOfFirst { it.chunkId == chunk.chunkId }
                                if (chunkIndex >= 0) {
                                    chunks[chunkIndex] = result
                                    currentState = currentState.copy(chunks = chunks)
                                    val completed = currentState.chunks.count { it.status == ChunkStatus.COMPLETED }
                                    chunkProgressCallback(completed, currentState.chunks.size)
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        stateMutex.withLock {
            DownloadStateManager.saveState(currentState)
        }

        val failedChunks = currentState.chunks.filter { it.status == ChunkStatus.FAILED }
        if (failedChunks.isNotEmpty()) {
            log("FAILED: ${failedChunks.size} chunks failed")
            throw IOException("Download failed: ${failedChunks.size} chunks failed")
        }

        log("All chunks downloaded, merging ${currentState.chunks.size} chunks into ${dest.getAbsolutePath()}...")
        log("Chunk sizes: ${currentState.chunks.sortedBy { it.chunkId }.joinToString(", ") { "chunk${it.chunkId}=${it.downloadedBytes}b" }}")
        val mergeStartTime = System.currentTimeMillis()
        val mergeSuccess = mergeChunkFiles(
            chunks = currentState.chunks,
            chunkFileProvider = { getChunkFile(it) },
            dest = dest,
        )
        val mergeDuration = System.currentTimeMillis() - mergeStartTime
        log("Merge complete in ${mergeDuration}ms (${mergeDuration / 1000.0}s), final file size: ${dest.getLength()}")

        if (!mergeSuccess) {
            throw IOException("Failed to merge chunk files")
        }

        log("Cleaning up chunk files...")
        val cleanupStartTime = System.currentTimeMillis()
        for (chunk in currentState.chunks) {
            try {
                getChunkFile(chunk.chunkId)?.delete()
            } catch (_: Exception) {}
        }
        try {
            chunkDir.delete()
        } catch (_: Exception) {}
        val cleanupDuration = System.currentTimeMillis() - cleanupStartTime
        log("Cleanup complete in ${cleanupDuration}ms (${cleanupDuration / 1000.0}s)")

        stateMutex.withLock {
            currentState = currentState.copy(
                currentStage = DownloadStage.CRC_CHECKING,
                downloadedBytes = size,
                lastUpdateTime = System.currentTimeMillis(),
            )
            DownloadStateManager.saveState(currentState)
        }

        val overallDuration = System.currentTimeMillis() - overallStartTime
        log("=== Chunked Download Complete ===")
        log("Overall duration: ${overallDuration}ms (${overallDuration / 1000.0}s)")

        return null
    }

    /**
     * Download a file from Samsung's server using parallel chunked transfers.
     * The file is split into N equal chunks and downloaded concurrently via Range headers,
     * each streamed directly to a temp file, then concatenated into the final destination.
     *
     * @param fileName the name of the file to download.
     * @param start an optional offset (unused with chunked download).
     * @param size the total size of the file to download.
     * @param dest the destination file.
     * @param isPaused callback to check if download should pause.
     * @param progressCallback reports (current, max, bps) during download.
     */
    @OptIn(InternalAPI::class)
    @Deprecated("Use downloadFileChunked for resumable download support")
    suspend fun downloadFile(
        fileName: String,
        start: Long = 0,
        size: Long,
        dest: IPlatformFile,
        isPaused: suspend () -> Boolean = { false },
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
    ): String? {
        val logFile = java.io.File(System.getProperty("user.home"), "bifrost_debug.log")
        
        fun log(msg: String) {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val logMsg = "[$timestamp] $msg"
            println(logMsg)
            logFile.appendText(logMsg + "\n")
        }
        
        log("DEBUG: downloadFile called for file: $fileName")
        log("DEBUG: size: $size, start: $start")
        
        val url = getDownloadUrl(fileName)
        log("DEBUG: downloadUrl: $url")
        
        val authV = getAuthV(cloud = true)
        log("DEBUG: authV: ${authV.take(50)}...")

        // Skip MD5 probe to avoid memory issues with large files
        log("DEBUG: Skipping MD5 probe for large file...")
        val md5: String? = null

        // If file is already fully downloaded, skip.
        if (dest.getLength() >= size) {
            return md5
        }

        // Use streaming download with HttpURLConnection for large files to avoid memory issues
        // OkHttp buffers the entire response in memory, which causes OOM for files > 10GB
        log("DEBUG: Using streaming download with HttpURLConnection...")
        
        return streamDownloadWithHttpUrlConnection(
            urlString = url,
            authV = authV,
            start = start,
            size = size,
            dest = dest,
            isPaused = isPaused,
            progressCallback = progressCallback
        )
    }

    private fun KetchError.isAuthFailure(): Boolean {
        return when (this) {
            is KetchError.Http -> code == 401
            is KetchError.AuthenticationFailed -> true
            else -> false
        }
    }

    private fun HttpResponse.is401(body: String): Boolean {
        if (status.value == 401) {
            return true
        }

        try {
            val xml = Ksoup.parse(body)

            val status = xml.firstElementByTagName("FUSBody")
                ?.firstElementByTagName("Results")
                ?.firstElementByTagName("Status")
                ?.text()

            if (status == "401") {
                return true
            }
        } catch (_: Throwable) {
        }

        return false
    }
}