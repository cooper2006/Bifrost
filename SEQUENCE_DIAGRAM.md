# 下载流程时序图

```
UI (DownloadView)    DownloadModel         Downloader              Request           FusClient              Ktor Streaming      FileManager        CryptUtils
     |                     |                     |                     |                  |                       |                  |                    |
     |-- Download Click -->|                     |                     |                  |                       |                  |                    |
     |                     |-- launchJob ------->|                     |                  |                       |                  |                    |
     |                     |  (via JobManager)   |                     |                  |                       |                  |                    |
     |                     |                     |-- onDownload ------|                  |                       |                  |                    |
     |                     |                     |  stateMachine.transition(FetchingInfo)|                    |                  |                    |
     |                     |                     |                     |-- retrieveBinaryFileInfo              |                  |                    |
     |                     |                     |                     |                  |-- makeReq(GEN_NONCE)-->|                  |                    |
     |                     |                     |                     |                  |<-- nonce/auth -----|   |                  |                    |
     |                     |                     |                     |                  |-- makeReq(BINARY_INFORM)->|             |                    |
     |                     |                     |                     |                  |<-- BinaryFileInfo -|   |                  |                    |
     |                     |                     |                     |                  |                       |                  |                    |
     |                     |                     |<-- BinaryFileInfo   |                  |                       |                  |                    |
     |                     |                     |                     |                  |                       |                  |                    |
     |                     |                     |-- performDownload --|                  |                       |                  |                    |
     |                     |                     |  phase 0: buildDownloadContext         |                   |                  |                    |
     |                     |                     |  phase 1: writeDecryptionKey           |                   |                  |                    |
     |                     |                     |  phase 2: phaseBinaryInitAndDownload   |                   |                  |                    |
     |                     |                     |    stateMachine.transition(BinaryInit) |                   |                  |                    |
     |                     |                     |    retryWithBackoff<String?>(max=10)   |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |    doBinaryInitAndDownload             |                   |                  |                    |
     |                     |                     |         |-- refreshNonce() ----------->|                   |                  |                    |
     |                     |                     |         |  (authMutex.withLock)        |                   |                  |                    |
     |                     |                     |         |-- createBinaryInit()         |                   |                  |                    |
     |                     |                     |         |-- makeReq(BINARY_INIT) ------>|                   |                  |                    |
     |                     |                     |         |  (retryWithBackoff<String>)   |                   |                  |                    |
     |                     |                     |         |  (authMutex.withLock)         |                   |                  |                    |
     |                     |                     |         |<-- 200 OK -------------------|                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |         |-- downloadFile() ----------->|                   |                  |                    |
     |                     |                     |         |  (start=已下载偏移)           |                   |                  |                    |
     |                     |                     |         |                             |-- GET Range:start- --->|               |                    |
     |                     |                     |         |                             |   (64KB buffer loop)  |               |                    |
     |                     |                     |         |                             |<-- 206 Partial Content-|               |                    |
     |                     |                     |         |                             |-- write to encFile --->|               |                    |
     |                     |                     |         |                             |<-- progress callback -|               |                    |
     |                     |                     |         |  (每500ms节流, 每5s日志)      |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |         |  alt 401 in downloadFile     |                   |                  |                    |
     |                     |                     |         |    onAuthRefresh()           |                   |                  |                    |
     |                     |                     |         |    refreshNonce() ---------->|                   |                  |                    |
     |                     |                     |         |    makeReq(BINARY_INIT) ----->|                   |                  |                    |
     |                     |                     |         |    continue (续传)            |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |  phase 3: phaseVerifyCrc32            |                   |                  |                    |
     |                     |                     |    stateMachine.transition(VerifyingCrc)                 |                  |-- CRC32 --------->|
     |                     |                     |         |                             |                   |                  |<-- CRC OK ----------|
     |                     |                     |         |  (失败时 cleanupTempFiles)   |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |  phase 4: phaseVerifyMd5              |                   |                  |                    |
     |                     |                     |    (仅 md5 != null 时)                |                   |                  |-- MD5 ----------->|
     |                     |                     |         |                             |                   |                  |<-- MD5 OK ----------|
     |                     |                     |         |  (失败时 cleanupTempFiles)   |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |  phase 5: phaseCopyFile               |                   |                  |                    |
     |                     |                     |    (Android + temp 目录时)             |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |  phase 6: phaseDecrypt                |                   |                  |                    |
     |                     |                     |    stateMachine.transition(Decrypting) |                  |                  |-- decrypt ------->|
     |                     |                     |         |                             |                   |                  |<-- decFile written -|
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |  phaseCleanup                         |                   |                  |                    |
     |                     |                     |    (autoDeleteEncryptedFirmware 时)    |                   |                  |                    |
     |                     |                     |         |                             |                   |                  |                    |
     |                     |                     |<-- endJobSuccess / endJob             |                   |                  |                    |
     |                     |                     |  stateMachine.transition(Done/Error)  |                   |                  |                    |
     |<-- Progress Update --|<-- progress.value -|  (via DownloadModel bridges)          |                   |                  |                    |
     |<-- Status Update ----|<-- statusText.value|                                     |                   |                  |                    |
     |                     |                     |                                     |                   |                  |                    |
```

