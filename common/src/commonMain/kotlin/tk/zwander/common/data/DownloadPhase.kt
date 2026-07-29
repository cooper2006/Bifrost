package tk.zwander.common.data

/**
 * 下载流程的各个阶段状态。
 * 使用 sealed interface 确保类型安全，UI 层通过 when 表达式穷举所有分支。
 */
sealed interface DownloadPhase {
    /** 空闲状态，无下载任务。 */
    data object Idle : DownloadPhase

    /** 正在获取固件信息（BinaryInform）。 */
    data object FetchingInfo : DownloadPhase

    /** 正在执行 BinaryInit 认证。 */
    data object BinaryInit : DownloadPhase

    /** 正在下载文件。 */
    data class Downloading(val progress: Progress) : DownloadPhase

    /** 正在校验 CRC32。 */
    data class VerifyingCrc(val progress: Progress) : DownloadPhase

    /** 正在校验 MD5。 */
    data class VerifyingMd5(val progress: Progress) : DownloadPhase

    /** 正在复制文件到目标目录。 */
    data class Copying(val progress: Progress) : DownloadPhase

    /** 正在解密文件。 */
    data class Decrypting(val progress: Progress) : DownloadPhase

    /** 下载完成。 */
    data class Done(val message: String) : DownloadPhase

    /** 下载出错。 */
    data class Error(val message: String, val exception: Throwable? = null) : DownloadPhase
}

/**
 * 进度信息。
 * @param current 当前进度（已处理的字节数）
 * @param max 总字节数
 * @param bytesPerSecond 当前速度（字节/秒）
 */
data class Progress(
    val current: Long,
    val max: Long,
    val bytesPerSecond: Long = 0L,
)
