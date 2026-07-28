package tk.zwander.common.data

sealed class FetchResult {
    open val error: Exception? = null
    open val rawOutput: String = ""
    open val responseCode: String? = null
    open val ignoredCodes: Set<String> = setOf("408", "F01")

    fun isReportableCode(): Boolean {
        return responseCode !in ignoredCodes
    }

    class VersionFetchResult(
        val versionCode: String = "",
        val androidVersion: String = "",
        override val error: Exception? = null,
        override val rawOutput: String = "",
        override val responseCode: String? = null,
    ) : FetchResult() {
        operator fun component1() = versionCode
        operator fun component2() = androidVersion
        operator fun component3() = error
        operator fun component4() = rawOutput
    }

    data class GetBinaryFileResult(
        val info: BinaryFileInfo? = null,
        override val error: Exception? = null,
        override val rawOutput: String = "",
        val requestBody: String,
        override val responseCode: String? = null,
    ) : FetchResult()
}
