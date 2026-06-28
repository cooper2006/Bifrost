package tk.zwander.common.util

import android.content.Context
import dev.zwander.kotlin.file.IPlatformFile
import dev.zwander.kotlin.file.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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

    private lateinit var context: Context

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    private val downloadsDir: File by lazy {
        val dir = File(context.filesDir, "download_states")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    actual suspend fun saveState(state: DownloadState) = withContext(Dispatchers.IO) {
        val stateFile = File(downloadsDir, "${state.firmwareId}.json")
        val updatedState = state.copy(lastUpdateTime = System.currentTimeMillis())
        val jsonString = json.encodeToString(updatedState)
        stateFile.writeText(jsonString)
    }

    actual suspend fun loadState(firmwareId: String): DownloadState? = withContext(Dispatchers.IO) {
        val stateFile = File(downloadsDir, "$firmwareId.json")
        if (!stateFile.exists()) return@withContext null

        try {
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

    actual suspend fun deleteState(firmwareId: String) = withContext(Dispatchers.IO) {
        val stateFile = File(downloadsDir, "$firmwareId.json")
        if (stateFile.exists()) {
            stateFile.delete()
        }
    }

    actual suspend fun getIncompleteDownloads(): List<DownloadState> = withContext(Dispatchers.IO) {
        getAllStateFiles().mapNotNull { file ->
            try {
                val state = loadState(file.getName().removeSuffix(".json"))
                state?.takeIf { it.currentStage != DownloadStage.COMPLETED }
            } catch (e: Exception) {
                null
            }
        }
    }

    actual suspend fun hasResumableDownload(firmwareId: String): Boolean = withContext(Dispatchers.IO) {
        val state = loadState(firmwareId)
        state != null && state.currentStage != DownloadStage.COMPLETED
    }

    actual suspend fun getStateFile(firmwareId: String): IPlatformFile? = withContext(Dispatchers.IO) {
        val stateFile = File(downloadsDir, "$firmwareId.json")
        if (stateFile.exists()) PlatformFile(stateFile) else null
    }

    actual suspend fun getAllStateFiles(): List<IPlatformFile> = withContext(Dispatchers.IO) {
        downloadsDir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { PlatformFile(it) }
            ?: emptyList()
    }
}
