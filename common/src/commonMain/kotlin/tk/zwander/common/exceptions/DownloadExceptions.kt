package tk.zwander.common.exceptions

/**
 * 下载过程中认证过期（HTTP 401）。
 *
 * 用于替代通过异常消息字符串匹配 "401" 判断认证失败的方式。
 * 在 [tk.zwander.common.tools.FusClient.downloadFile] 中抛出，
 * 在 [tk.zwander.common.tools.delegates.Downloader] 中用 `is` 判断捕获。
 */
class AuthExpiredException(
    message: String = "HTTP 401: Unauthorized",
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 下载过程中 socket 超时。
 *
 * 通常发生在大文件下载时连接静默断开，60 秒无数据到达。
 */
class DownloadTimeoutException(
    cause: Throwable? = null,
) : Exception("下载超时：socket 空闲超过 60 秒", cause)

/**
 * 下载过程中连接被关闭或发生 I/O 错误。
 *
 * 区别于文件系统异常（权限、磁盘满等），此类异常被视为可重试的网络错误。
 */
class ConnectionClosedException(
    cause: Throwable? = null,
) : Exception("下载连接已断开", cause)
