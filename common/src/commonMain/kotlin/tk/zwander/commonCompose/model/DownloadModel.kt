package tk.zwander.commonCompose.model

import dev.zwander.kotlin.file.IPlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
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
        // Clean up temp files when job ends (cancel or complete)
        cleanupTempFiles()
    }
}
