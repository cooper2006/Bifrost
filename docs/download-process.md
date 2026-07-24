# Bifrost 固件下载流程说明

本文档详细介绍点击"下载"按钮后，Bifrost 从三星服务器获取固件的完整技术流程。涵盖代码层面与实际运行时行为。

> **文档版本：** v2.2.0+（含暂停/恢复、临时文件清理、Nonce 刷新重试）

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
│ 3. 初始化下载会话（Binary Init）      │
│    - 构建 BinaryInit XML 请求        │
│    - 发送至三星 BinaryInit 接口       │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 4. 下载加密固件文件                   │
│    - Ketch 多连接下载                 │
│    - 进度回调更新 UI                  │
│    - 支持暂停/恢复                    │
│    - 401 自动重试 + Nonce 刷新        │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 5. 完整性校验                        │
│    - CRC32 校验（可选）              │
│    - MD5 校验（服务器提供时）         │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 6. 文件复制（临时目录 → 目标目录）    │
│    （仅在 Android 等需要中转时执行）  │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│ 7. 解密固件                          │
│    - AES-ECB 解密                    │
│    - .enc2 / .enc4 两种加密格式      │
│    - 生成解密密钥文件（可选）          │
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

点击 **Download** 按钮触发 `Downloader.onDownload()`（[代码](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L40)）。

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
        message = exception.message!!,   // VersionException 的 message 始终非空
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

### 3.3 Nonce 过期重试机制

三星的 FUS nonce 可能在 BinaryInit 和实际下载之间过期（返回 HTTP 401）。`Downloader.performDownload()` 在 v2.2.0+ 中实现了有界自动重试逻辑：

```kotlin
val maxInitRetries = 3    // 最多重试 3 次
var initRetries = 0

while (initRetries <= maxInitRetries) {
    if (initRetries > 0) {
        FusClient.refreshNonce()  // 重新生成 nonce
    }
    // 重新执行 BinaryInit + 下载
    FusClient.makeReq(BINARY_INIT, request)
    FusClient.downloadFile(...)
    break   // 成功退出
    // 401 时 catch → initRetries++ → continue
}
```

> **注意：** 每次重试都会刷新 nonce、重建 BinaryInit、清除上次失败的 Ketch 任务、重新执行整个流程。

---

## 阶段四：文件下载与文件管理

### 4.1 文件路径与命名

下载开始前，`performDownload()` 会构造以下文件路径（[代码行 77-117](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L77)）：

| 变量 | 路径 | 说明 |
|------|------|------|
| `encFile` | `tempDir / fullFileName` | 加密文件（下载目标） |
| `extractedEncFile` | `downloadDir / fullFileName` | 目标目录中的副本 |
| `decFile` | `downloadDir / fullFileName.replace(.enc2/.enc4, "")` | 解密后的固件 |
| `decKeyFile` | `downloadDir / DecryptionKey_fullFileName.txt` | 解密密钥（可选） |

文件名规则（[代码行 86-89](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L86)）：

```kotlin
val fullFileName = fileName.replace(
    ".zip",
    "_${model.fw.value.replace("/", "_")}_${model.region.value}.zip",
)
```

> ⚠️ 三星固件版本字符串较长（如 `G970FXXU6HVH1/G970FOXM6HVH1/...`），替换后文件名可能超过文件系统的 255 字符限制。

### 4.2 临时文件跟踪与清理

所有文件路径注册到 `DownloadModel._tempFiles` 列表（[代码行 128-131](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L128)）：

```kotlin
model.addTempFile(encFile)          // 加密文件（临时）
model.addTempFile(extractedEncFile) // 目标目录副本
model.addTempFile(decFile)          // 解密后的固件
model.addTempFile(decKeyFile)       // 解密密钥
```

