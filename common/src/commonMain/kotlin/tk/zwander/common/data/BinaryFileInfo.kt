package tk.zwander.common.data

/**
 * V4 解密密钥，包含 AES 密钥字节和原始逻辑校验字符串。
 * 手动实现 equals/hashCode 以正确比较 ByteArray 内容。
 */
data class V4Key(
    val keyBytes: ByteArray,
    val logicString: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is V4Key) return false
        return logicString == other.logicString && keyBytes.contentEquals(other.keyBytes)
    }

    override fun hashCode(): Int {
        return 31 * logicString.hashCode() + keyBytes.contentHashCode()
    }
}

/**
 * Represents a binary file to download.
 */
data class BinaryFileInfo(
    val path: String,
    val fileName: String,
    val size: Long,
    val crc32: Long?,
    val v4Key: V4Key?,
    val fwVer: String?,
    val modelType: String?,
    val logicVal: String,
)
