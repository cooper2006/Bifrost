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
     * Flag set by [endJobSuccess] to mark the job as successfully completed.
     * Checked in [onEnd] to decide whether to clean up temp files.
     */
    private var _jobSuccess = false

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

    /**
     * Call this instead of [endJob] when the job completed successfully.
     * Temp files will be cleaned up in [onEnd].
     */
    fun endJobSuccess(text: String) {
        _jobSuccess = true
        endJob(text)
    }

    override fun onEnd(text: String) {
        super.onEnd(text)
        // Clean up temp files on success or cancellation (blank text).
        // On failure, keep partially downloaded files for resume.
        val shouldCleanup = _jobSuccess || text.isBlank()
        if (shouldCleanup) {
            cleanupTempFiles()
        }
        _jobSuccess = false
    }
}
