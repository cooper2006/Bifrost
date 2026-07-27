@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

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
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.globalHttpClient

/**
 * 管理与三星服务器的通信。
 */
object FusClient : IFusClient<FusClient.Request> {
    enum class Request(val value: String, val cloud: Boolean) : IFusClient.IRequest {
        GENERATE_NONCE("NF_SmartDownloadGenerateNonce.do", false),
        BINARY_INFORM("NF_SmartDownloadBinaryInform.do", false),
        BINARY_INIT("NF_SmartDownloadBinaryInitForMass.do", false),
        HISTORY("SmartHistory.do", false),
    }

    // @Volatile: 这些字段从协程中访问，可能在不同线程上运行。
    // @Volatile 确保跨线程可见性，但复合操作（如 check-then-act）仍需调用方保证顺序。
    @Volatile
    private var nonce = ""

    @Volatile
    private var auth: String = ""
    @Volatile
    private var sessionId: String = ""

    override suspend fun getNonce(): String {
        if (nonce.isBlank()) {
            generateNonce()
        }

        return nonce
    }

    suspend fun refreshNonce() {
        generateNonce()
    }

    override suspend fun generateNonce() {
        BugsnagUtils.addBreadcrumb(
            message = "生成随机数。",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        println("生成随机数。")
        makeReq(Request.GENERATE_NONCE, "", null, true)
        BugsnagUtils.addBreadcrumb(
            message = "随机数: $nonce, 认证: $auth",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        println("随机数: $nonce")
        println("认证: $auth")
    }

    private suspend fun makeSignatureHash(signature: String?): String? {
        if (signature == null) return null

        val hasher = CryptUtils.md5Provider.hasher()
        val a = hasher.hash("auth:$nonce:00000001".toByteArray()).toHexString()
        val b = hasher.hash("interface:$signature".toByteArray()).toHexString()

        return hasher.hash("$a:FUS:$b".toByteArray()).toHexString()
    }

    override suspend fun getAuthV(includeNonce: Boolean, signature: String?, cloud: Boolean): String {
        val hasSignature = !signature.isNullOrBlank()
        val effectiveNonce = when {
            includeNonce && hasSignature -> {
                val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
                CharArray(16) { chars.random() }.joinToString("")
            }
            includeNonce -> nonce
            else -> ""
        }
        return "FUS nonce=\"${if (cloud) effectiveNonce else this.nonce}\", " +
                "signature=\"${makeSignatureHash(signature?.takeIf { !it.isBlank() }) ?: this.auth}\", " +
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
     * @param request 要发送的请求。
     * @param data 需要放入请求中的任何正文数据。
     * @return 响应正文数据，作为文本。通常是XML。
     */
    override suspend fun makeReq(
        request: Request,
        data: String,
        signature: String?,
        includeNonce: Boolean,
    ): String = makeReqWithRetry(request, data, signature, includeNonce, retryCount = 0)

    private suspend fun makeReqWithRetry(request: Request, data: String, signature: String?, includeNonce: Boolean, retryCount: Int): String {
        println("[BifrostDownload] makeReq start: request=${request.value}, cloud=${request.cloud}, dataLen=${data.length}, hasSig=${signature != null}, retry=$retryCount")
        if (nonce.isBlank() && request != Request.GENERATE_NONCE) {
            println("[BifrostDownload] makeReq: nonce blank, generating...")
            generateNonce()
        }

        val authV = getAuthV(cloud = request.cloud, signature = signature)
        val url = "https://neofussvr.sslcs.cdngc.net/${request.value}"
        println("[BifrostDownload] makeReq: POST $url, sessionId=${sessionId.take(8)}...")

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
        println("[BifrostDownload] makeReq: response status=${response.status.value}")

        val body = response.bodyAsText()
        println("[BifrostDownload] makeReq: body length=${body.length}, snippet=${body.take(120).replace("\n", " ")}")

        if (request != Request.GENERATE_NONCE && response.is401(body)) {
            if (retryCount >= 3) {
                println("[BifrostDownload] makeReq: 401 after $retryCount retries, giving up")
                throw RuntimeException("认证持续失败（重试 $retryCount 次后仍为 401）")
            }
            println("[BifrostDownload] makeReq: got 401, regenerating nonce and retrying (${retryCount + 1}/3)")
            generateNonce()

            return makeReqWithRetry(request, data, signature, includeNonce, retryCount + 1)
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
                println("生成随机数时出错。")
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
        println("[BifrostDownload] downloadFile start: fileName=$fileName, start=$start, size=${size}bytes (${size / (1024 * 1024)}MB), dest=${dest.getAbsolutePath()}")
        val url = getDownloadUrl(fileName)
        println("[BifrostDownload] downloadFile: url=$url")

        // 单线程 Ktor 流式下载，不经过 Ketch（Ketch 内部会先发 HEAD 消耗 auth）
        println("[BifrostDownload] downloadFile: using Ktor single-thread streaming mode, size=${size / (1024 * 1024)}MB")

        val maxAuthRetries = 3
        var authRetries = 0
        var downloadedBytes = start
        val buffer = ByteArray(64 * 1024) // 64KB buffer
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
                        socketTimeoutMillis = 60_000L  // 60秒无数据则超时，避免连接静默断开后无限阻塞
                        connectTimeoutMillis = 30_000L
                    }
                }.execute { response ->
                    println("[BifrostDownload] downloadFile: GET response status=${response.status.value}")

                    if (response.status.value == 401) {
                        throw RuntimeException("HTTP 401: Unauthorized")
                    }
                    if (response.status.value != 200 && response.status.value != 206) {
                        throw RuntimeException("下载失败，状态码: ${response.status.value}")
                    }

                    val channel = response.bodyAsChannel()
                    val outputStream = dest.openOutputStream(true)!!

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

                            // 进度回调节流，每 500ms 触发一次
                            if (now - lastProgressTime > 500) {
                                progressCallback(downloadedBytes, size, bps)
                                lastProgressTime = now
                            }

                            // 每 5 秒打印一次下载进度日志
                            if (now - lastLogTime > 5000) {
                                val pct = if (size > 0) String.format("%.1f", downloadedBytes * 100.0 / size) else "0"
                                println("[BifrostDownload] progress: ${downloadedBytes / (1024 * 1024)}MB / ${size / (1024 * 1024)}MB ($pct%), bps=${bps / 1024}KB/s")
                                lastLogTime = now
                            }
                        }
                    } finally {
                        outputStream.flush()
                        outputStream.close()
                    }

                    // 最终进度回调
                    val elapsedTotal = (System.currentTimeMillis() - startTime) / 1000.0
                    val bps = if (elapsedTotal > 0) (downloadedBytes / elapsedTotal).toLong() else 0L
                    progressCallback(downloadedBytes, size, bps)

                    println("[BifrostDownload] downloadFile: done: ${downloadedBytes / (1024 * 1024)}MB in ${String.format("%.1f", elapsedTotal)}s, bps=${bps / 1024}KB/s")
                }

                return null
            } catch (e: CancellationException) {
                println("[BifrostDownload] downloadFile: cancelled at ${downloadedBytes / (1024 * 1024)}MB")
                throw e
            } catch (e: Exception) {
                val isAuth = e.message?.contains("401") == true
                if (isAuth && onAuthRefresh != null && authRetries < maxAuthRetries) {
                    authRetries++
                    println("[BifrostDownload] downloadFile: 401 during download, calling onAuthRefresh ($authRetries/$maxAuthRetries)")
                    onAuthRefresh.invoke()
                    continue
                }
                println("[BifrostDownload] downloadFile: failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }
}
