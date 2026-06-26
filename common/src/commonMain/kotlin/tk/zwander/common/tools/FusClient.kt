@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

import com.fleeksoft.io.exception.ArrayIndexOutOfBoundsException
import com.fleeksoft.ksoup.Ksoup
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.DEFAULT_CHUNK_SIZE
import tk.zwander.common.util.firstElementByTagName
import tk.zwander.common.util.globalHttpClient
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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
    private const val DownloadChunkCount = 1

    /** Max retries per chunk on non-auth failure. */
    private const val MaxChunkRetries = 3

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
            connection = URL(urlString).openConnection() as HttpURLConnection
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
            
            dest.openOutputStream(false)?.use { outputStream ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // Check pause state
                    while (isPaused()) {
                        kotlinx.coroutines.delay(100)
                    }
                    
                    outputStream.write(buffer, 0, bytesRead)
                    totalWritten += bytesRead
                    
                    // Report progress
                    val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
                    val bps = if (elapsed > 0) (totalWritten * 1000.0 / elapsed).toLong() else 0L
                    progressCallback(totalWritten, size, bps)
                }
            }
            
            log("DEBUG: Download completed, total bytes: $totalWritten")
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
    suspend fun downloadFile(
        fileName: String,
        start: Long = 0,
        size: Long,
        dest: IPlatformFile,
        isPaused: suspend () -> Boolean = { false },
        progressCallback: suspend (current: Long, max: Long, bps: Long) -> Unit,
    ): String? {
        val logFile = java.io.File(System.getProperty("user.home"), "bifrost_fusclient_debug.log")
        
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