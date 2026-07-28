package tk.zwander.common.util

/**
 * JVM/Android 实现：使用 synchronized 关键字实现可重入锁。
 */
actual class CommonLock {
    private val lock = Any()

    actual fun <T> withLock(block: () -> T): T {
        return synchronized(lock) {
            block()
        }
    }
}
