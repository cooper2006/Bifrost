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
import tk.zwander.common.util.BreadcrumbType
import tk.zwander.common.util.BugsnagUtils
import tk.zwander.common.util.globalHttpClient

/**
 * Manage communications with Samsung's server.
 */
object FusClientLegacy : IFusClient<FusClientLegacy.Request> {
    enum class Request(val value: String) : IFusClient.IRequest {
        GENERATE_NONCE("NF_DownloadGenerateNonce.do"),
        BINARY_INFORM("NF_DownloadBinaryInform.do"),
        BINARY_INIT("NF_DownloadBinaryInitForMass.do")
    }

    private var encNonce = ""
    private var nonce = ""

    private var auth: String = ""
    private var sessionId: String = ""

    override suspend fun getNonce(): String {
        if (nonce.isBlank()) {
            generateNonce()
        }

        return nonce
    }

    override suspend fun generateNonce() {
        BugsnagUtils.addBreadcrumb(
            message = "Generating nonce.",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        println("Generating nonce.")
        makeReq(Request.GENERATE_NONCE)
        BugsnagUtils.addBreadcrumb(
            message = "Nonce: $nonce, Auth: $auth",
            data = mapOf(),
            type = BreadcrumbType.LOG,
        )
        println("Nonce: $nonce")
        println("Auth: $auth")
    }

    override suspend fun getAuthV(includeNonce: Boolean, signature: String?, cloud: Boolean): String {
        return "FUS nonce=\"${if (includeNonce) encNonce else ""}\", signature=\"${this.auth}\", nc=\"\", type=\"\", realm=\"\", newauth=\"1\""
    }

    override suspend fun getDownloadUrl(path: String): String {
        return "http://cloud-neofussvr.samsungmobile.com/NF_DownloadBinaryForMass.do?file=${path}"
    }

    /**
     * Make a request to Samsung, automatically inserting authorization data.
     * @param request the request to make.
     * @param data any body data that needs to go into the request.
     * @return the response body data, as text. Usually XML.
     */
    override suspend fun makeReq(
        request: Request,
        data: String,
        signature: String?,
        includeNonce: Boolean,
    ): String = makeReqWithRetry(request, data, signature, includeNonce, retryCount = 0)

    private suspend fun makeReqWithRetry(
        request: Request,
        data: String,
        signature: String?,
        includeNonce: Boolean,
        retryCount: Int,
    ): String {
        if (nonce.isBlank() && request != Request.GENERATE_NONCE) {
            generateNonce()
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

        println(request)

        if (request != Request.GENERATE_NONCE && response.is401(body)) {
            // 限制重试次数上限，避免 401 持续返回时无限递归导致栈溢出
            if (retryCount >= 3) {
                throw RuntimeException("认证持续失败（重试 $retryCount 次后仍为 401）")
            }
            generateNonce()

            return makeReqWithRetry(request = request, data = data, signature = signature, includeNonce = includeNonce, retryCount = retryCount + 1)
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
                println("Error generating nonce.")
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