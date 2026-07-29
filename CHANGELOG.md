## [Unreleased]

### Added
- **DownloadStateMachine**：基于 `DownloadPhase` sealed interface 的下载状态机，单 `StateFlow<DownloadPhase>` 驱动 UI 更新，替代原有的 `progress`/`speed`/`statusText` 三字段方案
- **`Phase` 枚举与 `Progress` 数据类**：定义下载全生命周期的 10 个状态（Idle/FetchingInfo/BinaryInit/Downloading/VerifyingCrc/VerifyingMd5/Copying/Decrypting/Done/Error），每阶段携带 `Progress(current, max, bytesPerSecond)`
- **`JobManager`**：独立协程生命周期管理器，使用 `SupervisorJob + Dispatchers.IO`，通过 `invokeOnCompletion` 自动检测是否有活跃协程
- **`BaseModel.jobManager`**：将协程管理从 `BaseModel._jobs` 列表迁移到 `JobManager` 实例，`launchJob`/`endJob` 委托给 JobManager
- **`authMutex` 线程安全**：FusClient 中 `nonce`/`auth`/`sessionId` 三字段通过 `kotlinx.coroutines.sync.Mutex` 保护，`makeReqWithRetryCheck` 在整个请求过程中持有锁
- **`Retry.kt` 通用指数退避重试**：`retryWithBackoff<T>()` 替代所有手写重试循环，支持可配置的 `maxRetries`/`initialDelay`/`maxDelay`/`retryable` 异常条件判断
- **Phase 3 单元测试**：4 个测试文件共 117 个 JVM 测试覆盖 Request/CryptUtils/VersionFetch/DownloadStateMachine 组件
- **测试 XML 夹具**：`binary_inform_response.xml`、`binary_init_request.xml`、`history_response.xml` 测试资源文件

### Changed
- **`Downloader.performDownload()` 拆分为 7 个阶段方法**：`buildDownloadContext()`、`writeDecryptionKey()`、`phaseBinaryInitAndDownload()`、`phaseVerifyCrc32()`、`phaseVerifyMd5()`、`phaseCopyFile()`、`phaseDecrypt()`、`phaseCleanup()`，每个方法单一职责、返回 `Result` 或 `Boolean`
- **`DownloadContext` 不可变上下文**：在 `performDownload` 初始化阶段一次性构造，各阶段方法共享，避免重复从 model 读取字段
- **BinaryInit + 下载重试使用 `retryWithBackoff<String?>`**：替代手写 while 循环，指数退避延迟（初始 1s，上限 10s）
- **`FusClient.makeReq()` 使用 `retryWithBackoff<String>`**：替代手写递归 401 重试，避免 FusClientLegacy 的无限递归 bug
- **`FusClient.getAuthV()`/`getNonce()`/`refreshNonce()`/`generateNonce()` 全部通过 `authMutex.withLock` 保护**
- **暂停检查提取为公共函数**：`waitWhilePaused()` 扩展函数替代 4 处重复的 while+delay 代码块
- **移除 `retryWithBackoff` Unit 重载**：JVM 签名冲突问题，所有调用处使用显式泛型参数如 `retryWithBackoff<String>(...)`

### Fixed
- **`retryWithBackoff` JVM 签名冲突**：Unit 重载与泛型重载的 JVM 签名相同，移除 Unit 重载，调用者使用显式类型参数
- **`FusClientLegacy.generateNonceInternal` 缺参数编译错误**：`makeReqInternal(Request.GENERATE_NONCE)` 缺少 `data`/`signature`/`includeNonce` 参数，补充 `""`/`null`/`false`
- **`FusClient`/`Downloader` 中 `retryWithBackoff` 泛型推断失败**：编译器无法推断 T，添加显式类型参数
- **`Downloader.kt` 中暂停检查的 4 处代码重复**：提取为 `waitWhilePaused()` 公共扩展函数
- **暂停检查不再循环在 Ktor 回调外阻塞**：`waitWhilePaused` 仅在进度回调内使用，不阻塞下载阶段切换
- **`FusClientLegacy.makeReqInternal` 缺失超时**：架构改进时遗漏，现补充 60s/30s/15s 超时，与 `FusClient` 保持一致
- **`Downloader.onFetch` 缺少外层超时保护**：添加 `withTimeout(120s)` 作为 Ktor 超时的双重保险，防止三星服务器无响应时进度条无限旋转
- **`JobManager.cancelAll` 增加调试日志**：记录取消的 Job 数量和状态变化，便于排查进度条不消失的问题

