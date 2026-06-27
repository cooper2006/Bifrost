package tk.zwander.common.util

import dev.zwander.kotlin.file.IPlatformFile
import dev.zwander.kotlin.file.PlatformFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import tk.zwander.common.data.ChunkState
import tk.zwander.common.data.DownloadStage
import tk.zwander.common.data.DownloadState
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
actual object DownloadStateManager {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val downloadsDirPath: String by lazy {
        val paths = NSFileManager.defaultManager.URLsForDirectory(
            NSDocumentDirectory,
            NSUserDomainMask
        )
        val docsDir = paths.firstOrNull()?.path ?: ""
        "$docsDir/bifrost_download_states".also { path ->
            NSFileManager.defaultManager.createDirectoryAtPath(
                path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null
            )
        }
    }

    private fun getStateFilePath(firmwareId: String): String {
        return "$downloadsDirPath/$firmwareId.json"
    }

    actual suspend fun saveState(state: DownloadState) {
        val statePath = getStateFilePath(state.firmwareId)
        val updatedState = state.copy(lastUpdateTime = System.currentTimeMillis())
        val jsonString = json.encodeToString(updatedState)
        jsonString.encodeToByteArray().let { bytes ->
            NSFileManager.defaultManager.createFileAtPath(
                statePath,
                bytes = bytes.toNSData(),
                attributes = null
            )
        }
    }

    actual suspend fun loadState(firmwareId: String): DownloadState? {
        val statePath = getStateFilePath(firmwareId)
        val data = NSFileManager.defaultManager.contentsAtPath(statePath) ?: return null

        return try {
            val jsonString = data.toByteArray().decodeToString()
            json.decodeFromString<DownloadState>(jsonString).let { state ->
                state.copy(
                    chunks = state.chunks.map { chunk ->
                        ChunkState.fromStatusString(
                            chunkId = chunk.chunkId,
                            startByte = chunk.startByte,
                            endByte = chunk.endByte,
                            downloadedBytes = chunk.downloadedBytes,
                            checksum = chunk.checksum,
                            statusString = chunk.statusString
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    actual suspend fun deleteState(firmwareId: String) {
        val statePath = getStateFilePath(firmwareId)
        NSFileManager.defaultManager.removeItemAtPath(statePath, error = null)
    }

    actual suspend fun getIncompleteDownloads(): List<DownloadState> {
        return getAllStateFiles().mapNotNull { file ->
            try {
                val state = loadState(file.getName().removeSuffix(".json"))
                state?.takeIf { it.currentStage != DownloadStage.COMPLETED }
            } catch (e: Exception) {
                null
            }
        }
    }

    actual suspend fun hasResumableDownload(firmwareId: String): Boolean {
        val state = loadState(firmwareId)
        return state != null && state.currentStage != DownloadStage.COMPLETED
    }

    actual suspend fun getStateFile(firmwareId: String): IPlatformFile? {
        val statePath = getStateFilePath(firmwareId)
        return if (NSFileManager.defaultManager.fileExistsAtPath(statePath)) {
            PlatformFile(statePath)
        } else {
            null
        }
    }

    actual suspend fun getAllStateFiles(): List<IPlatformFile> {
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(
            downloadsDirPath,
            error = null
        ) ?: return emptyList()

        return contents.mapNotNull { item ->
            val name = item as? String ?: return@mapNotNull null
            if (name.endsWith(".json")) {
                PlatformFile("$downloadsDirPath/$name")
            } else {
                null
            }
        }
    }
}
