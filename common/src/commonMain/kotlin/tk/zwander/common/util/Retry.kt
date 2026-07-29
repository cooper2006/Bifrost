package tk.zwander.common.util

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * 带指数退避的重试工具。
 *
 * 重试条件通过 [retryable] 判断：返回 true 表示可重试，false 则直接抛出。
 * 每次重试的延迟时间 = initialDelay * (2^attempt)，但不超过 maxDelay。
 * 注意：重试延迟不包括 block 执行时间本身。
 *
 * @param maxRetries 最大重试次数（包括第一次尝试后的重试）
 * @param initialDelay 首次重试的基础延迟毫秒数
 * @param maxDelay 最大延迟毫秒数上限
 * @param retryable 判断异常是否可重试的 lambda
 * @param block 实际执行的操作（suspend 函数）
 * @return block 执行成功时的返回值
 * @throws Throwable 如果 block 一直失败则抛出最后一次异常
 */
suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelay: Long = 500L,
    maxDelay: Long = 30_000L,
    retryable: (Throwable) -> Boolean = { true },
    block: suspend () -> T,
): T {
    var lastThrowable: Throwable? = null

    for (attempt in 0..maxRetries) {
        try {
            return block()
        } catch (e: Throwable) {
            lastThrowable = e

            if (attempt >= maxRetries || !retryable(e)) {
                throw e
            }

            val delayMs = min(
                initialDelay * 2.0.pow(attempt.toDouble()).toLong(),
                maxDelay,
            )

            BifrostLogger.general.info("重试 $attempt/$maxRetries: ${e.javaClass.simpleName}: ${e.message}, 等待 ${delayMs}ms")
            delay(delayMs)
        }
    }

    throw lastThrowable ?: IllegalStateException("retryWithBackoff 未捕获到异常")
}
