@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

import tk.zwander.common.util.BifrostLogger
import com.fleeksoft.io.exception.ArrayIndexOutOfBoundsException
import com.fleeksoft.ksoup.Ksoup
import dev.zwander.kotlin.file.IPlatformFile
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
import io.ktor.utils.io.core.toByteArray
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.firstElementByTagName
import tk.zwander.common.util.globalHttpClient
import tk.zwander.common.util.retryWithBackoff

/**
 * 管理与三星服务器的通信。
 *
 * nonce/auth/sessionId 的读写通过 [authMutex] 保护，确保 check-then-act 操作是原子的。
 * downloadFile 方法不持有 authMutex（大文件下载不应阻塞其他请求），
 * 但下载开始时读取 auth 值是通过 getAuthV() 在锁外获取的快照值。
 */
object FusClient : IFusClient<FusClient.Request> {
    enum class Request(val value: String, val cloud: Boolean) : IFusClient.IRequest {
        GENERATE_NONCE("NF_SmartDownloadGenerateNonce.do", false),
        BINARY_INFORM("NF_SmartDownloadBinaryInform.do", false),
        BINARY_INIT("NF_SmartDownloadBinaryInitForMass.do", false),
        HISTORY("SmartHistory.do", false),
    }

    private val authMutex = Mutex()

    private var nonce = ""
    private var auth: String = ""
    private var sessionId: String = ""

    override suspend fun getNonce(): String = authMutex.withLock {
        if (nonce.isBlank()) {
            generateNonceInternal()
        }
        nonce
    }

    suspend fun refreshNonce() {
        authMutex.withLock {
            generateNonceInternal()
        }
    }

    override suspend fun generateNonce() {
        authMutex.withLock {
            generateNonceInternal()
        }
    }