## [2.1.3] - 2026-07-28

### Added
- **暂停/恢复按钮**：在下载器标签页中，固件下载时可切换暂停，单击即可恢复
- **自动清理临时文件**：下载取消或完成时，从磁盘删除临时的加密固件文件
- 暂停/恢复按钮的 `pause.svg` / `play.svg` 图标
- **HTTP Range 断点续传**：失败/中断的下载现在通过 `Range: bytes={start}-` 从当前文件偏移继续，而非从头开始
- **Socket 超时处理**：下载在 60 秒 socket 空闲后中止并自动重试/续传（此前在连接静默断开时会无限阻塞）
- `ParallelDownloader`：分块并行下载引擎，使用 `FileChannel` positioned write 和分块级 401 重试（已实现，尚未接入主流程）
- `FusClient.downloadFile()` 新增 `onAuthRefresh` 参数：下载过程中返回 401 时调用回调以重新建立下载会话（先刷新 nonce，再发送 BinaryInit）
- `DownloadModel.endJobSuccess()` 方法：显式标记任务成功完成，解决此前通过 `text == "done"` 硬编码字符串判断成功的不可靠问题
- **专用异常类型**：新增 `AuthExpiredException`、`DownloadTimeoutException`、`ConnectionClosedException`，替代通过异常消息字符串匹配判断错误类型的方式
- **`copying` 字符串资源**：将硬编码的 "Copying" 状态文本本地化（base + zh-rCN）
- **`CommonLock` 跨平台锁**：基于 expect/actual 的非挂起锁工具类（JVM 用 synchronized，Darwin 用自旋锁）
- **`V4Key` 数据类**：将 `BinaryFileInfo.v4Key` 从 `Pair<ByteArray, String>` 改为专用 `V4Key` 类，正确实现 `equals`/`hashCode`（用 `contentEquals` 比较 ByteArray 内容）

### Changed
- **用 Ktor 流式下载替换 Ketch**：`FusClient.downloadFile()` 现直接使用 Ktor HTTP 客户端；移除 Ketch，因为它在下载前会发 HEAD 请求，消耗 FUS auth 并导致后续请求失败
- **Nonce 刷新重试**：若三星在 `BinaryInit` 与实际文件下载之间返回瞬时 401，应用会重新生成 FUS nonce 并重试（最多 10 次，原为 3 次）而非立即失败；重试现在还覆盖 socket 超时和连接断开错误
- **临时文件清理策略**：`DownloadModel.onEnd()` 现仅在成功或用户取消时清理临时文件；失败时保留已下载部分，以便下次尝试续传
- 重构 `Downloader.performDownload()` — 将文件路径解析移出 try 块，减少嵌套
- 在整个下载路径中添加 `[BifrostDownload]` 前缀的诊断日志
- Gradle wrapper 版本升级到 9.6.1（与本地安装版本一致）
- **`IFusClient` 默认实现**：`downloadFile` 默认实现现调用接口自身的抽象方法（`getAuthV()`、`getDownloadUrl()`），而非直接依赖 `FusClientLegacy` 具体类
- **统一超时配置**：`IFusClient` 默认实现中 Android 分支的 socket/connect 超时从无限改为 60 秒/30 秒，与 `FusClient` 保持一致
- **暂停检查提取为公共函数**：`waitWhilePaused()` 扩展函数替代 4 处重复的 while+delay 代码块
- **统一日志输出**：将全代码库剩余的 `println` 诊断输出全部替换为 `BifrostLogger`（SLF4J）模块分级日志，支持按模块和级别过滤

