# 下载流程时序图

```
UI (DownloadView)        DownloadModel        Downloader           Request           FusClient           Ktor Streaming      FileManager        CryptUtils
     |                       |                      |                   |                  |                    |                  |                    |
     |-- Download Click ---->|                     |                   |                  |                    |                  |                    |
     |                       |-- launchJob -------->|                   |                  |                    |                  |                    |
     |                       |                      |-- onDownload ----|                  |                    |                  |                    |
     |                       |                      |                   |-- retrieveBinaryFileInfo             |                  |                    |
     |                       |                      |                   |                  |-- makeReq(GEN_NONCE)-->|                  |                    |
     |                       |                      |                   |                  |<-- nonce/auth -----|                  |                    |
     |                       |                      |                   |                  |-- makeReq(BINARY_INFORM)->|             |                    |
     |                       |                      |                   |                  |<-- BinaryFileInfo -|                  |                    |
     |                       |                      |                   |                  |                    |                  |                    |
     |                       |                      |<-- BinaryFileInfo |                  |                    |                  |                    |
     |                       |                      |                   |                  |                    |                  |                    |
     |                       |                      |-- performDownload |                  |                    |                  |                    |
     |                       |                      |  (loop 0-10x)     |                  |                    |                  |                    |
     |                       |                      |                   |                  |-- refreshNonce()   |                  |                    |
     |                       |                      |                   |                  |-- makeReq(BINARY_INIT)->|             |                    |
     |                       |                      |                   |                  |<-- 200 OK ----------|                  |                    |
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |                   |                  |-- downloadFile() -->|                    |                    |
     |                       |                      |                   |                  |  (start=已下载偏移)  |                    |                    |
     |                       |                      |                   |                  |                    |-- GET Range:start- --->|
     |                       |                      |                   |                  |                    |   (64KB buffer loop)  |
     |                       |                      |                   |                  |                    |<-- 206 Partial Content -|
     |                       |                      |                   |                  |                    |-- write to encFile --->|
     |                       |                      |                   |                  |                    |<-- progress callback -|
     |                       |                      |                   |                  |  (每500ms节流)      |                    |
     |                       |                      |                   |                  |  (每5秒日志进度)     |                    |
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |  alt 401          |                  |                    |                    |                    |
     |                       |                      |    onAuthRefresh( )|                 |                    |                    |                    |
     |                       |                      |    refreshNonce()  |                  |                    |                    |                    |
     |                       |                      |    makeReq(BINARY_INIT)              |                    |                    |                    |
     |                       |                      |    continue (续传) |                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |-- CRC32 check ---->|                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- CRC32 --------->|
     |                       |                      |                   |                  |                    |                    |<-- CRC OK --------------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |-- MD5 check (if md5 available)                                       |                    |
     |                       |                      |                   |                  |                    |                    |-- MD5 ----------->|
     |                       |                      |                   |                  |                    |                    |<-- MD5 OK ---------------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |-- decrypt -------->|                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- decrypt ------->|
     |                       |                      |                   |                  |                    |                    |<-- decFile written ------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |-- auto-delete enc?|                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- cleanup -------->|
     |                       |                      |                   |                  |                    |                    |<-- done ---------------|
     |                       |                      |<-- endJobSuccess -|                  |                    |                  |                    |
     |<-- Progress Update ---|<-- progress.value ---|                   |                  |                    |                  |                    |
     |<-- Status Update -----|<-- statusText.value -|                   |                  |                    |                  |                    |
     |                       |                      |                   |                  |                    |                  |                    |
```

## 关键节点说明

| 阶段 | 说明 |
|------|------|
| 1. BinaryFileInfo 获取 | 通过 BINARY_INFORM 请求获取固件信息（文件名、大小、CRC32、v4Key） |
| 2. BINARY_INIT 认证 | 向 FUS 服务器发起初始化认证，获取 session |
| 3. Ktor 单线程流式下载 | 移除 Ketch 后改用 Ktor 直接流式下载，64KB buffer 循环读取，支持 HTTP Range 断点续传。每 500ms 回调进度，每 5 秒日志记录 |
| 4. 401/超时/断连自动重试 | downloadFile 内置 onAuthRefresh 回调，收到 401 后刷新 nonce 并重新 BinaryInit；外层 performDownload 循环最多重试 10 次 |
| 5. CRC32 校验 | 解密前校验加密文件的 CRC32 |
| 6. MD5 校验 | 校验文件 Content-MD5（来自 downloadFile 返回值） |
| 7. 文件复制 | 如果 tempDirectory != downloadDirectory，复制到目标目录 |
| 8. 解密 | 使用 v4Key 对加密文件进行 AES 解密 |
| 9. 清理 | 可选自动删除加密文件 |
