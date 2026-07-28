package tk.zwander.common.util

import kotlin.native.concurrent.AtomicInt
import kotlin.native.concurrent.AtomicReference
import kotlin.native.concurrent.freeze

/**
 * Darwin (iOS/macOS) 实现：使用 AtomicReference + 自旋实现简单锁。
 * 由于锁的持有时间极短（仅 List 的读写操作），自旋开销可忽略。
 */
actual class CommonLock {
    private val locked = AtomicInt(0)

    actual fun <T> withLock(block: () -> T): T {
        while (!locked.compareAndSet(0, 1)) {
            // 自旋等待
        }
        return try {
            block()
        } finally {
            locked.value = 0
        }
    }
}