    /**
     * 内部方法：调用方必须持有 [authMutex]。
     */
    private suspend fun generateNonceInternal() {
        BugsnagUtils.addBreadcrumb(
            message = "生成随机数。",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        BifrostLogger.general.info("生成随机数。")
        makeReqInternal(Request.GENERATE_NONCE, "", null, true)
        BugsnagUtils.addBreadcrumb(
            message = "随机数: $nonce, 认证: $auth",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        BifrostLogger.general.debug("随机数: $nonce")
        BifrostLogger.general.debug("认证: $auth")
    }

    private suspend fun makeSignatureHash(signature: String?): String? {
        if (signature == null) return null

        val snapshotNonce = authMutex.withLock { nonce }
        val hasher = CryptUtils.md5Provider.hasher()
        val a = hasher.hash("auth:$snapshotNonce:00000001".toByteArray()).toHexString()
        val b = hasher.hash("interface:$signature".toByteArray()).toHexString()

        return hasher.hash("$a:FUS:$b".toByteArray()).toHexString()
    }

    override suspend fun getAuthV(includeNonce: Boolean, signature: String?, cloud: Boolean): String {
        val snapshotNonce = authMutex.withLock { nonce }
        val snapshotAuth = authMutex.withLock { auth }

        val hasSignature = !signature.isNullOrBlank()
        val effectiveNonce = when {
            includeNonce && hasSignature -> {
                val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
                CharArray(16) { chars.random() }.joinToString("")
            }
            includeNonce -> snapshotNonce
            else -> ""
        }
        return "FUS nonce=\"${if (cloud) effectiveNonce else snapshotNonce}\", " +
                "signature=\"${makeSignatureHash(signature?.takeIf { !it.isBlank() }) ?: snapshotAuth}\", " +
                "nc=\"${if (hasSignature) "00000001" else ""}\", " +
                "type=\"${if (hasSignature) "auth" else ""}\", " +
                "realm=\"${if (hasSignature) "interface" else ""}\""
    }

    override suspend fun getDownloadUrl(path: String): String {
        // 注意：三星 FUS 下载服务器仅支持 HTTP（不支持 HTTPS）。
        // Authorization header 中的认证信息将以明文传输，这是协议限制。
        return "http://cloud-neofussvr.samsungmobile.com/NF_SmartDownloadBinaryForMass.do?file=${path}"
    }

    /**
     * 向三星发送请求，自动插入授权数据。
     * 使用 retryWithBackoff 处理 401 重试，而非手写递归。
     */
    override suspend fun makeReq(
        request: Request,
        data: String,
        signature: String?,
        includeNonce: Boolean,
    ): String = retryWithBackoff<String>(
        maxRetries = 3,
        initialDelay = 500L,
        retryable = { e ->
            // 只有 401 相关的异常才重试
            e is RuntimeException && e.message?.contains("认证持续失败") == true
        },
    ) {
        makeReqWithRetryCheck(request, data, signature, includeNonce)
    }

    /**
     * 执行一次请求，如果收到 401 则抛出异常让 retryWithBackoff 处理。
     * 内部持有 [authMutex] 保护状态读写。
     */
    private suspend fun makeReqWithRetryCheck(
        request: Request,
        data: String,
        signature: String?,
        includeNonce: Boolean,
    ): String = authMutex.withLock {
        makeReqInternal(request, data, signature, includeNonce)
    }

    /**
     * 内部方法：调用方必须持有 [authMutex]。
     */
    private suspend fun makeReqInternal(
        request: Request,
        data: String,
        signature: String?,
        includeNonce: Boolean,
    ): String {
        BifrostLogger.download.info("makeReq start: request=${request.value}, cloud=${request.cloud}, dataLen=${data.length}, hasSig=${signature != null}")
        if (nonce.isBlank() && request != Request.GENERATE_NONCE) {
            BifrostLogger.download.info("makeReq: nonce blank, generating...")
            generateNonceInternal()
        }

        val authV = getAuthV(cloud = request.cloud, signature = signature)
        val url = "https://neofussvr.sslcs.cdngc.net/${request.value}"
        BifrostLogger.download.info("makeReq: POST $url, sessionId=${sessionId.take(8)}...")

        val response =
            globalHttpClient.request(url) {
                method = HttpMethod.Post
                headers {
                    append("Authorization", authV)
                    append("User-Agent", "SMART 2.0")
                    append("Cookie", "JSESSIONID=${sessionId};SESSION=${sessionId}")
                    append(HttpHeaders.ContentLength, "${data.toByteArray().size}")
                }
                setBody(data)
            }
        BifrostLogger.download.info("makeReq: response status=${response.status.value}")

        val body = response.bodyAsText()
        BifrostLogger.download.info("makeReq: body length=${body.length}, snippet=${body.take(120).replace("\n", " ")}")

        if (request != Request.GENERATE_NONCE && response.is401(body)) {
            BifrostLogger.download.info("makeReq: got 401, regenerating nonce")
            generateNonceInternal()
            throw RuntimeException("认证持续失败（重试后仍为 401）")
        }

        if (response.headers["NONCE"] != null || response.headers["nonce"] != null) {
            try {
                nonce = response.headers["NONCE"] ?: response.headers["nonce"] ?: ""

                try {
                    auth = CryptUtils.decryptNonce(nonce.take(16).padEnd(16, '0'))
                } catch (_: Exception) {}
            } catch (e: ArrayIndexOutOfBoundsException) {
                BugsnagUtils.addBreadcrumb(
                    message = "生成随机数时出错。",
                    data = mapOf("error" to e),
                    type = BreadcrumbType.ERROR,
                )
                BifrostLogger.general.error("生成随机数时出错。", e)
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

    /**
     * 从三星服务器下载文件（单线程流式下载）。
     * 直接使用 Ktor HTTP 客户端，避免 Ketch 库在下载前自动发 HEAD 请求消耗 auth。
     *
     * @param fileName 要下载的文件名。
     * @param start 可选的偏移量。用于恢复下载。
     * @param size 文件大小
     * @param dest 目标文件
     * @param progressCallback 进度回调
     * @param onAuthRefresh 授权刷新回调，在 401 时调用以重新建立下载会话（如 BinaryInit）
     */
    @OptIn(InternalAPI::class)
    suspend fun downloadFile(
        fileName: String,
        start: Long,
        size: Long,
        dest: IPlatformFile,
        onAuthRefresh: (suspend () -> Unit)? = null,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
    ): String? {
        BifrostLogger.download.info("downloadFile start: fileName=$fileName, start=$start, size=${size}bytes (${size / (1024 * 1024)}MB), dest=${dest.getAbsolutePath()}")
        val url = getDownloadUrl(fileName)
        BifrostLogger.download.info("downloadFile: url=$url")

        BifrostLogger.download.info("downloadFile: using Ktor single-thread streaming mode, size=${size / (1024 * 1024)}MB")

        val maxAuthRetries = 3
        var authRetries = 0
        var downloadedBytes = start
        val buffer = ByteArray(64 * 1024)
        val startTime = System.currentTimeMillis()

        while (true) {
            val authV = getAuthV(cloud = true)

            try {
                globalHttpClient.prepareRequest {
                    method = HttpMethod.Get
                    url(url)
                    headers {
                        append("Authorization", authV)
                        append("User-Agent", "SMART 2.0")
                        append("Cache-Control", "no-cache")
                        if (downloadedBytes > 0) {
                            append("Range", "bytes=$downloadedBytes-")
                        }
                    }
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = 60_000L
                        connectTimeoutMillis = 30_000L
                    }
                }.execute { response ->
                    BifrostLogger.download.info("downloadFile: GET response status=${response.status.value}")

                    if (response.status.value == 401) {
                        throw tk.zwander.common.exceptions.AuthExpiredException()
                    }
                    if (response.status.value != 200 && response.status.value != 206) {
                        throw RuntimeException("下载失败，状态码: ${response.status.value}")
                    }

                    val channel = response.bodyAsChannel()
                    val outputStream = dest.openOutputStream(true)
                        ?: throw java.io.IOException("无法打开输出流: ${dest.getAbsolutePath()}")

                    try {
                        var lastProgressTime = startTime
                        var lastLogTime = startTime

                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead <= 0) break

                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = (now - startTime) / 1000.0
                            val bps = if (elapsed > 0) (downloadedBytes / elapsed).toLong() else 0L

                            if (now - lastProgressTime > 500) {
                                progressCallback(downloadedBytes, size, bps)
                                lastProgressTime = now
                            }

                            if (now - lastLogTime > 5000) {
                                val pct = if (size > 0) String.format("%.1f", downloadedBytes * 100.0 / size) else "0"
                                BifrostLogger.download.info("progress: ${downloadedBytes / (1024 * 1024)}MB / ${size / (1024 * 1024)}MB ($pct%), bps=${bps / 1024}KB/s")
                                lastLogTime = now
                            }
                        }
                    } finally {
                        outputStream.flush()
                        outputStream.close()
                    }

                    val elapsedTotal = (System.currentTimeMillis() - startTime) / 1000.0
                    val bps = if (elapsedTotal > 0) (downloadedBytes / elapsedTotal).toLong() else 0L
                    progressCallback(downloadedBytes, size, bps)

                    BifrostLogger.download.info("downloadFile: done: ${downloadedBytes / (1024 * 1024)}MB in ${String.format("%.1f", elapsedTotal)}s, bps=${bps / 1024}KB/s")
                }

                return null
            } catch (e: CancellationException) {
                BifrostLogger.download.info("downloadFile: cancelled at ${downloadedBytes / (1024 * 1024)}MB")
                throw e
            } catch (e: Exception) {
                val isAuth = e is tk.zwander.common.exceptions.AuthExpiredException
                if (isAuth && onAuthRefresh != null && authRetries < maxAuthRetries) {
                    authRetries++
                    BifrostLogger.download.info("downloadFile: 401 during download, calling onAuthRefresh ($authRetries/$maxAuthRetries)")
                    onAuthRefresh.invoke()
                    continue
                }
                BifrostLogger.download.info("downloadFile: failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }
}
