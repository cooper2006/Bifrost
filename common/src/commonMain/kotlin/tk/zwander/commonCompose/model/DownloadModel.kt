package tk.zwander.commonCompose.model

import dev.zwander.kotlin.file.IPlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import tk.zwander.common.data.ChunkState
import tk.zwander.common.data.DownloadState
import tk.zwander.common.data.changelog.Changelog
import tk.zwander.common.util.BifrostSettings
import tk.zwander.common.util.SettingsKey

/**
 * The model for the Downloader view.
 */
class DownloadModel : BaseModel("download_model") {
    companion object {
        private const val MANUAL_KEY = "field_manual"
    }

    /**
     * Whether the user is manually inputting firmware.
     */
    val manual = SettingsKey.Boolean(MANUAL_KEY.fullKey, false, BifrostSettings.settings).asMutableStateFlow()

    /**
     * The Android version of automatically-retrieved
     * firmware.
     */
    val osCode = MutableStateFlow("")

    /**
     * The changelog for the auto-retrieved firmware.
     */
    val changelog = MutableStateFlow<Changelog?>(null)

    /**
     * Whether the changelog is expanded.
     */
    val changelogExpanded = MutableStateFlow(false)

    /**
     * Whether the download is paused.
     */
    val isPaused = MutableStateFlow(false)

    /**
     * Current download state for chunk progress tracking.
     */
    val downloadState = MutableStateFlow<DownloadState?>(null)

    /**
     * Total number of chunks.
     */
    val totalChunks = MutableStateFlow(0)

    /**
     * Number of completed chunks.
     */
    val completedChunks = MutableStateFlow(0)

    /**
     * List of temporary files to clean up when download is cancelled.
     */
    private val _tempFiles = mutableListOf<IPlatformFile>()

    /**
     * Add a temporary file to the cleanup list.
     */
    fun addTempFile(file: IPlatformFile?) {
        file?.let {
            _tempFiles.add(it)
        }
    }

    /**
     * Clean up all temporary files.
     */
    fun cleanupTempFiles() {
        _tempFiles.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
        _tempFiles.clear()
    }

    override fun onEnd(text: String) {
        super.onEnd(text)
        downloadState.value = null
        totalChunks.value = 0
        completedChunks.value = 0
    }
}
