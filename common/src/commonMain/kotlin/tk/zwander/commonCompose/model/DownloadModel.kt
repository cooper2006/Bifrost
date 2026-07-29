package tk.zwander.commonCompose.model

import dev.zwander.kotlin.file.IPlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import tk.zwander.common.data.DownloadPhase
import tk.zwander.common.data.DownloadStateMachine
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

    /** 下载状态机，替代原有的 progress/speed/statusText 三字段方案。 */
    val stateMachine = DownloadStateMachine()

    /**
     * 桥接：从状态机映射 statusText 给旧代码使用。
     * 新代码应直接使用 stateMachine.state。
     */
    override val statusText: MutableStateFlow<String> = MutableStateFlow("")

    /**
     * 桥接：从状态机映射 speed 给旧代码使用。
     * 新代码应直接使用 stateMachine.state。
     */
    override val speed: MutableStateFlow<Long> = MutableStateFlow(0L)

    /**
     * 桥接：从状态机映射 progress 给旧代码使用。
     * 新代码应直接使用 stateMachine.state。
     */
    override val progress: MutableStateFlow<Pair<Long, Long>> = MutableStateFlow(0L to 0L)

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
    val downloadState = MutableStateFlow<tk.zwander.common.data.DownloadState?>(null)

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
     *
     * 使用 synchronizedList 保证线程安全：addTempFile 可能从下载协程调用，
     * 而 cleanupTempFiles 可能从 UI 取消操作调用，两者可能并发执行。
     * 迭代+清空是复合操作，需在 synchronized 块中执行。
     */
    private val _tempFiles = java.util.Collections.synchronizedList(mutableListOf<IPlatformFile>())

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
        synchronized(_tempFiles) {
            _tempFiles.forEach { file ->
                try {
                    file.delete()
                } catch (_: Exception) {
                    // Ignore cleanup errors
                }
            }
            _tempFiles.clear()
        }
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
