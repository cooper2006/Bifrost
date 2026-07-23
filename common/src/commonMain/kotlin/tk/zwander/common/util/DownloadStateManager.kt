package tk.zwander.common.util

import dev.zwander.kotlin.file.IPlatformFile
import tk.zwander.common.data.DownloadState

expect object DownloadStateManager {
    suspend fun saveState(state: DownloadState)

    suspend fun loadState(firmwareId: String): DownloadState?

    suspend fun deleteState(firmwareId: String)

    suspend fun getIncompleteDownloads(): List<DownloadState>

    suspend fun hasResumableDownload(firmwareId: String): Boolean

    suspend fun getStateFile(firmwareId: String): IPlatformFile?

    suspend fun getAllStateFiles(): List<IPlatformFile>
}
