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
import kotlinx.atomicfu.atomic
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
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.Sink
import org.slf4j.LoggerFactory
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

private val logger = LoggerFactory.getLogger("FusClient")

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

    /**
     * Thread-safe state container for FusClient
     */
    private data class ClientState(
        val nonce: String = "",
        val auth: String = "",
        val sessionId: String = "",
    )

    private val stateMutex = Mutex()
    private var currentState = ClientState()

    suspend fun getNonce(): String {
        return stateMutex.withLock {
            if (currentState.nonce.isBlank()) {
                generateNonce()
            }
            currentState.nonce
        }
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
            message = "Nonce: ${currentState.nonce}, Auth: ${currentState.auth}",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
    }

    private suspend fun makeSignatureHash(signature: String?): String? {
        if (signature == null) return null

        val nonce = stateMutex.withLock { currentState.nonce }
        val hasher = CryptUtils.md5Provider.hasher()
        val a = hasher.hash("auth:$nonce:00000001".toByteArray()).toHexString()
        val b = hasher.hash("interface:$signature".toByteArray()).toHexString()

        return hasher.hash("$a:FUS:$b".toByteArray()).toHexString()
    }

    private suspend fun getAuthV(includeNonce: Boolean = true, signature: String? = null, cloud: Boolean = false): String {
        val hasSignature = !signature.isNullOrBlank()
        val (nonceValue, currentAuth) = stateMutex.withLock {
            val nonceVal = when {
                includeNonce && hasSignature -> {
                    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
                    CharArray(16) { chars.random() }.joinToString("")
                }
                includeNonce -> currentState.nonce
                else -> ""
            }
            nonceVal to currentState.auth
        }

        return "FUS nonce=\"${if (cloud) nonceValue else currentState.nonce}\", " +
                "signature=\"${makeSignatureHash(signature?.takeIf { !it.isBlank() }) ?: currentAuth}\", " +
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
        if (request != Request.GENERATE_NONCE) {
            val nonceBlank = stateMutex.withLock { currentState.nonce.isBlank() }
            if (nonceBlank) {
                generateNonce()
            }
        }

        val (currentNonce, currentSessionId) = stateMutex.withLock {
            currentState.nonce to currentState.sessionId
        }

        val authV = getAuthV(cloud = request.cloud, signature = signature)

        val response =
            globalHttpClient.request("https://neofussvr.sslcs.cdngc.net/${request.value}") {
                method = HttpMethod.Post
                headers {
                    append("Authorization", authV)
                    append("User-Agent", "SMART 2.0")
                    append("Cookie", "JSESSIONID=$currentSessionId;SESSION=$currentSessionId")
                    append("Set-Cookie", "JSESSIONID=$currentSessionId;SESSION=$currentSessionId")
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
                val newNonce = response.headers["NONCE"] ?: response.headers["nonce"] ?: ""

                try {
                    val newAuth = CryptUtils.decryptNonce(newNonce.take(16).padEnd((16 - newNonce.length).coerceAtLeast(0), '0'))
                    stateMutex.withLock {
                        currentState = currentState.copy(
                            nonce = newNonce,
                            auth = newAuth,
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to decrypt nonce", e)
                }
            } catch (e: ArrayIndexOutOfBoundsException) {
                BugsnagUtils.addBreadcrumb(
                    message = "Error generating nonce.",
                    data = mapOf("error" to e),
                    type = BreadcrumbType.ERROR,
                )
                logger.error("Error generating nonce", e)
            }
        }

        if (response.headers["Set-Cookie"] != null || response.headers["set-cookie"] != null) {
            val newSessionId = response.headers.entries()
                .firstNotNullOfOrNull { headers ->
                    headers.value.find { value ->
                        value.contains("JSESSIONID=") ||
                                value.contains("SESSION=")
                    }
                }
                ?.replace("JSESSIONID=", "")
                ?.replace("SESSION=", "")
                ?.replace(Regex(";.*$"), "")

            newSessionId?.let { sessionId ->
                stateMutex.withLock {
                    currentState = currentState.copy(sessionId = sessionId)
                }
            }
        }

        return body
    }

    /** Number of parallel chunks for firmware download. */
    private const val DownloadChunkCount = 4

    /** Max retries per chunk on non-auth failure. */
    private const val MaxChunkRetries = 3

    /** Progress update interval in bytes (10MB). */
    private const val ProgressUpdateInterval = 10L * 1024 * 1024

    /** HTTP connection timeout in milliseconds (30 seconds). */
    private const val HTTP_CONNECT_TIMEOUT_MS = 30_000

    /** Minimum interval between nonce refreshes in milliseconds (5 seconds). */
    private const val MIN_NONCE_REFRESH_INTERVAL_MS = 5_000L

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
        logger.debug("streamDownloadWithHttpUrlConnection called, URL: $urlString, size: $size")

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null

        try {
            connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", authV)
            connection.setRequestProperty("User-Agent", "SMART 2.0")
            connection.setRequestProperty("Range", "bytes=$start-${size - 1}")
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = 0  // No timeout for large files
            connection.instanceFollowRedirects = true

            logger.debug("Response code: ${connection.responseCode}")

            if (connection.responseCode >= 400) {
                throw IOException("HTTP error: ${connection.responseCode} ${connection.responseMessage}")
            }

            inputStream = connection.inputStream

            val startTime = System.nanoTime()
            var totalWritten = 0L
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)

            logger.debug("About to open output stream...")
            val outputStream = dest.openOutputStream(false)
            logger.debug("openOutputStream result: ${outputStream != null}")

            if (outputStream == null) {
                val errorMsg = "Failed to open output stream for file: $dest"
                logger.error("ERROR: $errorMsg")
                throw IOException(errorMsg)
            }

            outputStream.use { fos ->
                inputStream.use { input ->
                    var bytesRead: Int
                    var lastProgressLog = System.nanoTime()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
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
            }

            logger.info("Download completed, total bytes written: $totalWritten")
            return null  // MD5 validation skipped for streaming download
        } catch (e: Exception) {
            logger.error("Stream download failed: ${e.message}")
            logger.debug("Stack trace:", e)
            throw e
        } finally {
            try {
                inputStream?.close()
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Common chunk download logic used by both retry and non-retry versions.
     */
    private suspend fun downloadChunkInternal(
        urlString: String,
        authV: String,
        chunk: ChunkState,
        destFile: IPlatformFile,
        isPaused: suspend () -> Boolean,
        onProgress: suspend (bytesRead: Long) -> Unit,
        log: (String) -> Unit,
    ): Pair<Long, ChunkStatus> {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var bytesDownloaded = 0L

        try {
            connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", authV)
            connection.setRequestProperty("User-Agent", "SMART 2.0")
            connection.setRequestProperty("Range", "bytes=${chunk.startByte}-${chunk.endByte}")
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = 0
            connection.instanceFollowRedirects = true

            log("Start: ${chunk.startByte}, End: ${chunk.endByte}, Size: ${chunk.endByte - chunk.startByte + 1}")
            log("Response code: ${connection.responseCode}, content length: ${connection.contentLength}")

            if (connection.responseCode >= 400) {
                throw IOException("HTTP error: ${connection.responseCode} ${connection.responseMessage}")
            }

            inputStream = connection.inputStream

            val contentLength = connection.contentLength
            log("Content length from connection: $contentLength")

            val outputStream = destFile.openOutputStream(false)
                ?: throw IOException("Failed to open output stream for chunk ${chunk.chunkId}")

            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
            var bytesRead: Int
            var lastProgress = 0L

            outputStream.use { fos ->
                inputStream.use { input ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
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
            }

            if (bytesDownloaded > lastProgress) {
                onProgress(bytesDownloaded - lastProgress)
            }

            log("Completed, downloaded $bytesDownloaded bytes, file size: ${destFile.getLength()}")
            return Pair(bytesDownloaded, ChunkStatus.COMPLETED)
        } catch (e: Exception) {
            log("Failed: ${e.message}")
            log("Stack trace: ${e.stackTraceToString()}")
            return Pair(bytesDownloaded, ChunkStatus.FAILED)
        } finally {
            try {
                inputStream?.close()
                connection?.disconnect()
            } catch (_: Exception) {}
        }
    }

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
        val chunkLogger = LoggerFactory.getLogger("FusClient.Chunk${chunk.chunkId}")

        var retryCount = 0

        while (retryCount <= retryPolicy.maxRetries) {
            if (retryCount > 0) {
                val currentAuthV = getAuthV(cloud = true)
                val (downloaded, status) = downloadChunkInternal(
                    urlString = urlString,
                    authV = currentAuthV,
                    chunk = chunk,
                    destFile = destFile,
                    isPaused = isPaused,
                    onProgress = onProgress,
                    log = { msg ->
                        when {
                            msg.contains("Failed", ignoreCase = true) || msg.contains("Error", ignoreCase = true) ->
                                chunkLogger.error(msg)
                            msg.contains("Retrying", ignoreCase = true) ->
                                chunkLogger.info(msg)
                            else ->
                                chunkLogger.info(msg)
                        }
                    }
                )

                if (status == ChunkStatus.COMPLETED) {
                    return chunk.copy(
                        downloadedBytes = downloaded,
                        status = ChunkStatus.COMPLETED,
                    )
                }

                retryCount++
                if (retryCount <= retryPolicy.maxRetries) {
                    val delay = retryPolicy.getDelayForAttempt(retryCount - 1)
                    chunkLogger.info("Retrying after ${delay}ms (attempt $retryCount/${retryPolicy.maxRetries})")
                    kotlinx.coroutines.delay(delay)
                } else {
                    chunkLogger.error("Max retries exceeded, marking chunk as failed")
                    return chunk.copy(
                        downloadedBytes = downloaded,
                        status = ChunkStatus.FAILED,
                    )
                }
            } else {
                val connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", getAuthV(cloud = true))
                connection.setRequestProperty("User-Agent", "SMART 2.0")
                connection.setRequestProperty("Range", "bytes=${chunk.startByte}-${chunk.endByte}")
                connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                connection.readTimeout = 0
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode

                chunkLogger.info("Start: ${chunk.startByte}, End: ${chunk.endByte}, Size: ${chunk.endByte - chunk.startByte + 1}")
                chunkLogger.info("Response code: $responseCode")

                if (responseCode == 401) {
                    chunkLogger.warn("Auth error (401), refreshing nonce...")
                    connection.disconnect()
                    onAuthError()
                    retryCount++
                    continue
                }

                if (responseCode >= 400) {
                    throw IOException("HTTP error: $responseCode ${connection.responseMessage}")
                }

                val (downloaded, status) = downloadChunkInternal(
                    urlString = urlString,
                    authV = getAuthV(cloud = true),
                    chunk = chunk,
                    destFile = destFile,
                    isPaused = isPaused,
                    onProgress = onProgress,
                    log = { msg ->
                        when {
                            msg.contains("Failed", ignoreCase = true) || msg.contains("Error", ignoreCase = true) ->
                                chunkLogger.error(msg)
                            else ->
                                chunkLogger.info(msg)
                        }
                    }
                )

                if (status == ChunkStatus.COMPLETED) {
                    return chunk.copy(
                        downloadedBytes = downloaded,
                        status = ChunkStatus.COMPLETED,
                    )
                }

                retryCount++
                if (retryCount <= retryPolicy.maxRetries) {
                    val delay = retryPolicy.getDelayForAttempt(retryCount - 1)
                    chunkLogger.info("Retrying after ${delay}ms (attempt $retryCount/${retryPolicy.maxRetries})")
                    kotlinx.coroutines.delay(delay)
                } else {
                    chunkLogger.error("Max retries exceeded, marking chunk as failed")
                    return chunk.copy(
                        downloadedBytes = downloaded,
                        status = ChunkStatus.FAILED,
                    )
                }
            }
        }

        return chunk.copy(status = ChunkStatus.FAILED)
    }

    @Deprecated("Use downloadSingleChunkWithRetry instead", ReplaceWith("downloadSingleChunkWithRetry"))
    private suspend fun downloadSingleChunk(
        urlString: String,
        authV: String,
        chunk: ChunkState,
        destFile: IPlatformFile,
        isPaused: suspend () -> Boolean,
        onProgress: suspend (bytesRead: Long) -> Unit,
    ): ChunkState {
        val chunkLogger = LoggerFactory.getLogger("FusClient.Chunk${chunk.chunkId}")

        val (downloaded, status) = downloadChunkInternal(
            urlString = urlString,
            authV = authV,
            chunk = chunk,
            destFile = destFile,
            isPaused = isPaused,
            onProgress = onProgress,
            log = { msg ->
                when {
                    msg.contains("Failed", ignoreCase = true) || msg.contains("Error", ignoreCase = true) ->
                        chunkLogger.error(msg)
                    else ->
                        chunkLogger.info(msg)
                }
            }
        )

        return chunk.copy(
            downloadedBytes = downloaded,
            status = status,
        )
    }

    /**
     * Merge all chunk files into the final destination file.
     */
    private suspend fun mergeChunkFiles(
        chunks: List<ChunkState>,
        chunkFileProvider: (Int) -> IPlatformFile?,
        dest: IPlatformFile,
    ): Boolean = withContext(Dispatchers.IO) {
        val outputStream = dest.openOutputStream(false) ?: return@withContext false

        try {
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
            logger.error("Failed to merge chunk files: ${e.message}")
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
        logger.info("=== Chunked Download Start ===")
        logger.info("File: $fileName, Size: $size, Threads: $parallelThreads")

        val overallStartTime = System.currentTimeMillis()

        val url = getDownloadUrl(fileName)
        val chunkSize = ChunkSizeCalculator.calculate(size)

        logger.info("Calculated chunk size: $chunkSize bytes")

        val existingState = DownloadStateManager.loadState(firmwareId)
        if (existingState != null && NoncePolicy.isNonceExpired(existingState.downloadStartTime)) {
            logger.warn("Nonce expired (age: ${NoncePolicy.getNonceAge(existingState.downloadStartTime)}ms), refreshing...")
            onNonceRefresh()
            logger.info("Nonce refreshed successfully")
        }

        if (dest.getLength() >= size) {
            logger.info("File already fully downloaded (${dest.getLength()} >= $size), skipping")
            return null
        }

        logger.info("Starting chunked download for file: $fileName, size: $size, dest: ${dest.getAbsolutePath()}")

        val destName = dest.getName()

        val chunkDirPath = java.io.File(destDir.getAbsolutePath(), ".bifrost_chunks")
        if (!chunkDirPath.exists()) {
            chunkDirPath.mkdirs()
        }
        val chunkDir = destDir.child(".bifrost_chunks", true)
            ?: throw IOException("Failed to create chunk temp directory")
        logger.debug("Chunk dir: ${chunkDir.getAbsolutePath()}, exists on disk: ${chunkDirPath.exists()}")

        fun getChunkFile(chunkId: Int): IPlatformFile? {
            return chunkDir.child("${destName}.chunk_${chunkId}.tmp", false)
        }

        val initialState = existingState?.let { state ->
            logger.info("Resuming existing download, completed chunks: ${state.completedChunks}/${state.totalChunks}")
            state
        } ?: run {
            logger.info("Starting new download")
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

        val localStateMutex = Mutex()
        var currentState = initialState
        val totalDownloaded = atomic(initialState.downloadedBytes)
        val startTime = System.nanoTime()
        var lastSaveBytes = totalDownloaded.value
        val progressUpdateMutex = Mutex()
        var lastProgressUpdateTime = 0L
        val minProgressUpdateIntervalMs = 500L

        suspend fun updateProgress() {
            val now = System.currentTimeMillis()
            if (now - lastProgressUpdateTime < minProgressUpdateIntervalMs) {
                return
            }

            if (progressUpdateMutex.tryLock()) {
                try {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProgressUpdateTime < minProgressUpdateIntervalMs) {
                        return
                    }
                    lastProgressUpdateTime = currentTime

                    val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
                    val currentDownloaded = totalDownloaded.value
                    val bps = if (elapsed > 0) (currentDownloaded * 1000.0 / elapsed).toLong() else 0L
                    progressCallback(currentDownloaded, size, bps)
                } finally {
                    progressUpdateMutex.unlock()
                }
            }
        }

        suspend fun saveProgressIfNeeded() {
            val currentDownloaded = totalDownloaded.value
            if (currentDownloaded - lastSaveBytes >= ProgressUpdateInterval) {
                localStateMutex.withLock {
                    DownloadStateManager.saveState(currentState)
                }
                lastSaveBytes = totalDownloaded.value
            }
        }

        suspend fun onChunkProgress(chunkId: Int, bytesDelta: Long) {
            totalDownloaded.addAndGet(bytesDelta)
            localStateMutex.withLock {
                val chunks = currentState.chunks.toMutableList()
                val chunkIndex = chunks.indexOfFirst { it.chunkId == chunkId }
                if (chunkIndex >= 0) {
                    val chunk = chunks[chunkIndex]
                    chunks[chunkIndex] = chunk.copy(downloadedBytes = chunk.downloadedBytes + bytesDelta)
                    currentState = currentState.copy(
                        downloadedBytes = totalDownloaded.value,
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

        logger.info("Pending chunks: ${pendingChunks.size}, total chunks: ${currentState.chunks.size}")
        logger.debug("Chunk directory: ${chunkDir.getAbsolutePath()}")
        logger.debug("Dest file: ${dest.getAbsolutePath()}, dest name: $destName")

        if (pendingChunks.isEmpty()) {
            logger.info("All chunks already downloaded, merging...")
        } else {
            val chunkIterator = pendingChunks.iterator()
            val mutex = Mutex()
            val nonceRefreshMutex = Mutex()
            var lastNonceRefreshTime = 0L
            val minNonceRefreshInterval = MIN_NONCE_REFRESH_INTERVAL_MS

            suspend fun refreshNonceIfNeeded(): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastNonceRefreshTime < minNonceRefreshInterval) {
                    return true
                }

                if (nonceRefreshMutex.tryLock()) {
                    try {
                        if (System.currentTimeMillis() - lastNonceRefreshTime < minNonceRefreshInterval) {
                            return true
                        }
                        logger.info("Refreshing nonce (global)...")
                        onNonceRefresh()
                        lastNonceRefreshTime = System.currentTimeMillis()
                        logger.info("Nonce refreshed successfully (global)")
                        return true
                    } finally {
                        nonceRefreshMutex.unlock()
                    }
                } else {
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

                            logger.info("Thread $threadId starting chunk ${chunk.chunkId}")

                            val chunkFile = getChunkFile(chunk.chunkId) ?: continue
                            logger.debug("Chunk file ${chunk.chunkId}: ${chunkFile.getAbsolutePath()}")

                            val authErrorCallback: suspend () -> Unit = {
                                logger.warn("Auth error detected in chunk ${chunk.chunkId}, triggering global nonce refresh...")
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
                            logger.info("Chunk ${chunk.chunkId} result: status=${result.statusString}, downloaded=${result.downloadedBytes}")

                            localStateMutex.withLock {
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

        localStateMutex.withLock {
            DownloadStateManager.saveState(currentState)
        }

        val failedChunks = currentState.chunks.filter { it.status == ChunkStatus.FAILED }
        if (failedChunks.isNotEmpty()) {
            logger.error("FAILED: ${failedChunks.size} chunks failed")
            throw IOException("Download failed: ${failedChunks.size} chunks failed")
        }

        logger.info("All chunks downloaded, merging ${currentState.chunks.size} chunks into ${dest.getAbsolutePath()}...")
        logger.debug("Chunk sizes: ${currentState.chunks.sortedBy { it.chunkId }.joinToString(", ") { "chunk${it.chunkId}=${it.downloadedBytes}b" }}")
        val mergeStartTime = System.currentTimeMillis()
        val mergeSuccess = mergeChunkFiles(
            chunks = currentState.chunks,
            chunkFileProvider = { getChunkFile(it) },
            dest = dest,
        )
        val mergeDuration = System.currentTimeMillis() - mergeStartTime
        logger.info("Merge complete in ${mergeDuration}ms (${mergeDuration / 1000.0}s), final file size: ${dest.getLength()}")

        if (!mergeSuccess) {
            throw IOException("Failed to merge chunk files")
        }

        logger.info("Cleaning up chunk files...")
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
        logger.debug("Cleanup complete in ${cleanupDuration}ms (${cleanupDuration / 1000.0}s)")

        localStateMutex.withLock {
            currentState = currentState.copy(
                currentStage = DownloadStage.CRC_CHECKING,
                downloadedBytes = size,
                lastUpdateTime = System.currentTimeMillis(),
            )
            DownloadStateManager.saveState(currentState)
        }

        val overallDuration = System.currentTimeMillis() - overallStartTime
        logger.info("=== Chunked Download Complete ===")
        logger.info("Overall duration: ${overallDuration}ms (${overallDuration / 1000.0}s)")

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
        logger.debug("downloadFile called for file: $fileName, size: $size, start: $start")

        val url = getDownloadUrl(fileName)
        logger.debug("downloadUrl: $url")

        val authV = getAuthV(cloud = true)
        logger.debug("authV: ${authV.take(50)}...")

        // Skip MD5 probe to avoid memory issues with large files
        logger.debug("Skipping MD5 probe for large file...")
        val md5: String? = null

        if (dest.getLength() >= size) {
            return md5
        }

        logger.debug("Using streaming download with HttpURLConnection...")

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