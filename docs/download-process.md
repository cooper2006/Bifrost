# Bifrost 固件下载流程说明

本文档详细介绍点击"下载"按钮后，Bifrost 从三星服务器获取固件的完整技术流程。涵盖代码层面与实际运行时行为。

> **文档版本：** v2.2.0+（架构改进 Phase 1~3：DownloadStateMachine、阶段方法拆分、retryWithBackoff 统一重试、authMutex 线程安全、JobManager 协程管理、单元测试覆盖）

---

## 流程总览

```
用户点击 Download
    │
    ▼
┌─────────────────────────────────────┐
│ 1. 获取固件元数据（Binary Inform）    │
│    - 构建 XML 请求                   │
│    - 发送至三星 BinaryInform 接口     │
│    - 解析响应中的文件信息             │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 2. 固件版本校验                      │
│    - 比对请求的版本与服务器返回的版本   │
│    - 检查 CSC/CP/PDA 一致性          │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 3. 构造下载上下文（buildDownloadContext）│
│    - 计算文件名路径                   │
│    - 创建 encFile/extractedEncFile/   │
│      decFile/decKeyFile              │
│    - 注册到临时文件跟踪列表            │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 4. 写解密密钥文件（writeDecryptionKey）│
│    - V2/V4 密钥写入                  │
│    - 下载开始前写入，用户可提前查看    │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 5. 初始化 + 下载（phaseBinaryInitAndDownload）│
│    - BinaryInit 认证                  │
│    - retryWithBackoff 指数退避重试    │
│    - Ktor 单线程流式下载              │
│    - HTTP Range 断点续传              │
│    - 401/超时/断连 自动重试            │
│    - 进度回调 + DownloadStateMachine  │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 6. 完整性校验                        │
│    ├─ phaseVerifyCrc32 (可选)        │
│    └─ phaseVerifyMd5 (服务器提供时)   │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 7. 文件复制（phaseCopyFile）          │
│    （仅在 Android 中转时执行）        │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 8. 解密固件（phaseDecrypt）           │
│    - AES-ECB 解密                    │
│    - .enc2 / .enc4 两种加密格式      │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 9. 清理（phaseCleanup）              │
│    - 可选自动删除加密文件              │
│    - 成功时 cleanupTempFiles          │
└──────────────┬──────────────────────┘
               │
               ▼
             完成
```

---

## 阶段一：版本查询（Check for Updates）

点击 **Check for Updates** 按钮触发版本检查流程：

### 1.1 FOTA 版本查询

`VersionFetch.getLatestVersion()` 向三星 FOTA 服务发送 HTTP GET 请求：

```
GET https://fota-cloud-dn.ospserver.net:443/firmware/{region}/{model}/version.xml
User-Agent: Kies2.0_FUS
```

服务器返回 XML，其中 `<latest>` 标签包含最新固件版本号。Bifrost 从中提取版本字符串（格式如 `G970FXXU6HVH1/G970FOXM6HVH1/G970FXXU6HVH1/G970FXXU6HVH1`）和对应的 Android 版本号。

### 1.2 历史版本查询（备选方案）

如果 FOTA 接口返回空结果，Bifrost 再调用 `VersionFetch.hybridGetLatestVersion()` 通过三星 SmartHistory 接口获取固件历史：

1. 构建带签名的 History XML 请求
2. 通过 `FusClient.makeReq(Request.HISTORY, ...)` 发送
3. 解析 `<BINARY_INFO>` 节点列表
4. 排除测试版固件（`osName == "Z(Android 99)"`）
5. 按 `sequence` 排序，取最后（最新）一个版本

### 1.3 更新日志获取

版本确定后，通过 `ChangelogHandler.getChangelog()` 获取该版本的更新日志（changelog），在 UI 上展示给用户。

---

## 阶段二：点击下载（Download）

点击 **Download** 按钮触发 `Downloader.onDownload()`（[代码](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L38)）。

### 2.1 获取固件文件信息

`Request.retrieveBinaryFileInfo()` 中的核心逻辑：

**步骤 1：构建 BinaryInform XML 请求**

```xml
<FUSMsg>
  <FUSHdr>
    <ProtoVer>1</ProtoVer>
    <SessionID>0</SessionID>
    <MsgID>1</MsgID>
  </FUSHdr>
  <FUSBody>
    <Put>
      <Data name="ACCESS_MODE">1</Data>
      <Data name="BINARY_NATURE">1</Data>
      <Data name="REQUEST_TYPE">2</Data>
      <Data name="LOGIC_CHECK">xxxx</Data>
      <Data name="BINARY_SW_VERSION">G970FXXU6HVH1/G970FOXM6HVH1/...</Data>
      <Data name="DEVICE_SN_NUMBER"></Data>
      <Data name="BINARY_LOCAL_CODE">XAA</Data>
      <Data name="BINARY_MODEL_NAME">SM-G970F</Data>
      ...
    </Put>
    <Get>
      <CmdID>2</CmdID>
      <BINARY_SW_VERSION/>
    </Get>
  </FUSBody>
</FUSMsg>
```

其中 `LOGIC_CHECK` 字段由 `Request.getLogicCheck()` 生成：通过 nonce 对固件版本字符串进行位选择运算，做逻辑校验。

**步骤 2：发送 BinaryInform 请求**

