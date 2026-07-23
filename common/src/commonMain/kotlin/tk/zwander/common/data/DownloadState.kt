package tk.zwander.common.data

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStage {
    DOWNLOADING,
    CRC_CHECKING,
    MD5_CHECKING,
    DECRYPTING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class ChunkStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

@Serializable
data class ChunkState(
    val chunkId: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val checksum: String? = null,
    val status: ChunkStatus = ChunkStatus.PENDING,
) {
    val statusString: String
        get() = status.name

    companion object {
        fun fromStatusString(chunkId: Int, startByte: Long, endByte: Long, downloadedBytes: Long, checksum: String?, statusString: String): ChunkState {
            return ChunkState(
                chunkId = chunkId,
                startByte = startByte,
                endByte = endByte,
                downloadedBytes = downloadedBytes,
                checksum = checksum,
                status = try {
                    ChunkStatus.valueOf(statusString)
                } catch (_: Exception) {
                    ChunkStatus.PENDING
                }
            )
        }
    }
}

@Serializable
data class DownloadState(
    val firmwareId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val crc32: String? = null,
    val md5: String? = null,
    val v4KeyBase64: String? = null,
    val model: String,
    val region: String,
    val fw: String,
    val currentStage: DownloadStage = DownloadStage.DOWNLOADING,
    val downloadedBytes: Long = 0L,
    val decryptedBytes: Long = 0L,
    val downloadStartTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val chunkSize: Long = 0L,
    val chunks: List<ChunkState> = emptyList(),
    val errorMessage: String? = null,
    val version: Int = 1,
) {
    val totalChunks: Int
        get() = chunks.size

    val completedChunks: Int
        get() = chunks.count { it.status == ChunkStatus.COMPLETED }

    val currentStageString: String
        get() = currentStage.name

    companion object {
        fun create(
            firmwareId: String,
            fileName: String,
            filePath: String,
            fileSize: Long,
            model: String,
            region: String,
            fw: String,
            crc32: String? = null,
            md5: String? = null,
            v4KeyBase64: String? = null,
            chunkSize: Long,
        ): DownloadState {
            val chunks = calculateChunks(fileSize, chunkSize)
            return DownloadState(
                firmwareId = firmwareId,
                fileName = fileName,
                filePath = filePath,
                fileSize = fileSize,
                model = model,
                region = region,
                fw = fw,
                crc32 = crc32,
                md5 = md5,
                v4KeyBase64 = v4KeyBase64,
                downloadStartTime = System.currentTimeMillis(),
                lastUpdateTime = System.currentTimeMillis(),
                chunkSize = chunkSize,
                chunks = chunks,
            )
        }

        fun calculateChunks(fileSize: Long, chunkSize: Long): List<ChunkState> {
            if (fileSize <= 0 || chunkSize <= 0) return emptyList()

            val chunks = mutableListOf<ChunkState>()
            var chunkId = 0
            var startByte = 0L

            while (startByte < fileSize) {
                val endByte = (startByte + chunkSize - 1).coerceAtMost(fileSize - 1)
                chunks.add(
                    ChunkState(
                        chunkId = chunkId,
                        startByte = startByte,
                        endByte = endByte,
                        downloadedBytes = 0L,
                        status = ChunkStatus.PENDING,
                    )
                )
                chunkId++
                startByte = endByte + 1
            }

            return chunks
        }
    }
}

object ChunkSizeCalculator {
    private const val FOUR_GB = 4L * 1024 * 1024 * 1024
    private const val MIN_CHUNK_SIZE = 50L * 1024 * 1024
    private const val MAX_CHUNK_SIZE = FOUR_GB
    private const val NO_CHUNK_THRESHOLD = 100L * 1024 * 1024

    fun calculate(fileSize: Long, customSize: Long? = null): Long {
        if (customSize != null && customSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            return customSize
        }

        return when {
            fileSize < NO_CHUNK_THRESHOLD -> fileSize
            fileSize < 1L * 1024 * 1024 * 1024 -> MIN_CHUNK_SIZE
            fileSize < 5L * 1024 * 1024 * 1024 -> 100L * 1024 * 1024
            else -> MAX_CHUNK_SIZE
        }
    }

    fun isValidCustomSize(size: Long): Boolean {
        return size in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE
    }
}

/**
 * Retry policy for chunk downloads.
 */
data class ChunkRetryPolicy(
    val maxRetries: Int = 3,              // Maximum retry attempts per chunk
    val initialDelayMs: Long = 1000,      // Initial delay before first retry (milliseconds)
    val backoffMultiplier: Float = 2.0f,  // Exponential backoff multiplier
    val maxDelayMs: Long = 30000,         // Maximum delay cap (milliseconds)
) {
    companion object {
        val DEFAULT = ChunkRetryPolicy()
        val AGGRESSIVE = ChunkRetryPolicy(maxRetries = 5, initialDelayMs = 500)
        val CONSERVATIVE = ChunkRetryPolicy(maxRetries = 3, initialDelayMs = 2000, maxDelayMs = 60000)
    }
    
    /**
     * Calculate delay for a given retry attempt.
     */
    fun getDelayForAttempt(attempt: Int): Long {
        val rawDelay = initialDelayMs * (backoffMultiplier.pow(attempt)).toLong()
        return rawDelay.coerceAtMost(maxDelayMs)
    }
    
    private fun Float.pow(n: Int): Float {
        var result = 1.0f
        for (i in 0 until n) result *= this
        return result
    }
}

/**
 * Nonce expiration policy.
 */
object NoncePolicy {
    const val NONCE_EXPIRATION_MS = 30 * 60 * 1000L  // 30 minutes
    
    fun isNonceExpired(downloadStartTime: Long): Boolean {
        return System.currentTimeMillis() - downloadStartTime > NONCE_EXPIRATION_MS
    }
    
    fun getNonceAge(downloadStartTime: Long): Long {
        return System.currentTimeMillis() - downloadStartTime
    }
}
