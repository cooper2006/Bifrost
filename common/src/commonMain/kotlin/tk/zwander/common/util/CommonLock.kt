package tk.zwander.common.util

/**
 * 跨平台可重入锁，用于非挂起函数中的线程安全保护。
 * JVM/Android 使用 synchronized，Native 使用自旋锁。
 */
expect class CommonLock() {
    fun <T> withLock(block: () -> T): T
}