`onEnd()` 被调用时会执行 `cleanupTempFiles()`（[DownloadModel.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/commonCompose/model/DownloadModel.kt#L60)）。

> ⚠️ **注意：** `onEnd()` 在 成功、失败、取消 三种路径上都会被调用。这意味着下载成功后，`decFile`（解密固件）和 `decKeyFile`（密钥）也会被 cleanup 删除。这是当前代码中的一个 **关键 Bug**——成功路径上的文件应当从 `_tempFiles` 中移除后再调用 `endJob`。

---

### 4.3 MD5 探测（HEAD 请求）

### 4.4 暂停 / 恢复机制

暂停/恢复按钮（[DownloadView.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/commonCompose/view/pages/DownloadView.kt#L172)）：

```kotlin
onClick = {
    model.isPaused.value = !model.isPaused.value   // 切换 boolean 标志
}
```

暂停状态在 4 个进度回调点检查（均为 `while (model.isPaused.value) { delay(100) }` 模式）：

| 检查点 | 代码行 | 说明 |
|--------|--------|------|
| Ketch 下载进度回调 | L181-183 | 暂停进度更新 |
| CRC32 校验 | L229-231 | 暂停 CRC 校验 |
| 文件复制（Android） | L295-297 | 暂停文件复制 |
| AES 解密 | L342-344 | 暂停解密 |

> ⚠️ **已知局限性：** 当前暂停机制仅暂停进度回调和后续阶段，**不会暂停底层的 Ketch 网络下载**。如果用户在下载过程中点击暂停：
> 1. 进度停止更新（UI 冻结）
> 2. Ketch 继续在后台下载
> 3. 下载完成后，progress callback 不再被调用，`while` 循环退出
> 4. 流程继续进入 CRC/解密阶段
>
> 用户看到"已暂停"但实际下载仍在进行——这是一个 **行为与预期不符的功能缺陷**。

### 4.5 Ketch 下载循环与有界重试

Bifrost 使用 [Ketch](https://github.com/linroid/Ketch) 库进行文件下载（[代码行 219-277](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/FusClient.kt#L219)）：

```kotlin
ketch.download(
    DownloadRequest(
        url = url,
        destination = Destination(destPath),
        headers = mapOf(
            "Authorization" to authV,
            "User-Agent" to "SMART 2.0",
            "Cache-Control" to "no-cache",
        ),
    ),
)
```

Ketch 支持：
- **多线程分片下载**：将文件分为多个分段并行下载
- **断点续传**：通过现有任务查找恢复
- **自动重试**：内部有界重试（最多 3 次）

下载循环（[代码行 249-277](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/FusClient.kt#L249)）：

```kotlin
var ketchRetries = 0
val maxKetchRetries = 3

while (ketchRetries <= maxKetchRetries) {
    val result = task.await()
    if (result.isSuccess) break

    (result.exceptionOrNull() as? KetchError)?.let { error ->
        if (!error.isRetryable) throw error   // 不可重试直接抛出
    }
    ketchRetries++
}
// 超过最大重试次数后抛出 RuntimeException
```

### 4.6 进度追踪

下载过程中通过 `task.state.collect` 收集 Ketch 的状态（[FusClient.kt L237-247](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/FusClient.kt#L237)）：

```kotlin
CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
    task.state.collect {
        if (it is DownloadState.Downloading) {
            progressCallback(
                it.progress.downloadedBytes,   // 已下载字节
                size,                            // 总大小
                it.progress.bytesPerSecond,     // 实时速度
            )
        }
    }
}
```

> **注意：** 进度收集与主协程并发运行。即使进度协程因暂停标志而阻塞，Ketch 仍会继续下载。

### 4.7 下载授权与 Nonce 管理

FusClient 管理完整的 FUS 会话生命周期（[FusClient.kt](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/FusClient.kt)）：

- **Nonce 生成**：`POST NF_SmartDownloadGenerateNonce.do`，获取加密 nonce
- **Nonce 解密**：`CryptUtils.decryptNonce()` 通过专有算法解密
- **认证头**：`Authorization: FUS nonce="...", signature="...", nc="...", type="..."`
- **签名算法**：多层 MD5 哈希
- **Session Cookie**：响应头的 `Set-Cookie` 中的 `JSESSIONID`
- **401 自动刷新**：`makeReq()` 和 `performDownload()` 都内置 401 检测与 nonce 刷新

### 4.8 下载位置与临时文件管理

- **桌面端**：用户选择目标目录后直接下载
- **Android**：先下载到 Bifrost 内部数据目录（临时目录），再复制到用户选择的目标目录
- **临时文件追踪**：`DownloadModel` 维护 `_tempFiles` 列表，任务完成或取消时自动清理

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

如果三星服务器在 HTTP 响应头中返回了 `Content-MD5`，Bifrost 还会进行 MD5 校验：

```kotlin
// 下载前先 HEAD 请求探测 Content-MD5
val md5 = httpClient.prepareRequest {
    method = HttpMethod.Head
    url(url)
    headers { ... }
}.execute { response ->
    response.headers["Content-MD5"]
}

// 下载完成后校验
val result = CryptUtils.checkMD5(md5, encFile.openInputStream())
```

采用与 CyanogenMod 相同的 MD5 校验算法。

---

## 阶段六：文件复制（中转处理）

仅在 Android 平台上，当临时目录与目标目录不同时执行：

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
    encryptedFileInputStream,
    decryptedFileOutputStream,
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

> **关键时序说明：** 密钥文件在**下载开始前**即被写入（[Downloader.kt 行 118-131](/Users/cooper/GitHub/bifrost-git/Bifrost/common/src/commonMain/kotlin/tk/zwander/common/tools/delegates/Downloader.kt#L118)），而非解密阶段。这样用户在下载过程中即可提前看到密钥。

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

所有 `FusClient.makeReq()` 调用都内置了 401 自动重试：如果服务器返回 401，自动重新生成 nonce 并重发请求。

---

## 关键代码文件映射

| 文件 | 职责 |
|------|------|
| `Downloader.kt` | 下载流程编排：onDownload → performDownload |
| `DownloadView.kt` | UI 层：按钮、进度条、状态显示 |
| `DownloadModel.kt` | 状态管理：进度、暂停标志、临时文件追踪 |
| `FusClient.kt` | FUS 协议通信：Nonce 管理、请求签名、文件下载 |
| `Request.kt` | 业务请求封装：BinaryInform、BinaryInit、版本校验 |
| `VersionFetch.kt` | 最新版本检测：FOTA 接口 + SmartHistory 接口 |
| `CryptUtils.kt` | 加解密：Nonce 解密、CRC32/MD5 校验、AES-ECB 解密 |

---

## 时序图

```mermaid
sequenceDiagram
    participant User as 用户
    participant UI as DownloadView
    participant DM as DownloadModel
    participant DL as Downloader
    participant FC as FusClient
    participant REQ as Request
    participant Sammy as 三星服务器

    User->>UI: 点击 Download
    UI->>DL: onDownload(model)
    DL->>REQ: retrieveBinaryFileInfo(fw, model, region)
    REQ->>FC: makeReq(BINARY_INFORM, xml)
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

    DL->>DL: 准备目录、临时文件路径

    loop 最多重试 3 次
        DL->>FC: refreshNonce() (重试时)
        DL->>REQ: createBinaryInit(fileName, nonce)
        REQ-->>DL: BinaryInit XML
        DL->>FC: makeReq(BINARY_INIT, xml)
        FC->>Sammy: POST BinaryInitForMass.do
        Sammy-->>FC: OK

        DL->>FC: downloadFile(path, dest, size)
        FC->>FC: HEAD 请求探测 Content-MD5
        FC->>Sammy: GET NF_SmartDownloadBinaryForMass.do
        Sammy-->>FC: 加密固件数据流
        FC-->>DL: Ketch 多连接下载
        DL-->>UI: 进度回调 (bytes/sec)
        alt 401 认证错误
            FC-->>DL: KetchError (401)
            DL->>DL: 刷新 Nonce 后重试
        else 下载完成
            break
        end
    end

    DL->>DL: CRC32 校验 (如服务器提供)
    DL->>DL: MD5 校验 (如服务器提供)
    alt Android + 中转目录
        DL->>DL: 复制文件 临时 → 目标
    end
    DL->>DL: AES-ECB 解密 (.enc2/.enc4)
    DL->>DM: cleanupTempFiles()
    DL-->>UI: 完成 / 错误信息
    UI-->>User: 显示结果
```

---

## 当前已知问题

| 等级 | 问题 | 位置 | 状态 |
|------|------|------|------|
| 🔴 关键 | 成功下载后 `cleanupTempFiles()` 删除最终解密固件 | `DownloadModel.onEnd()` → `cleanupTempFiles()` | 未修复 |
| 🔴 关键 | 暂停按钮不影响 Ketch 网络下载 | `DownloadView.kt` toggle → `isPaused` flag | 未修复 |
| 🟡 中等 | `exception.message!!` 可能 NPE | `Downloader.kt:54` | 低风险，建议加固 |
| 🟡 中等 | 文件名可能超过 255 字符限制 | `Downloader.kt:86-89` | 未修复 |
| 🟡 中等 | `start` 参数语义矛盾：仅 MD5 探测使用，Ketch 自行管理断点续传 | `FusClient.kt:206-208` | 设计问题 |
| 🟢 轻微 | 暂停检查使用 busy-wait `while(delay(100))` | `Downloader.kt` 4 处 | 可优化为 StateFlow 挂起 |

## 已修复问题

| 问题 | 修复内容 | 版本 |
|------|----------|------|
| `downloadDirectory` 空指针 | 增加 null 检查，提前 return | v2.2.0 |
| `v4Key?.first!!` NPE | 改为 `if (info.v4Key != null)` 安全分支 | v2.2.0 |
| Ketch `while(true)` 无限循环 | 有界为最多 3 次重试后抛异常 | v2.2.0 |
| 请求头中包含 `Set-Cookie`（只应是响应头） | 移除 | v2.2.0 |
| MD5 探测使用 GET 请求 | 改为 HEAD 请求 | v2.2.0 |

## 代码变更记录

| Commit | 日期 | 内容 |
|--------|------|------|
| `d7570826` | 2026-06-24 | 暂停/恢复按钮、临时文件清理、401 重试循环 |
| `b831bb77` | 2026-06-24 | changelog 更新 |

## 改进建议

### P1 - 关键缺陷修复

1. **修复 `cleanupTempFiles()` 误删成功文件**
   成功路径上的最终文件（`decFile`、`decKeyFile`）不应被 cleanup。在调用 `endJob(MR.strings.done())` 之前从 `_tempFiles` 中移除它们，或者将最终文件的跟踪与临时文件分开管理。

2. **暂停按钮真正暂停 Ketch 下载**
   当前暂停仅暂停进度回调，Ketch 仍在后台下载。需要维护一个对当前 Ketch `DownloadTask` 的引用，在暂停时调用 `task.pause()`，恢复时调用 `task.resume()`。

### P2 - 代码健壮性

3. **`exception.message!!` → `exception.message ?: ""`** — 防御性编程，防止未来异常层级变更
4. **文件名长度截断** — 对 `fullFileName` 增加长度校验，超出时截断版本号部分
5. **`start` 参数语义清理** — 移除未使用的 `start` 参数，或在 Ketch 任务恢复时显式使用

### P3 - 性能与架构

6. **暂停 busy-wait 改为挂起式**
   ```kotlin
   // 用 StateFlow.first {} 挂起，避免每 100ms 轮询
   model.isPaused.first { !it }
   ```
7. **协程作用域管理** — `CoroutineScope(currentCoroutineContext()).launch()` 改用 `supervisorScope { launch { ... } }`

## 错误处理对照

| 错误场景 | 处理方式 | 代码位置 |
|----------|----------|----------|
| 服务器返回 400/401 | Nonce 过期，自动刷新后重试 | `FusClient.kt` / `Downloader.kt` |
| 服务器返回 403 | 设备未找到或固件不可用，提示用户 | `Request.kt` / `Downloader.kt` |
| 服务器返回 408 | IMEI/序列号无效 | `Request.kt` |
| BinaryInit 后下载 401 | 最多重试 3 次，刷新 Nonce 后重新 Initialize | `Downloader.kt` `performDownload()` |
| 版本不一致 | 弹出确认对话框，由用户决定是否继续 | `Downloader.kt` `onDownload()` |
| CRC32 校验失败 | 终止流程，提示错误 | `Downloader.kt` `performDownload()` |
| MD5 校验失败 | 终止流程，提示错误 | `Downloader.kt` `performDownload()` |
| 解密失败 | 抛出异常，显示错误信息 | `Downloader.kt` `performDownload()` |
| 用户取消下载 | 暂停 Ketch 任务，清理临时文件 | `DownloadView.kt` / `DownloadModel.kt` |
| 下载失败（Ketch 内部重试用尽） | 最多重试 3 次，超出抛异常 | `FusClient.kt` `downloadFile()` |
