@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.tools

import com.fleeksoft.io.exception.ArrayIndexOutOfBoundsException
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.zwander.common.util.BifrostLogger
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.globalHttpClient
import tk.zwander.common.util.retryWithBackoff

/**
 * Manage communications with Samsung's server (legacy protocol).
 *
 * nonce/encNonce/auth/sessionId 的读写通过 [authMutex] 保护，
 * 确保多协程下的 check-then-act 操作是原子的。
 */
object FusClientLegacy : IFusClient<FusClientLegacy.Request> {
    enum class Request(val value: String) : IFusClient.IRequest {
        GENERATE_NONCE("NF_DownloadGenerateNonce.do"),
        BINARY_INFORM("NF_DownloadBinaryInform.do"),
        BINARY_INIT("NF_DownloadBinaryInitForMass.do")
    }

    private val authMutex = Mutex()

    private var encNonce = ""
    private var nonce = ""
    private var auth: String = ""
    private var sessionId: String = ""

    override suspend fun getNonce(): String = authMutex.withLock {
        if (nonce.isBlank()) {
            generateNonceInternal()
        }
        nonce
    }

    override suspend fun generateNonce() {
        authMutex.withLock {
            generateNonceInternal()
        }
    }

    private suspend fun generateNonceInternal() {
        BugsnagUtils.addBreadcrumb(
            message = "Generating nonce.",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        BifrostLogger.general.info("Generating nonce.")
        makeReqInternal(Request.GENERATE_NONCE, "", null, false)
        BugsnagUtils.addBreadcrumb(
            message = "Nonce: $nonce, Auth: $auth",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        BifrostLogger.general.debug("Nonce: $nonce")
        BifrostLogger.general.debug("Auth: $auth")
    }

    override suspend fun getAuthV(includeNonce: Boolean, signature: String?, cloud: Boolean): String {
        return authMutex.withLock {
            "FUS nonce=\"${if (includeNonce) encNonce else ""}\", signature=\"${auth}\", nc=\"\", type=\"\", realm=\"\", newauth=\"1\""
        }
    }

    override suspend fun getDownloadUrl(path: String): String {
        return "http://cloud-neofussvr.samsungmobile.com/NF_DownloadBinaryForMass.do?file=${path}"
    }

    /**
     * Make a request to Samsung, automatically inserting authorization data.
     * 使用 retryWithBackoff 处理 401 重试。
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
            e is RuntimeException && e.message?.contains("认证持续失败") == true
        },
    ) {
        makeReqWithRetryCheck(request, data, signature, includeNonce)
    }

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
        if (nonce.isBlank() && request != Request.GENERATE_NONCE) {
            generateNonceInternal()
        }

        val authV = getAuthV(includeNonce)

        val response =
            globalHttpClient.request("https://neofussvr.sslcs.cdngc.net/${request.value}") {
                method = HttpMethod.Post
                headers {
                    append("Authorization", authV)
                    append("User-Agent", "Kiss2.0_FUS")
                    append("Cookie", "JSESSIONID=${sessionId}")
                    append("Set-Cookie", "JSESSIONID=${sessionId}")
                    append(HttpHeaders.ContentLength, "${data.toByteArray().size}")
                }
                setBody(data)
            }

        val body = response.bodyAsText()

        BifrostLogger.download.debug("Request: $request")

        if (request != Request.GENERATE_NONCE && response.is401(body)) {
            generateNonceInternal()
            throw RuntimeException("认证持续失败（重试后仍为 401）")
        }

        if (response.headers["NONCE"] != null || response.headers["nonce"] != null) {
            try {
                encNonce = response.headers["NONCE"] ?: response.headers["nonce"] ?: ""
                nonce = CryptUtils.Legacy.decryptNonce(encNonce)
                auth = CryptUtils.Legacy.getAuth(nonce)
            } catch (e: ArrayIndexOutOfBoundsException) {
                BugsnagUtils.addBreadcrumb(
                    message = "Error generating nonce.",
                    data = mapOf("error" to e),
                    type = BreadcrumbType.ERROR,
                )
                BifrostLogger.general.error("Error generating nonce.")
            }
        }

        if (response.headers["Set-Cookie"] != null || response.headers["set-cookie"] != null) {
            sessionId = response.headers.entries()
                .find { it.value.any { value -> value.contains("JSESSIONID=") } }
                ?.value?.find {
                    it.contains("JSESSIONID=")
                }
                ?.replace("JSESSIONID=", "")
                ?.replace(Regex(";.*$"), "") ?: sessionId
        }

        return body
    }
}
