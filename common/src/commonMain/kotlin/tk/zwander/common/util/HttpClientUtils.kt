@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package tk.zwander.common.util

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.engine.KtorHttpEngine
import com.linroid.ketch.sqlite.DriverFactory
import com.linroid.ketch.sqlite.createSqliteTaskStore
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig

val globalHttpClient: HttpClient = HttpClient(CIO) {
    this.followRedirects = true
    this.expectSuccess = false

    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
    }

    engine {
        endpoint {
            maxConnectionsPerRoute = 16
            keepAliveTime = 10000
            connectTimeout = 15000
        }
    }
}

val ketch: Ketch = Ketch(
    httpEngine = KtorHttpEngine(globalHttpClient.config {
        install(HttpTimeout) {
            // Ketch 负责自己的重试和断连恢复，这里给足够大的上限而非无限
            socketTimeoutMillis = 60_000L
            requestTimeoutMillis = 86_400_000L
            connectTimeoutMillis = 30_000L
        }
    }),
    config = DownloadConfig(
        maxConnectionsPerDownload = 8,
    ),
    taskStore = createSqliteTaskStore(ketchDb),
    logger = Logger.console(minLevel = LogLevel.WARN),
)

expect val ketchDb: DriverFactory
