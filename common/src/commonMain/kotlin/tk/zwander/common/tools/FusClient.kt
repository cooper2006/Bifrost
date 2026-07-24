@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

import com.fleeksoft.io.exception.ArrayIndexOutOfBoundsException
import com.fleeksoft.ksoup.Ksoup
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import dev.zwander.kotlin.file.IPlatformFile
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.io.InternalIoApi
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.firstElementByTagName
import tk.zwander.common.util.globalHttpClient
import tk.zwander.common.util.ketch

/**
 * 管理与三星服务器的通信。
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
            message = "生成随机数。",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        println("生成随机数。")
        makeReq(Request.GENERATE_NONCE)
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
     * 向三星发送请求，自动插入授权数据。
     * @param request 要发送的请求。
     * @param data 需要放入请求中的任何正文数据。
     * @return 响应正文数据，作为文本。通常是XML。
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
     * 从三星服务器下载文件。
     * @param fileName 要下载的文件名。
     * @param start 可选的偏移量。用于恢复下载。
     * @param size 文件大小
     * @param dest 目标文件
     * @param progressCallback 进度回调
     */
    @OptIn(InternalAPI::class, InternalIoApi::class)
    suspend fun downloadFile(
        fileName: String,
        start: Long = 0,
        size: Long,
        dest: IPlatformFile,
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
    ): String? {
        val url = getDownloadUrl(fileName)

        // 获取 Content-MD5。
        val authV = getAuthV(cloud = true)

        val md5 = globalHttpClient.prepareRequest {
            method = HttpMethod.Head
            url(url)
            headers {
                append("Authorization", authV)
                append("User-Agent", "SMART 2.0")
                if (start > 0) {
                    append("Range", "bytes=${start}-")
                }
            }
            timeout {
                this.requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                this.socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                this.connectTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            response.headers["Content-MD5"]
        }

        // 根据文件大小决定是否使用并行下载
        val useParallelDownload = size > 100L * 1024 * 1024  // 大于100MB使用并行下载
        
        if (useParallelDownload) {
            println("使用并行下载模式，文件大小: ${size / (1024 * 1024)}MB")
            return ParallelDownloader.downloadFile(
                fileName = fileName,
                start = start,
                size = size,
                dest = dest,
                progressCallback = progressCallback,
                authV = authV,
            )
        }

        val task = ketch.tasks.value.find { it.request.url == url }
            ?.let { download ->
                download.resume(Destination(dest.getAbsolutePath()))
                download.takeIf {
                    it.state.value !is DownloadState.Completed
                }
            } ?: ketch.download(
            DownloadRequest(
                url = url,
                destination = Destination(dest.getAbsolutePath()),
                headers = mapOf(
                    "Authorization" to authV,
                    "User-Agent" to "SMART 2.0",
                    "Cache-Control" to "no-cache",
                ),
            ),
        )

        CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
            task.state.collect {
                if (it is DownloadState.Downloading) {
                    progressCallback(
                        it.progress.downloadedBytes,
                        size,
                        it.progress.bytesPerSecond,
                    )
                }
            }
        }

        try {
            // 有界重试：Ketch 可能内部重试，但防止无限循环
            var ketchRetries = 0
            val maxKetchRetries = 3

            while (ketchRetries <= maxKetchRetries) {
                val result = task.await()

                if (result.isSuccess) {
                    break
                }

                (result.exceptionOrNull() as? KetchError)?.let { error ->
                    if (!error.isRetryable) {
                        throw error
                    }
                }

                ketchRetries++
            }

            if (ketchRetries > maxKetchRetries) {
                throw RuntimeException("下载重试次数超限")
            }
        } catch (_: CancellationException) {
            task.pause()
        }

        return md5
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