### Fixed
- 从 `common/build.gradle.kts` 和 `desktop/build.gradle.kts` 移除未使用的 `jvmToolchain`
- 将 `java.sql` 模块加入桌面端 JVM 模块列表（SQLite 所需）
- 从下载请求头中移除 auth token 的调试 `println`
- `FusClient.nonce` / `auth` / `sessionId` 字段添加 `@Volatile` 注解，确保协程跨线程可见性
- `desktop/build.gradle.kts` Skiko 路径检测改为跨平台（支持 macOS / Linux / Windows）
- `ParallelDownloader` 添加 `@Deprecated` 标注（多线程下载速度不稳定，已改用单线程流式下载）
- `getAuthV()` 修复变量遮蔽问题（本地 nonce 重命名为 `effectiveNonce`）
- 连接断开异常检查排除 `FileSystemException`，避免将文件系统权限错误误判为可恢复的网络错误
- **`FusClient.makeReqInternal` 缺少超时**：三星 FOTA 服务器不响应时，POST 请求（GenerateNonce、BinaryInform、BinaryInit）未设置 socket/connect timeout 导致 Ktor 无限挂起。现添加 60s/30s/15s 超时
- **`Downloader.onDownload` 缺少异常处理**：`retrieveBinaryFileInfo` 或 `performDownload` 中任何未捕获的异常（如服务器无响应导致的超时）都会让 indeterminate 进度条无限旋转、永不结束。现增加 try-catch，任何异常都会调用 `endJob` 显示错误信息
- **`FusClientLegacy.makeReq` 无限递归**：401 响应时无重试上限，现添加 `makeReqWithRetry` 限制最多 3 次重试
- **`FusClient.downloadFile` NPE 风险**：`dest.openOutputStream(true)!!` 改为抛出带上下文的 `IOException`
- **`DownloadModel._tempFiles` 线程安全**：改用 `synchronizedList`，`cleanupTempFiles` 在 `synchronized` 块中执行复合操作
- **CRC32/MD5 校验失败时未清理临时文件**：校验失败时现主动调用 `cleanupTempFiles()`，避免损坏文件残留磁盘
- **异常分类改用类型判断**：`FusClient` 和 `Downloader` 中 401/超时/连接断开的判断从字符串匹配改为 `is` 类型判断
- **`Decrypter.onDecrypt` 非空断言**：移除 2 处 `!!`，未选择文件或 v4Key 缺失时返回友好错误提示而非崩溃
- **`Request.createBinaryInit` 文件名越界**：添加长度检查，文件名过短时返回空 logicCheck 而非抛异常
- **`BaseModel._jobs` 并发安全**：使用 `CommonLock` 保护 `launchJob` 和 `endJob` 的读改写操作
- **`CryptUtils.checkMD5` 重复关闭流**：移除 `checkMD5` 中的重复 `.close()`，统一由 `calculateMD5` 的 finally 块关闭
- **`CryptUtils.Legacy.unpad` 填充值未验证**：添加 1-16 范围检查，非法填充值时返回原数据而非越界
- **`CryptUtils.kt` 损坏注释**：清理 git diff 残留的 `@@ -139,10 +163,8 @@` 标记
- **`VersionFetch` 错误解析 NPE**：`Code`/`Message` 字段改用安全调用，缺失时返回 "Unknown"
- **`Request.generateInfo` 非空断言**：`MODEL_PATH` 和 `LOGIC_VALUE_HOME` 的 `!!` 改为抛出带上下文的 `IllegalStateException`
- **`ProgressUtils.streamOperationWithProgress` 资源泄漏**：用 try/finally 包裹，确保异常/取消时关闭 input/output 流
- **`DecryptView` 文件拖拽 NPE**：`getParent()!!` 改为安全调用，无父目录时跳过而非崩溃
- **`PatreonSupportersParser` 网络失败崩溃**：JSON 解析移入 try/catch，网络或解析失败时返回空列表而非崩溃
- **`EventManager` / `PatreonSupportersParser` 单例线程安全**：改用 `@Volatile` + `CommonLock` 双重检查锁定
- **`History.kt` 字符串截取越界**：`substring(lastIndex - 3)` 添加长度检查，过短时返回原字符串
- **`FetchResult.ignoredCodes` 可变数组**：`arrayOf` 改为 `setOf` 不可变集合
- **`GlobalScope` 替换为 `CoroutineScope`**：`Settings.kt`、`IMEIGenerator.kt`、`CSCDB.kt` 中的 `GlobalScope.launch` 替换为 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，避免协程泄漏风险
- **全代码库 `e.printStackTrace()` 替换为 `BifrostLogger`**：18 处 `printStackTrace` 全部替换为带异常参数的 `BifrostLogger.warn/error` 调用，覆盖 commonMain、jvmMain、iosMain、androidMain 及 desktop 入口
- **`Downloader.kt` 非空断言修复**：`exception.message!!` 和 `info!!` 替换为空安全判断，避免 NPE 崩溃
- **`VersionFetch.kt` 非空断言修复**：`BINARY_SEQUENCE` 的 `toInt()!!` 改为 `toIntOrNull() ?: 0`
- **`FileManager.jvm.kt` 临时目录 NPE 修复**：`parentFile!!` 改为安全调用，无法获取时返回 null
- **`UrlHandler.ios.kt` URL 空指针修复**：`NSURL.URLWithString(url)!!` 改为安全调用，无效 URL 时记录警告
- **`IMEIGenerator.kt` 资源加载 NPE 修复**：`MR.files.tacs_csv()!!` 改为安全调用，资源缺失时记录警告
- **`Request.kt` 索引越界保护**：`dataIndex!!` 改为空安全和越界判断，索引无效时自动降级到首元素

