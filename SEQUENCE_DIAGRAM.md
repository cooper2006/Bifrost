# 下载流程时序图

```
UI (DownloadView)        DownloadModel        Downloader           Request           FusClient           Ketch/HTTP        FileManager        CryptUtils
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
     |                       |                      |                   |                  |                    |                  |                    |
     |                       |                      |                   |                  |-- makeReq(BINARY_INIT)->|             |                    |
     |                       |                      |                   |                  |<-- 200 OK ----------|                  |                    |
     |                       |                      |                   |                  |                    |                  |                    |
     |                       |                      |                   |                  |-- downloadFile() -->|                  |                    |
     |                       |                      |                   |                  |                    |-- split into 8 chunks |                    |
     |                       |                      |                   |                  |                    |-- GET Range:0-XXXXX--->|                    |
     |                       |                      |                   |                  |                    |-- GET Range:XXXX-XXXX->|                    |
     |                       |                      |                   |                  |                    |-- ... 6 more parallel requests              |
     |                       |                      |                   |                  |                    |<-- 206 Partial Content (8x)  |
     |                       |                      |                   |                  |                    |-- write to chunk temp files     |
     |                       |                      |                   |                  |                    |<-- progress callbacks --------|
     |                       |                      |                   |                  |                    |-- concatenate chunks ----->|
     |                       |                      |                   |                  |                    |<-- encFile written --------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- check CRC32 ----->|
     |                       |                      |                   |                  |                    |                    |<-- CRC OK --------------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- check MD5 --------->|
     |                       |                      |                   |                  |                    |                    |<-- MD5 OK ---------------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- decrypt --------->|
     |                       |                      |                   |                  |                    |                    |<-- decFile written ------|
     |                       |                      |                   |                  |                    |                    |                    |
     |                       |                      |                   |                  |                    |                    |-- auto-delete enc? --->|
     |                       |                      |                   |                  |                    |                    |<-- cleanup --------------|
     |                       |                      |<-- done() --------|                  |                    |                  |                    |
     |<-- Progress Update ---|<-- progress.value ---|                   |                  |                    |                  |                    |
     |<-- Status Update -----|<-- statusText.value -|                   |                  |                    |                  |                    |
     |                       |                      |-- endJob -------->|                   |                    |                  |                    |
     |                       |                      |                   |                  |                    |                  |                    |
```

## 关键节点说明

| 阶段 | 说明 |
|------|------|
| 1. BinaryFileInfo 获取 | 通过 BINARY_INFORM 请求获取固件信息（文件名、大小、CRC32、v4Key） |
| 2. BINARY_INIT 认证 | 向 FUS 服务器发起初始化认证，获取 session |
| 3. 多线程下载 | 将文件按 8 路分片，每片独立 Range 请求，并行下载到临时文件 |
| 4. CRC32 校验 | 解密前校验加密文件的 CRC32 |
| 5. MD5 校验 | 校验文件 Content-MD5（来自 HTTP 头） |
| 6. 文件复制 | 如果 tempDirectory != downloadDirectory，复制到目标目录 |
| 7. 解密 | 使用 v4Key 对加密文件进行 AES 解密 |
| 8. 清理 | 可选自动删除加密文件 |
