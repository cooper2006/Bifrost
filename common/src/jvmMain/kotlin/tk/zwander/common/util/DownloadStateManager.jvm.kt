package tk.zwander.common.util

import dev.zwander.kotlin.file.IPlatformFile
import dev.zwander.kotlin.file.PlatformFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.harawata.appdirs.AppDirsFactory
import tk.zwander.common.data.ChunkState
import tk.zwander.common.data.DownloadStage
import tk.zwander.common.data.DownloadState
import java.io.File

actual object DownloadStateManager {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val downloadsDir: File by lazy {
        val appDirs = AppDirsFactory.getInstance()
        val dataDir = appDirs.getUserDataDir("Bifrost", null, null)
        val dir = File(dataDir, "downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    actual suspend fun saveState(state: DownloadState) {
        val stateFile = File(downloadsDir, "${state.firmwareId}.json")
        val updatedState = state.copy(lastUpdateTime = System.currentTimeMillis())
        val jsonString = json.encodeToString(updatedState)
        stateFile.writeText(jsonString)
    }

    actual suspend fun loadState(firmwareId: String): DownloadState? {
        val stateFile = File(downloadsDir, "$firmwareId.json")
        if (!stateFile.exists()) return null

        return try {
            val jsonString = stateFile.readText()
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
        // First load the state to get the file path before deleting
        val state = loadState(firmwareId)
        
        // Then delete the state file
        val stateFile = File(downloadsDir, "$firmwareId.json")
        if (stateFile.exists()) {
            stateFile.delete()
        }
        
        // Also delete chunk files in download directories
        state?.filePath?.let { filePath ->
            val file = File(filePath)
            // Chunk directory is at: <downloadDir>/.bifrost_chunks/<destName>_chunks/
            file.parentFile?.let { parentDir ->
                val chunkDir = File(parentDir, ".bifrost_chunks")
                if (chunkDir.exists()) {
                    // Delete the specific chunk subdirectory for this firmware
                    chunkDir.listFiles()?.forEach { subDir ->
                        if (subDir.name.startsWith(file.name)) {
                            subDir.deleteRecursively()
                        }
                    }
                }
            }
        }
        
        // Also clean up in common download locations
        listOf(
            File(System.getProperty("user.home"), "Downloads"),
            File(System.getProperty("user.home"))
        ).forEach { baseDir ->
            val chunkDir = File(baseDir, ".bifrost_chunks")
            if (chunkDir.exists()) {
                chunkDir.listFiles()?.forEach { subDir ->
                    if (subDir.name.contains(firmwareId)) {
                        subDir.deleteRecursively()
                    }
                }
            }
        }
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
        val stateFile = File(downloadsDir, "$firmwareId.json")
        return if (stateFile.exists()) PlatformFile(stateFile) else null
    }

    actual suspend fun getAllStateFiles(): List<IPlatformFile> {
        return downloadsDir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { PlatformFile(it) }
            ?: emptyList()
    }
}