---

# 2.1.0
- 在 Android 上增加使用 File 框架的选项（需要 All Files 访问权限，在设置标签页中查看）。
- 改进对具有多个变体字符的型号（如 SM-J710FN）的固件版本匹配。
- 实现新的端点用于检查最新固件版本。
- 使用同一端点显示固件历史，而非抓取 Samfrew。

# 2.0.0
此版本有一些较大的变动，因此 Bifrost 升级到 v2。详情见下。
- 现在可以再次在无需提供 IMEI 或序列号的情况下下载固件！
  - 仍欢迎并鼓励提交 TAC；我不确定三星是否会或何时封锁此方式。
- 同样的改动也允许下载大多数手表固件。某些 CSC 没有可用固件，因此如果看到错误，请尝试其他 CSC。
- 手动下载旧固件版本也应再次可用。这并非对所有设备或固件版本都有效，但你不再被强制只能下载最新版。
- 下载现在使用多个同时连接以提升速度。
  - Android 必须先下载到 Bifrost 的内部数据目录，再复制到所选目录以支持多连接。这似乎仍比单连接直连更快，但可能会有变化。
- 更新 TAC。

# 1.20.5
- 解密和文件校验现在应快很多。
- 关闭并重新打开 CSC 选择器对话框时记住滚动位置。
- 修复历史记录获取。
- 更新 TAC。
- 更新依赖。

# 1.20.4
- 改进无效 CSC 或缺失固件的错误提示。
- 在 macOS 26 上更新透明外观。
- 修复若干崩溃。
- 更新 TAC。
- 更新依赖。

# 1.20.3
- 增加在 iOS 上运行的支持。
- 在关于页面增加捐赠按钮。
- 修复 Debian 上音频库依赖的问题。
- 应用运行期间记住 CSC 对话框状态（搜索内容、排序）。
- 使下载响应文本可选。
- 更新逻辑，对 SM-L* 手表及 SM-R* 设备都显示下载警告。
- 增加 TAC。

# 1.20.2
- 修复在 Linux 上选择目录可能毫无反应的问题。
- 致力于解决移动窗口跨显示器时会重置的问题。
- 更新 TAC。
- UI 调整。

# 1.20.1
- 更新 FileKit，修复在 Linux 上确认保存对话框无效的问题。
- 更新 Compose。
- 增加 TAC。

# 1.20.0
- 在 macOS 上增加启用 Vibrancy 效果的选项。
- 更新 TAC。
- 修复崩溃。
- 修复一些 UI 问题。

# 1.19.9
- 修复打开某些对话框导致的崩溃。

# 1.19.8
- 切换到 Samfrew 获取固件历史。
- 避免多次检查历史时崩溃。
- 修复在桌面端将无效文件拖到窗口导致的崩溃。
- 提升 I/O 函数的性能与效率。
- 增加并更新 TAC。

# 1.19.7
- 修复一个崩溃。

# 1.19.6
- 增加 Windows ARM64 原生支持。
- 生成 IMEI 时不再将 U 和 U1 变体视为相同。
- 修复一些更新日志解析问题。
- 更新 TAC。
- 更新翻译。
- 更新依赖。

# 1.19.5
- 修复在较旧 Windows 版本上检查 Bifrost 是否在 ARM Windows 上以 x86 模拟运行时导致崩溃的问题。
- 修复在较旧 Windows 版本上获取强调色导致应用崩溃的问题。
- 增加更多 TAC。
- 更新翻译。
- 更新依赖。

# 1.19.4
- 修复 Linux 文件选择器问题。
- 更新翻译。
- 更新 TAC。
- 更新依赖。

# 1.19.3
- 文件管理改进，提升 UX 并修复 1.19.2 中的一些问题。
- 移除在 Windows 上显示 Mica 效果的选项（因已损坏）。
- 增加 TAC。
- 更新翻译。

# 1.19.2
- 修复在 ARM Windows 上启动的问题。
- 修复 Linux 上文件选择器窗口空白。
- Linux 主题检测修复。

# 1.19.1
- Linux 图形修复。
- 更新 TAC。
- 更新依赖。

# 1.19.0
- 增加应用内更新检查。
- 更新翻译。
- UI 调整。
- 更新错误报告。