## 关键变更说明（相对于旧的 while-loop 架构）

| 方面 | 旧方案 | 新方案 |
|------|--------|--------|
| **下载流程组织** | 单体 `performDownload()` while 循环 | 拆分为 7 个阶段方法：`buildDownloadContext` → `writeDecryptionKey` → `phaseBinaryInitAndDownload` → `phaseVerifyCrc32` → `phaseVerifyMd5` → `phaseCopyFile` → `phaseDecrypt` → `phaseCleanup` |
| **重试机制** | 手写 `while (initRetries <= 10)` 循环 + refreshNonce + continue | `retryWithBackoff<String?>` (通用指数退避重试工具，底层同 Kotlin `retryWithBackoff`) |
| **状态管理** | 三个独立字段：`progress`, `speed`, `statusText` | `DownloadStateMachine` + `DownloadPhase` sealed interface，单 StateFlow 驱动 |
| **协程管理** | 内联 `_jobs` 列表，`launchJob` 手动跟踪 | `JobManager` (SupervisorJob + Dispatchers.IO)，`invokeOnCompletion` 自动检测活跃状态 |
| **线程安全** | 无锁，`nonce/auth/sessionId` 有 `@Volatile` 但 check-then-act 有竞态 | `authMutex` (Kotlinx `Mutex`) 保护所有 nonce/auth/sessionId 读写 |
| **`makeReq` 重试** | 递归调用（FusClientLegacy 有无限递归 bug） | `retryWithBackoff<String>` 统一处理 401 重试，`makeReqWithRetryCheck` 持有 authMutex |
| **错误处理** | 异常消息字符串匹配 | 专用异常类型 (`AuthExpiredException`, `DownloadTimeoutException`, `ConnectionClosedException`) + try/catch 统一处理 |
| **上下文封装** | 方法内从 model 逐个读取字段 | `DownloadContext` data class — 不可变快照，一次构造多阶段共享 |

## 关键节点说明

| 阶段 | 说明 |
|------|------|
| 1. BinaryFileInfo 获取 | 通过 BINARY_INFORM 请求获取固件信息（文件名、大小、CRC32、v4Key） |
| 2. buildDownloadContext | 构造所有文件路径（encFile/extractedEncFile/decFile/decKeyFile），注册到 _tempFiles |
| 3. writeDecryptionKey | 下载开始前写入解密密钥文件（V2/V4），允许用户在下载中提前看到密钥 |
| 4. phaseBinaryInitAndDownload | BINARY_INIT 认证 + Ktor 流式下载，`retryWithBackoff<String?>` 处理 401/超时/断连（max 10 次） |
| 5. doBinaryInitAndDownload | 单次 BinaryInit + refreshNonce + downloadFile 执行体 |
| 6. Ktor 单线程流式下载 | 64KB buffer 循环读取，HTTP Range 断点续传，500ms 进度回调 |
| 7. phaseVerifyCrc32 | 解密前校验加密文件 CRC32，失败时 cleanupTempFiles |
| 8. phaseVerifyMd5 | 校验 MD5（仅服务器提供时），失败时 cleanupTempFiles |
| 9. phaseCopyFile | 如果 tempDirectory != downloadDirectory，复制到目标目录 |
| 10. phaseDecrypt | AES-ECB 解密 (.enc2/.enc4)，使用 V2Key 或 V4Key |
| 11. phaseCleanup | 可选自动删除加密文件（autoDeleteEncryptedFirmware） |
| 12. DownloadStateMachine | 通过 sealed `DownloadPhase` + `Progress` 驱动 UI 更新，替代三字段桥接 |

## 注意事项

- **authMutex 不保护下载过程本身**（大文件下载不应阻塞其他请求）。`downloadFile` 方法在开始时读取一次 auth 快照（通过 `getAuthV()` 在锁外获取），之后不再依赖 Mutex。
- **`retryWithBackoff` 需要显式泛型参数**，因为 Kotlin 编译器无法推断 Unit 类型的 T。JVM 上的签名冲突已通过移除 Unit 重载解决，调用示例：`retryWithBackoff<String?>(...)`。
- **`makeReq` 使用 `retryWithBackoff<String>`**，内部持有 authMutex 通过 `makeReqWithRetryCheck` 调用，确保重试期间状态一致。
