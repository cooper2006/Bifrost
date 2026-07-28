package tk.zwander.common.util

import tk.zwander.common.util.BifrostLogger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SupporterInfo(
    val name: String,
    val link: String
)

class PatreonSupportersParser private constructor() {
    companion object {
        @Volatile
        private var instance: PatreonSupportersParser? = null
        private val lock = CommonLock()

        fun getInstance(): PatreonSupportersParser {
            return instance ?: lock.withLock {
                instance ?: PatreonSupportersParser().also { instance = it }
            }
        }
    }

    suspend fun parseSupporters(): List<SupporterInfo> {
        return try {
            val statement = globalHttpClient.get(
                urlString = "https://raw.githubusercontent.com/zacharee/PatreonSupportersRetrieval/master/app/src/main/assets/supporters.json",
            )

            Json.decodeFromString(ListSerializer(SupporterInfo.serializer()), statement.bodyAsText())
        } catch (e: Exception) {
            BifrostLogger.supporters.warn("Failed to fetch or parse supporters: ${e.message}", e)
            emptyList()
        }
    }
}