# 1.18.15
- 通过更新到 Java 21 修复在非 ASCII 路径下于 Windows 启动的问题。
- 降低下载固件时的 CPU 占用。
- 更新依赖。

# 1.18.14
- 解决在 Raspberry Pi 上启动时应用崩溃的问题。
- 更新翻译。
- 更新依赖。

# 1.18.13
- UI 调整。
- 更新 Compose。
- 增加更多 TAC。
- 在 macOS 上增加 "About Bifrost" 处理器，使其不再显示默认 Java 窗口。

# 1.18.12
- 在 Windows 上禁用控制台窗口。
- UI 调整。
- 移除 Twitter 链接。
- 更新 TAC。
- 更新依赖。

# 1.18.11
- 修复 Android 8.0 之前与日期解析相关的崩溃。
- 修复桌面端与无障碍 API 相关的崩溃。
- 改进 Android 上的 edge-to-edge 外观。
- 增加更多 TAC。
- 更新依赖。

# 1.18.10
- Bifrost 现在会尝试以应用当前语言获取固件更新日志。
- 用合适的库替换更新日志的手动 HTML 格式解析。
- 希望改进桌面端错误报告。
- 忽略更多错误。
- 修复配件下载错误未正确忽略的问题。
- 修剪固件请求体值。
- 移除公共代码中的一些 JVM 专属 API。
- 更新 Kotlin。
- 更新 Compose。

# 1.18.9
- 修复在 macOS 上关闭窗口时未退出应用的问题。

# 1.18.8
- 改进桌面端错误报告。
- 更新 Compose。
- 更新 AGP。
- 更新 Gradle。
- 更新 TAC。
- 代码清理。

# 1.18.7
- 修复桌面端关闭应用时窗口闪烁的问题。
- 修复带撇号的字符串显示反斜杠。
- 更新 Compose。
- 更新 Kotlin。
- 清理错误报告。

# 1.18.6
- 修复 CSC 和 TAC 的实时 URL。
- 为 One UI 6 上的正确动态颜色添加变通方案。
- 清理。

# 1.18.5
- 崩溃修复。
- 更新到 Kotlin 2.0.0。
- 更新 Compose。
- 更新 TAC。
- 更新依赖。
- 清理一些不需要的依赖。

# 1.18.4
- 仅在设备有 IMEI 时显示 TAC 信息和报告按钮。
- 修复若干崩溃。
- 更新翻译。
- 清理错误报告。

# 1.18.3
- 桌面端构建热修复。

# 1.18.2
- 修复版本不匹配警报被忽略仍发起下载的问题。
- 修复版本不匹配检测的误报。
- 更新 TAC。
- 更新 Compose。
- 更新翻译。

# 1.18.1
- 通过将请求逻辑与下载器统一，改进独立解密可靠性。
- 更新翻译。
- 更多错误报告过滤。

# 1.18.0
- 增加在下载期间保存固件解密密钥的设置，可在之后离线解密使用。
- 在解密器中增加"解密密钥"字段，可离线解密。
- 改进在线解密请求清理。
- 进一步提升下载速度。
- 减少错误报告。
- 更新 TAC。
- 更新翻译。

# 1.17.11
- 修复 Android 上的明文错误。
- 修复已服务版本检查的一些问题。
- 增加速率限制，尝试避免遍历生成的 IMEI 时触发三星的验证码。
- 更新翻译。
- 减少错误报告。

# 1.17.10
- 下载和解密速度在大多数情况下应有所提升。
- 修复已服务版本检查失败导致下载失败的问题。
- 增加输入以 "SM-R" 开头型号时的警告，指出只能下载平板和手机固件。
- 将之前导致崩溃的下载错误显示在 UI 中。
- 使用 Ksoup 进行 XML 解析，避免响应格式错误导致的崩溃。
- 增加更多 TAC。
- 更新翻译。
- 更新依赖。
- 更新 Compose。

# 1.17.9
- UI 修复。
- 更新翻译。
- 增加更多 TAC。
- 崩溃修复。

# 1.17.8
- 为无效 IMEI 或序列号返回的 408 错误增加更清晰的提示。
- 增加 IMEI 生成的另一个虚拟序列号。
- 增加更多 TAC。
- 清理一些 TAC 关联。
- 更新对话框实现。
- 崩溃修复。
- 更新翻译。

# 1.17.7
- 因 123456 和 012345 不再适用于所有情况，增加 011111 虚拟序列号。
- 增加美国 S24 的 TAC。
- TAC 清理。
- 更新翻译。