```http
POST https://neofussvr.sslcs.cdngc.net/NF_SmartDownloadBinaryInform.do
Authorization: FUS nonce="...", signature="...", ...
User-Agent: SMART 2.0
```

**步骤 3：解析响应**

三星服务器返回 XML，Bifrost 从中提取以下关键字段：

| 字段 | 说明 |
|------|------|
| `BINARY_BYTE_SIZE` | 固件文件大小（字节） |
| `BINARY_NAME` | 固件文件名（如 `SM-G970F_8_WWW_XXXX_XXXX.zip.enc2`） |
| `MODEL_PATH` | 文件的服务器路径前缀 |
| `BINARY_CRC` | CRC32 校验值（可选） |
| `BINARY_SW_VERSION` | 固件版本号 |
| `DEVICE_MODEL_TYPE` | 设备型号类型 |
| `LOGIC_VALUE_FACTORY` / `LOGIC_VALUE_HOME` | V4 解密密钥的原始材料 |
| `DEVICE_PDA_CODE1_FILE` | PDA 文件名，用于版本校验 |
| `DEVICE_CSC_HOME_FILE` / `DEVICE_CSC_FILE` | CSC 文件名，用于版本校验 |
| `DEVICE_PHONE_FONT_FILE` | CP（基带）文件名，用于版本校验 |

### 2.2 版本一致性校验

解析 XML 响应后，`retrieveBinaryFileInfo()` 会执行严格的版本匹配（[Request.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/Request.kt)）：

- 从请求的固件版本（如 `G970FXXU6HVH1/G970FOXM6HVH1/G970FXXU6HVH1/G970FXXU6HVH1`）中提取 PDA、CSC、CP 三部分
- 对比服务器返回的各个文件（PDA / CSC / CP / USERDATA）中的版本字段
- 校验 CSC 后缀（如 `OXM` 的后缀匹配）
- 如果版本不一致，抛出 `VersionMismatchException`（[异常定义](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/data/exception/VersionMismatchException.kt)），提示用户

### 2.3 版本回退确认

当检测到固件版本不匹配时，Bifrost 会弹出确认对话框询问用户是否继续下载：

```kotlin
confirmCallback.onError(
    info = DownloadErrorInfo(
        message = exception.message ?: "Unknown error",   // 安全调用，防止 message 为 null
        callback = DownloadErrorConfirmCallback(
            onAccept = {
                if (info != null) {
                    performDownload(info, model)   // 安全检查，info 可能为 null
                } else {
                    model.endJob("")
                    eventManager.sendEvent(Event.Download.Finish)
                }
            },
            onCancel = { model.endJob(""); eventManager.sendEvent(Event.Download.Finish) },
        ),
    ),
)
```

如果用户点击"是"，即使版本不匹配也会继续后续下载。

### 2.4 V4 解密密钥提取

如果固件使用 `.enc4` 加密格式，Bifrost 会从 XML 响应中提取 V4 密钥：

```kotlin
fun extractV4Key(): Pair<ByteArray, String>? {
    // 取 LATEST_FW_VERSION 或 BINARY_SW_VERSION
    // 取 LOGIC_VALUE_FACTORY 或 LOGIC_VALUE_HOME 的 Data
    // 用 Request.getLogicCheck(fwVer, logicVal) 生成逻辑校验值
    // 对逻辑校验值做 MD5 哈希，得到 AES-ECB 解密密钥
}
```

---

## 阶段三：下载初始化（Binary Init）

### 3.1 构建 BinaryInit XML

```xml
<FUSMsg>
  <FUSHdr>...</FUSHdr>
  <FUSBody>
    <Put>
      <Data name="BINARY_NAME">SM-G970F_8_WWW_XXXX_XXXX.zip.enc2</Data>
      <Data name="BINARY_SW_VERSION">G970FXXU6HVH1/...</Data>
      <Data name="DEVICE_LOCAL_CODE">XAA</Data>
      <Data name="DEVICE_MODEL_TYPE">SM-G970F</Data>
      <Data name="LOGIC_CHECK">xxxx</Data>
    </Put>
  </FUSBody>
</FUSMsg>
```

`LOGIC_CHECK` 在此处基于文件名的中间段（倒数第 25 到第 9 个字符）与 nonce 计算。

### 3.2 发送 BinaryInit 请求

```http
POST https://neofussvr.sslcs.cdngc.net/NF_SmartDownloadBinaryInitForMass.do
```

### 3.3 Nonce 过期与断连重试机制

三星的 FUS nonce 可能在 BinaryInit 和实际下载之间过期（返回 HTTP 401），或在下载大文件（17GB+）时发生 socket 超时 / 连接关闭。`Downloader.phaseBinaryInitAndDownload()` 使用 `retryWithBackoff` 统一处理重试（[代码行 263-292](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L263)）：

```kotlin
private suspend fun phaseBinaryInitAndDownload(
    ctx: DownloadContext, model: DownloadModel,
    info: BinaryFileInfo, fwVer: String?, modelType: String?,
    path: String, fileName: String, size: Long,
): String? {
    model.stateMachine.transition(DownloadPhase.BinaryInit)
    BifrostLogger.download.info("phaseBinaryInitAndDownload start")

    return retryWithBackoff<String?>(
        maxRetries = 10,
        initialDelay = 1000L,
        maxDelay = 10_000L,
        retryable = { e ->
            e is AuthExpiredException ||
                e is java.net.SocketTimeoutException ||
                e is DownloadTimeoutException ||
                e is ConnectionClosedException ||
                e.message?.contains("timeout", ignoreCase = true) == true ||
                e.message?.contains("closed", ignoreCase = true) == true ||
                (e is java.io.IOException && e !is java.nio.file.FileSystemException)
        },
    ) {
        doBinaryInitAndDownload(ctx, model, fwVer, modelType, path, fileName, size)
    }
}
```