# 1.17.6
- 修复 Android 上获取设备 IMEI 时的崩溃。
- TAC 为空时隐藏 TAC 信息。
- UI 调整。
- 更新 Compose。
- 更新 TAC。
- 更新翻译。

# 1.17.5
- 再次检查最新固件时清除状态文本和当前更新日志。
- 修复 Android 上与下载器 Service 相关的崩溃。
- 使更多字符串可翻译。
- 增加德语翻译。
- 增加简体中文翻译。
- 增加完整的土耳其语翻译。
- 崩溃修复。
- 更多 TAC 清理。
- 代码清理。

# 1.17.4
- 尝试修复 Android 上检查更新后崩溃的问题。
- 尝试修复 Windows 和 Linux 上加载应用图标时崩溃的问题。
- 增加法语翻译。
- 增加部分土耳其语翻译。
- 增加滚动指示器。
- 文本溢出时将标签页导航按钮图标化。
- 改进错误报告。
- UI 修复。

# 1.17.3
- 增加将文件从文件管理器拖到解密视图文件字段的能力。
- 增加在 Android 上为 Bifrost 设置应用内语言的能力。
- 修复设置文本渲染颜色错误的问题。
- 增加西班牙语翻译。
- 增加葡萄牙语翻译。
- 代码清理。

# 1.17.2
- 修复 Android 上尝试显示进度条时的崩溃。

# 1.17.1
- 使页面间滚动更流畅。
- 修复错误后显示三星服务器结果时的字符损坏。
- 增加更多 TAC。
- 修复 Weblate 翻译状态。
- 从 Jsoup 迁移到 Ksoup 进行 HTML 解析。
- 更新 Compose。
- 大量代码清理。

# 1.17.0
- 在 Windows 11 上增加应用 Mica 效果的选项。
- 增加 KDE 和 LXDE 的强调色检测（需重启应用以响应变化）。
- 增加通用字体映射工具，提升 Linux 上的渲染可靠性。
- 修复 Windows 10 上窗口样式问题。
- 崩溃修复。
- 代码清理。
- 增加 S24 Ultra（U/U1、B）的一些 TAC。
- 清理 TAC 数据库。

# 1.16.14
- 实现一个变通方案，在 Compose API 更新前防止文本字段中光标跳动。

# 1.16.13
- 修复 CSC 选择器中的排序。
- 将虚拟 IMEI 序列号减少到仅 123456 和 012345。
- 修复启动时覆盖已保存 IMEI/序列号字段值的问题。
- 更新 Windows 上的窗口样式。
- 修复文本中不应出现的反斜杠。
- 提升 Bifrost 打开且长时间不活动时的下载可靠性。
- 增加更多 TAC。
- 崩溃修复。

# 1.16.12
- 修复 Android 上的崩溃。

# 1.16.11
- 在 IMEI 字段增加编辑对话框并更改字段显示方式。
- 崩溃修复。
- 布局修复。
- 将 CSC 数据库迁移到 CSV 文件，方便他人访问。
- 增加获取远程 CSC 数据库的逻辑，无需版本更新即可添加 CSC。

# 1.16.10
- 崩溃修复。
- 更新 CSC 数据库。
- 将生成的 IMEI 限制为 10 个。

# 1.16.9
- 重做设置模型，希望能减少文本错乱。
- 在"更多"下增加删除设置数据的选项。

# 1.16.8
- 增加更多 TAC。
- 远程获取期间依赖本地 TAC 数据库。
- 重做解析逻辑以支持每个型号多个 TAC。
- 增加更多虚拟序列号尝试。
- 增加重试逻辑，遍历所有提供的 IMEI 直到有一个可用。

# 1.16.7
- 增加更多 TAC。
- 尽可能从 GitHub 获取最新 TAC 数据库，避免新增 TAC 时必须更新 Bifrost。

# 1.16.6
- 增加基于输入型号生成 IMEI 的 IMEI TAC 数据库。
  - 如果输入的型号在数据库中，应自动填充 IMEI。