`retryWithBackoff` 的特点（[Retry.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/util/Retry.kt)）：
- **指数退避**：延迟时间 = `initialDelay * (2^attempt)`，上限 maxDelay
- **重试条件**：通过 `retryable` lambda 判断异常是否可重试
- **泛型返回值**：`suspend fun <T> retryWithBackoff(maxRetries, initialDelay, maxDelay, retryable, block)`，需显式指定类型参数（JVM 上因签名冲突移除了 Unit 重载）

> **注意：** 每次重试都会执行 `doBinaryInitAndDownload`，它会先刷新 nonce、重建 BinaryInit，但**不会清空已下载的文件**——`start = encFile.getLength()` 实现断点续传，避免大文件重头下载。此前的 `while` 循环已全部替换为 `retryWithBackoff`。

重试覆盖三类瞬时故障：
- **401 认证过期**：刷新 nonce + 重新 BinaryInit
- **Socket 超时**：从当前文件偏移续传
- **连接关闭 / IOException**：从当前文件偏移续传（排除 `FileSystemException`）

---

## 阶段四：文件下载与文件管理

### 4.1 下载引擎：Ktor 单线程流式

当前版本使用 Ktor HTTP 客户端直接流式下载，**不再依赖 Ketch 库**（[FusClient.kt L196-296](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/FusClient.kt#L196)）。迁移原因：Ketch 在下载前会自动发送 HEAD 请求，消耗 FUS auth 导致后续请求失败。

```kotlin
globalHttpClient.prepareRequest {
    method = HttpMethod.Get
    url(url)
    headers {
        append("Authorization", authV)
        append("User-Agent", "SMART 2.0")
        append("Cache-Control", "no-cache")
        if (start > 0) {
            append("Range", "bytes=$start-")   // 断点续传
        }
    }
    timeout {
        requestTimeoutMillis = INFINITE
        socketTimeoutMillis = 60_000   // 60 秒无数据则超时
        connectTimeoutMillis = 30_000
    }
}.execute { response ->
    val channel = response.bodyAsChannel()
    val outputStream = dest.openOutputStream(true)
    val buffer = ByteArray(64 * 1024)   // 64KB buffer
    while (!channel.isClosedForRead) {
        val bytesRead = channel.readAvailable(buffer)
        if (bytesRead <= 0) break
        outputStream.write(buffer, 0, bytesRead)
        downloadedBytes += bytesRead
        // 进度回调（500ms 节流）
    }
}
```

特性：
- **单线程流式**：避免多连接对 FUS auth 的并发消耗
- **断点续传**：通过 `Range: bytes={start}-` 头实现，从已下载偏移继续
- **超时策略**：socket 60 秒无数据超时，避免连接静默断开后无限阻塞
- **进度节流**：进度回调每 500ms 触发一次，避免高频 StateFlow 发射

### 4.2 ParallelDownloader（已实现，尚未接入）

`ParallelDownloader`（[ParallelDownloader.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/ParallelDownloader.kt)）提供了分块并行下载能力，当前代码库已实现但**主下载流程尚未调用**，仍由 `FusClient.downloadFile` 单线程处理。其设计供后续启用：

- **动态分块**：50MB–500MB，按文件大小自适应（[calculateChunkSize](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/ParallelDownloader.kt#L54)）
- **并发上限 4**：`DEFAULT_CONNECTIONS = 4`（曾为 8，降并发以减内存压力）
- **FileChannel positioned write**：线程安全并发写入，避免共享 RandomAccessFile 的 seek/write 竞争
- **分块级 401 重试**：单分块 401 时回滚该分块进度计数，通过 `authProvider` 刷新 nonce 重试（最多 3 次）
- **Auth 刷新互斥**：`Mutex` 保护，多分块同时 401 时只刷新一次，避免级联失效

> ⚠️ 启用前需评估多连接并发对 FUS nonce/auth 的消耗——当前单线程方案正是为规避此问题而设计。

### 4.3 文件路径与命名

下载开始前，`buildDownloadContext()` 会构造以下文件路径（[代码行 176-237](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L176)）：

| 变量 | 路径 | 说明 |
|------|------|------|
| `encFile` | `tempDir / fullFileName` | 加密文件（下载目标） |
| `extractedEncFile` | `downloadDir / fullFileName` | 目标目录中的副本 |
| `decFile` | `downloadDir / fullFileName.replace(.enc2/.enc4, "")` | 解密后的固件 |
| `decKeyFile` | `downloadDir / DecryptionKey_fullFileName.txt` | 解密密钥（可选） |

文件名规则（[代码行 180-183](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L180)）：

```kotlin
val fullFileName = fileName.replace(
    ".zip",
    "_${model.fw.value.replace("/", "_")}_${model.region.value}.zip",
).substringAfterLast("/")
```

> ⚠️ 三星固件版本字符串较长（如 `G970FXXU6HVH1/G970FOXM6HVH1/...`），替换后文件名可能超过文件系统的 255 字符限制。

### 4.4 临时文件跟踪与清理

所有文件路径注册到 `DownloadModel._tempFiles` 列表（[代码行 229-234](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L229)）：

```kotlin
model.addTempFile(encFile)          // 加密文件（临时）
model.addTempFile(extractedEncFile) // 目标目录副本
model.addTempFile(decFile)          // 解密后的固件
model.addTempFile(decKeyFile)       // 解密密钥
```

`onEnd()` 被调用时按结果分支处理（[DownloadModel.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/commonCompose/model/DownloadModel.kt#L70)）：

```kotlin
override fun onEnd(text: String) {
    super.onEnd(text)
    // 仅在成功或用户取消时清理；失败时保留已下载部分以便下次续传
    val shouldCleanup = _jobSuccess || text.isBlank()
    if (shouldCleanup) {
        cleanupTempFiles()
    }
    _jobSuccess = false
}
```

成功完成时，下载流程调用 `endJobSuccess(text)` 而非 `endJob(text)`：

```kotlin
fun endJobSuccess(text: String) {
    _jobSuccess = true
    endJob(text)
}
```

> **变更说明（v2.3.0+）：** 此前 `onEnd()` 通过 `text == "done"` 硬编码字符串判断成功，不可靠（本地化后字符串可能不同），且在所有路径（含失败）上都可能执行 cleanup，删除已下载的加密文件导致断点续传失效。现已修正为：
> - **成功时**调用 `endJobSuccess()` 设置 `_jobSuccess = true`，触发 cleanup
> - **取消时**`text.isBlank()` 为真，触发 cleanup
> - **失败时**两者皆为 false，保留已下载部分，配合 `start = encFile.getLength()` 实现续传

### 4.5 暂停 / 恢复机制

暂停/恢复按钮（[DownloadView.kt L157-165](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/commonCompose/view/pages/DownloadView.kt#L157)）：

```kotlin
val isPaused by model.isPaused.collectAsState(false)
// ...
onClick = {
    model.isPaused.value = !model.isPaused.value   // 切换 boolean 标志
}
```

暂停状态在 5 个进度回调点检查（均为 `model.waitWhilePaused()` 模式，封装于 `Downloader.kt` 行 31-35）：

| 检查点 | 方法 | 说明 |
|--------|------|------|
| Ktor 下载进度回调 | `doBinaryInitAndDownload` 行 353-361 | 暂停进度更新 |
| CRC32 校验 | `phaseVerifyCrc32` 行 390-397 | 暂停 CRC 校验 |
| 文件复制（Android） | `phaseCopyFile` 行 471-478 | 暂停文件复制 |
| AES 解密 | `phaseDecrypt` 行 501-510 | 暂停解密 |

> **改进说明：** v2.2.0+ 已将暂停检查提取为公共函数 `waitWhilePaused()`（`DownloadModel.waitWhilePaused`），替代此前 4 处重复的 `while (isPaused.value) { delay(100) }` 代码块。

> ⚠️ **已知局限性：** 当前暂停机制仅暂停进度回调和后续阶段，**不会暂停底层的 Ktor 网络流读取**。如果用户在下载过程中点击暂停：
> 1. 进度停止更新（UI 冻结）
> 2. Ktor 仍在后台读取响应体并写入文件
> 3. 下载完成后，进度回调不再被调用，`while` 循环退出
> 4. 流程继续进入 CRC/解密阶段
>
> 用户看到"已暂停"但实际下载仍在进行——这是一个**行为与预期不符的功能缺陷**。修复需在 `while (isPaused)` 循环内同时挂起读取循环，或对 Ktor 读取协程做取消/恢复控制。

### 4.6 下载授权与 Nonce 管理

FusClient 管理完整的 FUS 会话生命周期（[FusClient.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/FusClient.kt)）：

- **Nonce 生成**：`POST NF_SmartDownloadGenerateNonce.do`，获取加密 nonce
- **Nonce 解密**：`CryptUtils.decryptNonce()` 通过专有算法解密
- **认证头**：`Authorization: FUS nonce="...", signature="...", nc="...", type="..."`
- **签名算法**：多层 MD5 哈希
- **Session Cookie**：响应头的 `Set-Cookie` 中的 `JSESSIONID`
- **401 自动刷新**：`makeReq()`（使用 `retryWithBackoff<String>`）和 `performDownload()` 都内置 401 检测与 nonce 刷新
- **线程安全**：`authMutex`（`Mutex`）保护 `nonce`/`auth`/`sessionId` 三字段的读写，`makeReqWithRetryCheck` 在整个请求过程中持有锁。为防止死锁，`getAuthV` 和 `makeSignatureHash` 拆分为带锁的公开 suspend 版本（供外部调用）和无锁的内部非 suspend 版本（`getAuthVInternal`/`makeSignatureHashInternal`，供 `makeReqInternal` 在已持锁状态下调用）
- **makeReq 重试**：使用 `retryWithBackoff<String>` 替代旧的手写递归，避免 FusClientLegacy 的无限递归 bug
- **下载不持有锁**：`downloadFile` 在请求开始时通过 `getAuthV(cloud=true)` 读取一次 auth 快照，之后不再依赖 Mutex，避免大文件下载阻塞其他请求

### 4.7 下载位置与临时文件管理

- **桌面端**：用户选择目标目录后直接下载
- **Android**：先下载到 Bifrost 内部数据目录（临时目录），再复制到用户选择的目标目录
- **临时文件追踪**：`DownloadModel` 维护 `_tempFiles` 列表，任务成功或取消时自动清理；失败时保留以便续传

---

## 阶段五：完整性校验

### 5.1 CRC32 校验

如果三星服务器返回了 `BINARY_CRC` 字段，Bifrost 在下载完成后对加密文件进行 CRC32 校验：

```kotlin
val result = CryptUtils.checkCrc32(
    encFile.openInputStream(),
    encFile.getLength(),
    crc32,    // 服务器返回的预期值
) { current, max, bps ->
    // 更新进度
}
```

- 使用 Kotlinx CRC32 实现
- 逐块读取文件计算校验值
- 与服务器返回的 CRC32 值对比
- 校验失败则显示错误并终止后续流程

### 5.2 MD5 校验

如果三星服务器在 HTTP 响应头中返回了 `Content-MD5`，Bifrost 还会进行 MD5 校验。当前实现中 `FusClient.downloadFile()` 返回 `null`（不再主动探测 MD5），MD5 校验仅在显式获取到 md5 值时执行：

```kotlin
val result = withContext(Dispatchers.Default) {
    CryptUtils.checkMD5(md5, encFile.openInputStream())
}
```

> **变更说明：** 早期版本通过 HEAD 请求探测 `Content-MD5`，现版本在 `downloadFile` 返回 md5 时才校验，避免额外请求消耗 auth。

---

## 阶段六：文件复制（中转处理）

仅在 Android 平台上，当临时目录与目标目录不同时、且文件未下载完整时执行（[代码行 326](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L326)）：

```kotlin
if (tempDirectory != null && tempDirectory != downloadDirectory && extractedEncFile.getLength() < size) {
    // 从临时目录复制到目标目录
}
```

1. 从临时目录打开加密文件的 `InputStream`
2. 向目标目录打开 `OutputStream`
3. 通过 `streamOperationWithProgress` 逐块复制
4. 复制完成后删除临时加密文件

---

## 阶段七：固件解密

### 7.1 解密密钥获取

根据文件扩展名，Bifrost 使用不同的密钥：

**`.enc2` 格式（V2 加密）：**

```kotlin
fun getV2Key(version: String, model: String, region: String): Pair<ByteArray, String> {
    val decKey = "${region}:${model}:${version}"
    // 例："XAA:SM-G970F:G970FXXU6HVH1/G970FOXM6HVH1/..."
    return MD5(decKey.toByteArray()) to decKey
}
```

密钥 = MD5(`{region}:{model}:{firmware_version}`)

**`.enc4` 格式（V4 加密）：**

密钥在 BinaryInform 阶段已提取，流程为：
```
LOGIC_VALUE_FACTORY + LATEST_FW_VERSION
    → Request.getLogicCheck() 生成逻辑校验值
    → MD5(逻辑校验值) → AES-ECB 解密密钥
```

### 7.2 AES-ECB 解密

```kotlin
CryptUtils.decryptProgress(
    extractedEncFile.openInputStream(),
    decFile.openOutputStream(),
    key,          // 16 字节 AES 密钥
    fileSize,     // 加密文件大小
) { current, max, bps ->
    // 更新进度
}
```

- 使用 AES-ECB 模式（无填充）
- 逐块读取加密文件，解密后写入目标文件
- 每处理一个块后报告进度

### 7.3 解密密钥保存（可选）

如果开启了 `enableDecryptKeySave` 设置（[Settings.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/util/Settings.kt#L49)），Bifrost 会在下载目录中额外生成一个 `.txt` 格式的解密密钥文件。

> **关键时序说明：** 密钥文件在**下载开始前**即被写入（[Downloader.kt 行 147-161](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L147)），而非解密阶段。这样用户在下载过程中即可提前看到密钥。

```kotlin
decKeyFile?.openOutputStream(false)?.use { output ->
    if (fullFileName.endsWith(".enc2")) {
        output.write(
            CryptUtils.getV2Key(
                model.fw.value,
                model.model.value,
                model.region.value,
            ).second.toByteArray(),
        )
    }
    v4Key?.let {
        output.write(v4Key.second.toByteArray())
    }
}
```

| 加密格式 | 密钥内容 |
|----------|----------|
| `.enc2` (V2) | 明文字符串 `{region}:{model}:{fw_version}` |
| `.enc4` (V4) | 密钥推导过程的输出文本（含 LOGIC_VALUE 等） |

### 7.4 自动清理加密文件

如果开启了 `autoDeleteEncryptedFirmware` 设置（[Settings.kt#L46](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/util/Settings.kt#L46)），解密完成后自动删除加密文件（`.enc2` / `.enc4`）。

---

## FUS 会话管理（贯穿全程）

### Nonce 生成

所有请求共享一个 FUS 会话 nonce：

```kotlin
POST https://neofussvr.sslcs.cdngc.net/NF_SmartDownloadGenerateNonce.do
```

服务器返回的 nonce 经过 `CryptUtils.decryptNonce()` 解密（通过专有的认证块解密算法），得到用于后续请求签名的 `auth` 值。

### 请求认证头

```http
Authorization: FUS nonce="<nonce>", signature="<signature>", nc="...", type="..."
Cookie: JSESSIONID=<sessionId>; SESSION=<sessionId>
User-Agent: SMART 2.0
```

`signature` 通过多层 MD5 哈希生成：
```
hash("auth:${nonce}:00000001")
    + hash("interface:${signature}")
    → MD5 合并结果
```

### 401 自动刷新

所有 `FusClient.makeReq()` 调用都内置了 401 自动重试：如果服务器返回 401（含 HTTP 状态码和 XML body 中的 `Status=401`），自动重新生成 nonce 并重发请求。

---

## 关键代码文件映射

| 文件 | 职责 |
|------|------|
| `Downloader.kt` | 下载流程编排：onDownload → performDownload → 7 个阶段方法 |
| `DownloadView.kt` | UI 层：按钮、进度条、状态显示 |
| `DownloadModel.kt` | 状态管理：进度、暂停标志、临时文件追踪、endJobSuccess |
| `DownloadStateMachine.kt` | 下载状态机：DownloadPhase 密封接口 + Progress, 单 StateFlow |
| `JobManager.kt` | 协程生命周期管理：SupervisorJob + Dispatchers.IO，自动检测活跃状态 |
| `BaseModel.kt` | 基类：字段持久化、jobManager、launchJob/endJob 骨架 |
| `FusClient.kt` | FUS 协议通信：authMutex 线程安全、Nonce 管理、请求签名、Ktor 流式下载、retryWithBackoff 重试 |
| `FusClientLegacy.kt` | 旧版 FUS 协议（Smart Switch）：makeReqWithRetry 限制 3 次重试 |
| `ParallelDownloader.kt` | 分块并行下载器（已标记 @Deprecated，未接入主流程） |
| `Request.kt` | 业务请求封装：BinaryInform、BinaryInit、版本校验、V4 密钥提取 |
| `VersionFetch.kt` | 最新版本检测：FOTA 接口 + SmartHistory 接口 |
| `CryptUtils.kt` | 加解密：Nonce 解密、CRC32/MD5 校验、AES-ECB 解密 |
| `Retry.kt` | 通用指数退避重试工具 `retryWithBackoff<T>()`：替代所有手写重试循环 |

---

## 时序图

```mermaid
sequenceDiagram
    participant User as 用户
    participant UI as DownloadView
    participant DM as DownloadModel
    participant SM as DownloadStateMachine
    participant DL as Downloader
    participant FC as FusClient
    participant REQ as Request
    participant Retry as retryWithBackoff
    participant Sammy as 三星服务器

    User->>UI: 点击 Download
    UI->>DL: onDownload(model)
    DL->>SM: transition(FetchingInfo)
    DL->>REQ: retrieveBinaryFileInfo(fw, model, region)
    REQ->>FC: makeReq(BINARY_INFORM, xml)
    FC->>FC: authMutex.withLock
    FC->>Sammy: POST BinaryInform.do (带 Nonce 签名)
    Sammy-->>FC: XML (文件名/大小/CRC/路径/密钥等)
    FC-->>REQ: 解析 XML
    REQ->>REQ: 版本一致性校验
    alt 版本不匹配
        REQ-->>DL: VersionException
        DL-->>UI: 确认对话框
        User->>UI: 确认继续
    end
    REQ-->>DL: BinaryFileInfo

    DL->>DL: buildDownloadContext() → DownloadContext
    DL->>DL: writeDecryptionKey() (下载开始前)

    DL->>SM: transition(BinaryInit)
    DL->>Retry: retryWithBackoff<String?>(maxRetries=10)
    loop 指数退避重试
        Retry->>DL: doBinaryInitAndDownload()
        DL->>FC: refreshNonce() (authMutex.withLock)
        DL->>REQ: createBinaryInit(fileName, nonce)
        REQ-->>DL: BinaryInit XML
        DL->>FC: makeReq(BINARY_INIT, xml)
        FC->>FC: authMutex.withLock + retryWithBackoff<String>
        FC->>Sammy: POST BinaryInitForMass.do
        Sammy-->>FC: OK
        FC-->>DL: 200

        DL->>FC: downloadFile(path, start, size, dest)
        FC->>FC: getAuthV(cloud=true) (锁外快照)
        FC->>Sammy: GET BinaryForMass.do (Range: bytes=start-)
        Sammy-->>FC: 加密固件数据流 (206 Partial Content)
        FC-->>DL: Ktor 流式下载 (64KB buffer)
        DL->>SM: transitionWithProgress(Downloading, current, max, bps)
        SM-->>UI: state Flow 驱动进度
        alt 401
            FC-->>DL: AuthExpiredException
            DL->>FC: onAuthRefresh → refreshNonce + BinaryInit
            DL->>Retry: 继续重试（续传）
        else 超时/断连
            FC-->>DL: DownloadTimeoutException / ConnectionClosedException
            DL->>Retry: 继续重试（续传）
        else 下载完成
            Retry-->>DL: md5 (String?)
        end
    end

    DL->>DL: phaseVerifyCrc32() (如 crc32 != null)
    DL->>SM: transitionWithProgress(VerifyingCrc)
    alt CRC 失败
        DL->>DM: cleanupTempFiles()
        DL->>DM: endJob(错误)
    end

    DL->>DL: phaseVerifyMd5() (如 md5 != null)
    DL->>SM: transitionWithProgress(VerifyingMd5)
    alt MD5 失败
        DL->>DM: cleanupTempFiles()
        DL->>DM: endJob(错误)
    end

    alt Android + 中转目录
        DL->>DL: phaseCopyFile()
        DL->>SM: transitionWithProgress(Copying)
    end

    DL->>DL: phaseDecrypt() (V2/V4 AES-ECB)
    DL->>SM: transitionWithProgress(Decrypting)

    DL->>DL: phaseCleanup() (autoDeleteEncryptedFirmware)
    DL->>SM: transition(Done)
    DL->>DM: endJobSuccess() → JobManager.cancelAll()
    DM->>DM: onEnd() → cleanupTempFiles()
    DL-->>UI: 完成 / 错误信息
    UI-->>User: 显示结果
```

---

## 当前已知问题

| 等级 | 问题 | 位置 | 状态 |
|------|------|------|------|
| 🔴 关键 | 暂停按钮不影响 Ktor 网络流读取，下载仍在后台进行 | `DownloadView.kt` toggle → `isPaused` flag | 未修复 |
| 🟡 中等 | 文件名可能超过 255 字符限制 | `Downloader.kt` buildDownloadContext | 未修复 |
| 🟡 中等 | `retryWithBackoff` 需显式泛型参数（JVM 签名冲突） | `Downloader.kt`、`FusClient.kt`、`FusClientLegacy.kt` | 已变通（显式类型参数） |

## 已修复问题

| 问题 | 修复内容 | 版本 |
|------|----------|------|
| FusClient/FusClientLegacy Mutex 死锁 | 将 `getAuthV` 和 `makeSignatureHash` 拆分为：<br>- 公开 suspend 版本（自行加锁，供外部调用）<br>- 内部非 suspend 版本（假设调用方已持有锁，供 `makeReqInternal` 调用）<br>`makeReqInternal` 改为调用内部版本，避免重复加锁 | v2.1.3+ |
| 单体 performDownload 难以测试和维护 | 拆分为 7 个阶段方法（buildDownloadContext/writeDecryptionKey/phaseBinaryInitAndDownload 等），每个方法单一职责 | v2.2.0+ |
| 下载进度/速度/状态三个独立字段管理混乱 | 引入 DownloadStateMachine + DownloadPhase sealed interface，单 StateFlow 驱动 UI | v2.2.0+ |
| 协程管理使用内联 _jobs 列表 | JobManager（SupervisorJob + Dispatchers.IO）+ invokeOnCompletion 自动检测 | v2.2.0+ |
| nonce/auth/sessionId 并发访问竞态 | authMutex（kotlinx Mutex）保护所有读写操作 | v2.2.0+ |
| retryWithBackoff Unit 重载导致 JVM 签名冲突 | 移除 Unit 重载，所有调用处使用显式泛型参数 | v2.2.0+ |
| FusClientLegacy.generateNonceInternal 缺参数编译错误 | 补充 makeReqInternal 的 data/signature/includeNonce 参数 | v2.2.0+ |
| makeReq 手写递归 401 重试 | 使用 retryWithBackoff<String> 统一处理 | v2.2.0+ |
| 异常分类使用字符串匹配 | 改用专用异常类型（AuthExpiredException / DownloadTimeoutException / ConnectionClosedException） | v2.1.3+ |
| 成功下载后 cleanupTempFiles() 误删最终文件 | 改为仅在成功/取消时清理，失败时保留以支持续传 | v2.1.3+ |
| Ketch 库下载前自动发 HEAD 消耗 auth | 移除 Ketch，改用 Ktor 直接流式下载 | v2.1.3+ |
| 大文件 socket 超时后无法恢复 | 实现 HTTP Range 断点续传 + 超时重试 | v2.1.3+ |
| 下载连接静默断开后无限阻塞 | 设置 socket 60s 超时 | v2.1.3+ |
| FusClientLegacy.makeReq 无限递归 | 添加 makeReqWithRetry 限制最多 3 次重试 | v2.1.3+ |
| 全代码库 println 诊断输出 | 统一替换为 BifrostLogger（SLF4J）模块分级日志 | v2.1.3+ |
| 多线程下载速度不稳定 | 回退为 Ktor 单线程流式下载，ParallelDownloader 标记 @Deprecated | v2.1.3+ |
| `downloadDirectory` 空指针 | 增加 null 检查，提前 return | v2.2.0 |
| `v4Key?.first!!` NPE | 改为 `V4Key` 数据类 + 安全调用 | v2.1.3+ |
| 请求头中包含 `Set-Cookie`（只应是响应头） | 移除 | v2.2.0 |
| MD5 探测使用 GET 请求 | 改为 HEAD 请求（现版本不主动探测，按返回值校验） | v2.2.0 |
| `BinaryFileInfo.v4Key` ByteArray 引用相等 | 创建 `V4Key` 数据类，使用 `contentEquals` 值比较 | v2.1.3+ |
| `ProgressUtils` 流泄漏 | 用 try/finally 包裹，确保异常/取消时关闭流 | v2.1.3+ |
| `EventManager` / `PatreonSupportersParser` 单例线程不安全 | 改用 `@Volatile` + `CommonLock` 双重检查锁定 | v2.1.3+ |
| `History.kt` 字符串截取越界 | 添加长度检查，过短时返回原字符串 | v2.1.3+ |
| `FetchResult.ignoredCodes` 可变数组 | 改为 `setOf` 不可变集合 | v2.1.3+ |
| `GlobalScope` 协程泄漏 | `Settings.kt`、`IMEIGenerator.kt`、`CSCDB.kt` 中 `GlobalScope.launch` 替换为 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` | v2.1.3+ |
| 全代码库 `e.printStackTrace()` | 18 处全部替换为 `BifrostLogger.warn/error`（SLF4J）带异常参数的日志调用 | v2.1.3+ |
| `Downloader.kt` 非空断言 NPE | `exception.message!!` 和 `info!!` 替换为空安全判断 | v2.1.3+ |
| `VersionFetch.kt` 非空断言 NPE | `BINARY_SEQUENCE.toInt()!!` 改为 `toIntOrNull() ?: 0` | v2.1.3+ |
| `FileManager.jvm.kt` 临时目录 NPE | `parentFile!!` 改为安全调用，失败返回 null | v2.1.3+ |
| `UrlHandler.ios.kt` URL 空指针 | `NSURL.URLWithString(url)!!` 改为安全调用 + 日志警告 | v2.1.3+ |
| `IMEIGenerator.kt` 资源加载 NPE | `MR.files.tacs_csv()!!` 改为安全调用 + 日志警告 | v2.1.3+ |
| `Request.kt` 索引越界 | `dataIndex!!` 改为空安全 + 越界判断，无效时降级到首元素 | v2.1.3+ |

## 代码变更记录

| Commit | 日期 | 内容 |
|--------|------|------|
| `7096cead` | 2026-07-29 | 架构改进 Phase 3：单元测试（4 文件 117 测试）、retryWithBackoff 编译修复、DownloadStateMachine/JobManager 文档更新 |
| (当前) | 2026-07-28 ~ 29 | 架构改进 Phase 1~2：DownloadStateMachine、阶段方法拆分、JobManager/BaseModel 分离、authMutex 线程安全、retryWithBackoff 统一重试 |
| (当前) | 2026-07-28 | 第四轮代码审查：GlobalScope 消除（3 文件）、e.printStackTrace() 全替换（18 处）、!! NPE 防御性修复（7 处） |
| (当前) | 2026-07-28 | 统一日志输出：全代码库 println 替换为 BifrostLogger（SLF4J）模块分级日志 |
| (当前) | 2026-07-28 | 第三轮代码审查修复：V4Key 数据类、流泄漏、单例线程安全、字符串越界、可变数组等 15 项修复 |
| (当前) | 2026-07-28 | 第二轮代码审查修复：FusClientLegacy 无限递归、异常类型专用化、ParallelDownloader 弃用、Decrypter NPE 等 |
| (当前) | 2026-07-28 | 第一轮代码审查修复：同步锁、@Volatile、下载器线程安全、CRC32/MD5 校验清理等 |
| (当前) | 2026-06-24 | 暂停/恢复按钮、临时文件清理、401 重试循环 |
| (当前) | 2026-06-24 | changelog 更新 |

## 改进建议

### P1 - 关键缺陷修复

1. **暂停按钮真正暂停网络下载**
   当前暂停仅暂停进度回调，Ktor 仍在后台读取流。需要在 `while (isPaused)` 循环内同时挂起 Ktor 读取循环，或对读取协程做取消/恢复控制（如使用 `select` 或 `channel` 暂停读取）。

### P2 - 代码健壮性

2. **文件名长度截断** — 对 `fullFileName` 增加长度校验，超出时截断版本号部分
3. **接入 ParallelDownloader（可选）** — 启用分块并行下载前需验证多连接对 FUS auth 的并发影响，建议在 `authProvider` 中复用单次 nonce 刷新而非每分块独立刷新

### P3 - 性能与架构

4. **暂停 busy-wait 改为挂起式**
   ```kotlin
   // 用 StateFlow.first {} 挂起，避免每 100ms 轮询
   model.isPaused.first { !it }
   ```

> **已完成：** `GlobalScope` 已全部替换为 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，不再有全局协程泄漏风险。

## 错误处理对照

| 错误场景 | 处理方式 | 代码位置 |
|----------|----------|----------|
| 服务器返回 400/401 | Nonce 过期，自动刷新后重试（`retryWithBackoff<String>`） | `FusClient.kt` / `Downloader.kt` |
| 服务器返回 403 | 设备未找到或固件不可用，提示用户 | `Request.kt` / `Downloader.kt` |
| 服务器返回 408 | IMEI/序列号无效 | `Request.kt` |
| BinaryInit 后下载 401 | `retryWithBackoff<String?>` 最多 10 次，`doBinaryInitAndDownload` 刷新 Nonce + BinaryInit | `Downloader.kt` `phaseBinaryInitAndDownload()` |
| Socket 超时 / 连接断开 | 指数退避重试，从当前文件偏移续传（上限 10 次） | `Downloader.kt` `phaseBinaryInitAndDownload()` |
| 版本不一致 | 弹出确认对话框，由用户决定是否继续 | `Downloader.kt` `onDownload()` |
| CRC32 校验失败 | 终止流程，cleanupTempFiles，提示错误 | `Downloader.kt` `phaseVerifyCrc32()` |
| MD5 校验失败 | 终止流程，cleanupTempFiles，提示错误 | `Downloader.kt` `phaseVerifyMd5()` |
| 解密失败 | 抛出异常，显示错误信息 | `Downloader.kt` `phaseDecrypt()` |
| 用户取消下载 | 清理临时文件（成功/取消路径） | `DownloadView.kt` / `DownloadModel.kt` |
| 下载失败（重试用尽） | 保留已下载部分以便下次续传 | `DownloadModel.onEnd()` |