- 在 Android 的"更多"页面增加显示 TAC 和型号并允许复制的区块。
  - 如果你的 IMEI 未用 TAC 和型号自动填充，请[提交 issue](https://github.com/zacharee/SamloaderKotlin/issues/new?assignees=&labels=&projects=&template=imei-database-request.md&title=)。

# 1.16.5
- 增加 IMEI/序列号字段，需填入与请求固件匹配的值。

# 1.16.4
- 实现使用现代原生 Windows 文件选择器。
- 将页脚和设置移至专用页面。
- 增加设置说明。
- 将页面标签移至底部。
- 桌面端现在像 Android 一样滚动页面。
- 更新固件更新日志样式。
- 重做更新日志的 HTML 解析。
- 修复 Android 上历史记录页面显示不正常的问题。
- 修复下载进行中可切换手动模式的问题。
- 修复非原生文件选择器的样式。
- 移除桌面端的 About 和 Supporters 窗口。
- 使进度条动画更流畅。
- 默认启用原生文件选择器。
- 修复圆角应用图标。
- 增加合适的 macOS 应用图标。
- Android APK 名称改为小写。
- 代码清理。

# 1.16.3
- 增加一个可能是临时的变通方案来下载固件。
- 再次改进错误报告。
- 崩溃修复。

# 1.16.2
- 希望修复 Windows 上的主题异常。

# 1.16.1
- 在 macOS 和 Windows 上更新样式以包含标题栏。
- 为 Linux 实现更好的暗色模式检测。
- 增加桌面端自动暗色/亮色模式切换。
- 更新 Gradle。

# 1.16.0
- 增加自动删除加密下载文件的选项。
- 修复 EUX 和 EUY 区域下载（感谢 [@ananjaser1211](https://github.com/ananjaser1211)）。
- 修复桌面端一些崩溃。
- 更新 Bugsnag 错误报告以显示更多信息。
- 希望通过移除未使用的库来稍微减小下载体积。

# 1.15.2
- 迁移到使用 Conveyor 构建桌面端。
  - 自动生成 ARM64 macOS 构建。
  - 现在支持 ARM64 Linux。
- 更新 Android 发布文件名以包含版本号。
- 使用 moko-resources 进行翻译（为后续做准备）。
- 更新错误报告行为。
- 更新 Compose。
- 性能修复。
- UI 调整。
- 移除原生 macOS 和 JS 目标（从未发布）。

# 1.15.1
- 修复无连字符或含小写字符的型号下载。

# 1.15.0
- macOS 构建现在应完全签名并公证！
- 增加允许文本字段中小写字符的选项。
- 应用重启后持久化型号/区域/固件/手动值。
- 改进文本字段输入性能。
- 更新依赖。

# 1.14.3
- 修复桌面端启动崩溃。
- 迁移到 Korlibs 4。
- 修复无害的 SLF4J 错误。
- 其他崩溃修复。

# 1.14.2
- 修复下载 (#109)（感谢 @ananjaser1211）。
- 增加 Bugsnag 错误报告。
- 更新 Compose 到 1.5.10。
- 更新 CSC 选项。
- 增加 Bugsnag。
- 修复一些日期格式 (#112)（感谢 @Tostis）。
- 增加原生 ARM64 macOS 构建。

# 1.14.1
- 增加更多 CSC。
- 修复版本检查阻止下载的问题。
- 用 flow 替换一批型号状态。
- 操作运行时不允许 CSC 选择器对话框更改当前 CSC。

# 1.14.0
- 避免解析 Patreon 支持者失败时崩溃。
- 增加 CSC 选择器对话框，便于选择正确的 CSC 或挑选替代 CSC。
- 为 Android 12+ 增加 Material You 图标。
- 调整对话框行为和 UI。
- 增加 Mastodon 社交链接。
- 屏幕宽度低于 600dp 时将版本信息移至对话框。

# 1.13.1
- 修复 Android 上的崩溃和 UI 问题。

# 1.13.0
- 历史记录视图现在使用 LazyVerticalStaggeredGrid 而非手动非惰性实现。
- 升级到 Kotlin 1.8.0 和 Compose 1.3.x。
- 为 Android、Windows 和 macOS 增加动态主题。
- 迁移到 Material Design 3。
- 将最小窗口尺寸设为 200x300dp。
- 改进 Android 上的 pager 性能。
- 减小页脚图标按钮间距。
- 在下载视图中获取版本信息时显示加载指示器。
- 修复历史记录视图中最后一张卡片被截断的问题。
- 更新依赖。

# 1.12.0
- 允许在 Bifrost 检测到版本不匹配时继续下载。
- 改进版本不匹配检查。
- 改进对话框外观。
- 推进原生 macOS 版本。
- 更新依赖。

# 1.0.11
- 再次修复 Windows 资源问题。

# 1.0.10
- 暂时移除翻译，直到有更好的框架。
- 更新进度条布局并增加更多动画。
- 实现功能性的浏览器版本（仍无发布计划）。
- 改进文件句柄清理。
- 使用三星的 version.xml 页面增加历史记录回退。
- 更新依赖。
- 增加 Windows 资源加载的临时变通方案。

# 1.0.9
- 更新依赖。
- 更新到 Kotlin 1.7.0。
- 增加泰语翻译。
- 修复操作卡住/挂起的问题。
- 增加一些动画。
- 增加一些缺失的字符串到资源文件。
- 为 web 和 macOS 原生创建初始（非功能性）Compose 版本。
- 使图片资源正确跨平台。
- 修复 Android 和 JVM 的构建。

# 1.0.8
- 更新依赖。
- 在 Android 上为屏幕键盘调整内容大小。
- 使 JS 版本再次可构建。
- 实现本地化框架。
- 增加俄语翻译。
- 仅在设置可用时显示设置齿轮。
- 在 Windows 上使用 OpenGL 渲染器。

# 1.0.7
- 更新依赖。
- 将字符串提取到变量。
- 修复校验已服务固件时的错误。
- 修复损坏的 Windows 图标。

# 1.0.6
- 更新依赖。
- 清理代码。
- 通过默认使用 JFileChooser 解决空白文件选择器问题。
- 增加新设置以切回使用 FileDialog。
- 将构建更新到 JDK 18。

# 1.0.5
- 使手动模式的版本比较更可靠。

# 1.0.4
- 代码清理。
- 在 Android 上实现水平 pager 以在视图间滑动。
- 更新 Compose 和 Kotlin。
- 重新启用手动固件输入：
  - 启用时会有警告。
  - Bifrost 会校验请求的固件与已服务固件是否匹配。
- 实现更好的错误提示。
- 修复 403 返回状态导致的崩溃。
- 修复 Samsung 文档 URL 为 null 时的崩溃。

# 1.0.3
- 为 macOS 实现一些菜单栏项。
- 增加 Patreon 支持者对话框。
- 修复 HTTP 请求无限挂起的问题。
- 清理代码。

# 1.0.2
- 修复 About 对话框导致 Linux 和 Windows 崩溃的问题。

# 1.0.1
- 修复 macOS 包名。
- 更新文件名。

# 1.0.0
- 重命名为 Bifrost。
- 更新图标和颜色。
- 修复 macOS 的 about 对话框。

# 0.5.3
- 更新窗口 API。
- 更新 Kotlin 到 1.5.31。
- 更新 Compose 到 1.0.0。
- 修复 Android 12 上的一些崩溃。
- 移除对手动 DPScale 的依赖。
- 修复 macOS 的暗色模式。
- 为 macOS 增加 about 对话框。
- 修复历史记录视图。
- 代码清理。
- 创建实验性浏览器版本（可能永远不会上线）。

# 0.5.2
- 更新依赖。
- 修复一些崩溃。
- 正确处理其他语言的更新日志。

# 0.5.1
- 更新依赖。
- 历史记录标签从 SamMobile 迁移到 OdinRom。
- 在历史项的更新日志中增加发布日期。
- 更新一些 UI 元素的外观。

# 0.5.0
- 为当前固件和历史记录标签中的项增加更新日志。
- 为历史记录标签使用交错网格（可能导致性能问题）。

# 0.4.1
- 移除对 Bintray 的依赖。
- 恢复历史记录标签，但不带下载按钮。
- 修复历史记录标签中的网格问题。
- 代码清理。
- 修复 Android <8.0 上的崩溃。

# 0.4.0
- 更新依赖。
- 移除手动固件下载。
- 移除历史记录标签。
- 清理代码。

# 0.3.2
- 后端重组以更好组织。
- 在 Android 上使通知显示进度。
- 更新依赖。
- 增加 macOS 构建。

# 0.3.1
- 更新依赖。
- 增加从历史项复制信息到下载和解密页面的能力。
- 检查最新更新时显示 OS 版本文本。
- 限制最大内容宽度。

# 0.3.0
- 增加新页面用于查看设备和区域组合的固件历史。
- 进行一些 UI 调整以改善桌面端显示。
- 减小页脚大小。
- 减小一些 padding 值。
- 更新依赖。

# 0.2.1
- 修复关闭应用时"Working..."通知不消失的问题。

# 0.2.0
- 更新 README 以包含 Android 构建说明。
- 增加 MIT 许可证。
- 使主内容可滚动。
- 修复屏幕旋转导致下载中断的问题。
- 重新排列一些按钮。
- 使布局略有响应性，带自动流式输入字段和混合图片/文本按钮。
- 在固件字段增加格式提示。
- 在 Android 上通过 Service 运行下载/解密。
- 其他杂项 UI 调整。

# 0.1.0
初始发布
