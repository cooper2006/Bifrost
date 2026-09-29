# 上游合并记录（Upstream Merge Notes）

本文档记录 Bifrost fork（`cooper2006/Bifrost`）跟进上游 `zacharee/Bifrost` 的合并策略、冲突处理与未采纳项，便于后续继续跟进上游。

---

## 本次合并：2026-09-29

### 范围

| 项目 | 内容 |
|------|------|
| 上游 remote | `upstream` → https://github.com/zacharee/Bifrost |
| 上游分支 | `upstream/master` |
| 合并基点 | `d485f94b` |
| 上游提交数 | 26（自 2.1.4 起，HEAD 为 `ed73a034 Add TACs`） |
| 变更规模 | 477 个文件，+22684 / -5046 |
| 合并后版本 | `2.2.1`（`versionCode = 96`） |

### 上游主要变更

| 类别 | 内容 |
|------|------|
| 资源体系 | moko-resources → **Compose Resources**（`Res.string` / `Res.drawable` / `Res.readBytes`） |
| iOS 集成 | CocoaPods → **Swift Package Manager**（删除 `common.podspec`、`iosApp/Podfile`） |
| 依赖升级 | ktor 3.6.0、nsexception-kt 1.1.0、oshi 7.6.1、richeditor-compose 1.2.0、slf4j 2.0.20、Gradle 9.7.1 |
| 本地仓库 | 新增 `libs/`，承载上游自编译的 Ketch 构件（ktor / remote / sqlite / torrent） |
| 认证与下载 | `auth_params` 在每次启动与每次 nonce 生成时重新提取；legacy 下载回退；401 处理调整；下载流程统一 |
| 数据更新 | TAC 数据库、CSC 列表、Crowdin 翻译更新（上游移除了有问题的中文翻译） |
| 文档 | README 增加 Homebrew 安装方式 |

### 冲突处理原则

1. **工程结构跟随上游**：Compose Resources、Swift Package Manager、`libs/` 本地 Maven 仓库、Gradle 与依赖版本一律采用上游，降低后续合并成本。
2. **本地自研能力保留**：下载核心链路以本地实现为准（Ktor 单线程流式下载、下载状态机、阶段化 `Downloader`、`ParallelDownloader`、`BifrostLogger` 日志体系、`authMutex` 线程安全、统一超时）。
3. **本地化资产迁移而非丢弃**：中文翻译由 moko-resources 迁移到 Compose Resources 对应语言目录。
4. **上游语义修复择优吸收**：与本地实现不冲突的上游修复直接采纳（见逐文件决策表）。

### 逐文件决策

| 文件 | 处理方式 |
|------|----------|
| `common/build.gradle.kts`、`settings.gradle.kts` | 采用上游（Compose Resources + `libs/` 仓库 + 移除 `cocoapods {}`） |
| `build.gradle.kts` | 版本号取本地 `2.2.1` / `versionCode 96` |
| `gradle/libs.versions.toml` | 上游版本 + 保留本地测试依赖（`mockk`、`kotlin-test`、`kotlinx-coroutines-test`） |
| `gradle/wrapper/gradle-wrapper.properties` | 上游 9.7.1，保留腾讯云镜像下载 |
| `gradle.properties` | 上游配置 + 本机 JDK 路径改为注释 |
| `README.md` | 本地中文版 + 上游 Homebrew 安装段落 |
| `common/common.podspec`、`iosApp/Podfile(.lock)`、`.idea/SamloaderKotlin.iosApp.iml` | 采用上游删除（SPM 迁移） |
| `moko-resources/**`（21 个语言目录） | 采用上游删除，翻译迁移至 `composeResources/values-*/` |
| `composeResources/values/strings.xml` | 自动合并（含本地新增的 12 个 key） |
| `composeResources/values-zh-rCN/strings.xml` | 注入本地 131 条中文翻译 |
| `composeResources/drawable/pause.svg`、`play.svg` | 保留（本地暂停/恢复按钮使用） |
| `FusClient.kt` | 本地实现 + 上游 `AuthParamsHandler.extractFile()` 调用时机 |
| `FusClientLegacy.kt` | 本地实现（`authMutex` + 超时 + 日志 + `retryWithBackoff`） |
| `IFusClient.kt` | 本地实现（Ktor 默认下载实现，无 `createHeaders`/`createDownloadTask`），并按 Ketch 0.0.2-dev1 适配 `DownloadTask.request`：`StateFlow<DownloadRequest>`，取值需 `request.value.url` |
| `Request.kt` | 本地实现 + 上游 legacy `logicCheck` 计算方式 |
| `Downloader.kt` | 本地实现（状态机 + 阶段化方法） |
| `Decrypter.kt`、`History.kt`、`VersionFetch.kt`、`CSCDB.kt`、`IMEIGenerator.kt` | 本地实现 + 上游 `Res.*` 引用 |
| `SettingsAboutView.kt`、`DownloadView.kt`、`ResumeDownloadDialog.kt` | 本地 UI 改动 + 上游 `Res.*` 引用 |

### 未采纳的上游改动（有意保留本地实现）

| 上游改动 | 保留本地的原因 |
|----------|----------------|
| 以 Ketch 作为默认下载引擎（`IFusClient.createDownloadTask`） | Ketch 在下载前会发送 HEAD 请求消耗 FUS auth，本地已用 Ktor 流式下载替代 |
| `FusClientLegacy` 的 `USE_MODERN_AUTH` 认证方案 | 本地 legacy 路径仍沿用既有认证流程，切换需实机验证后再采纳 |
| `VersionFetch` 的 `Kiss2.0_FUS` 用户代理 | 判定为上游笔误，保留三星官方客户端标识 `Kies2.0_FUS` |
| `Downloader` 的 legacy 回退结构与 `println` 调试输出 | 本地阶段化实现已覆盖失败重试与 401 恢复，日志统一走 `BifrostLogger` |
| 上游移除的（有问题的）中文翻译 | 本地具备完整且经过校对的中文翻译 |

### 验证方式

本次合并通过以下静态与构建检查：

| 检查项 | 结果 |
|--------|------|
| 冲突残留（`git diff --diff-filter=U`、`<<<<<<<` 标记） | 0 |
| `MR.*` / `icerock` / `moko` 代码引用残留 | 0（`moko` 仅出现在历史 CHANGELOG 条目中） |
| `Res.string.*` / `Res.drawable.*` / `Res.readBytes(...)` 引用 vs 资源定义 | 0 缺失 |
| `Res.*` 引用所需的 import（`Res`、具体资源名、`util.invoke`） | 0 缺失 |
| `composeResources/values/strings.xml` key 总数 | 148（含本地新增 12 个） |
| `composeResources/values-zh-rCN/strings.xml` | 131 条中文翻译，0 遗漏 |
| `./gradlew :common:compileKotlinJvm` | ✅ BUILD SUCCESSFUL |
| `./gradlew :desktop:compileKotlinJvm` | ✅ BUILD SUCCESSFUL |
| `./gradlew :common:compileAndroidMain :android:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（3m30s） |
| `./gradlew :android:assembleDebug` | ✅ BUILD SUCCESSFUL（2m25s，APK 正常产出） |
| `:common:compileKotlinIos*` | 未验证（需下载 Kotlin/Native 工具链；且存在上述 slf4j 问题，见“后续跟进建议”第 4 条） |

环境：JDK 21、Android SDK Platform 37.0、Gradle 9.7.1（均由本次验证自动准备）。

### 构建验证中修复的问题

| 问题 | 处理 |
|------|------|
| `IFusClient.kt:70` `it.request.url` 编译失败 | Ketch 0.0.2-dev1（`libs/` 内上游自编译版本）中 `DownloadTask.request` 已改为 `StateFlow<DownloadRequest>`，按上游写法改为 `it.request.value.url` |
| 镜像仓库回源超时被误报为 `Could not find xxx.aar/.jar` | `bugsnag-plugin-android-ndk-6.27.1.aar` 等文件在 aliyun 首次回源需 40~80s，超过 Gradle 默认 30s HTTP 超时；在 `gradle.properties` 中将 `org.gradle.internal.http.connectionTimeout` / `socketTimeout` 放宽到 120s |

### 后续跟进建议

1. 若决定采纳上游 legacy 认证方案（`USE_MODERN_AUTH`），建议单独分支实机验证后再合入。
2. 上游已将下载引擎统一到 Ketch；若上游持续在该方向迭代，可评估把本地 Ktor 下载改进（HEAD 不消耗 auth、断点续传、统一超时）反向提交给上游。
3. `libs/` 为上游自编译的 Ketch 构件，升级 ktor 时需要同步更新，否则可能出现 API 不匹配。
4. **iOS 目标的 slf4j 依赖问题（代码分析结论，本次未实际验证 iOS 构建）**：`BifrostLogger`（`common/src/commonMain/.../util/Logger.kt`）直接依赖 JVM 专用的 `org.slf4j`，而 `slf4j` 仅配置在 `androidAndJvmMain` / `jvmMain`。从依赖配置推断，Darwin/iOS 目标编译 `commonMain` 时很可能无法解析 `org.slf4j.LoggerFactory`。建议后续把 `BifrostLogger` 改为 `expect object` + 平台 actual（Android/JVM 用 SLF4J，Darwin 用 `platform.Foundation.NSLog` 或简单输出）；本次未做该重构，因为验证 iOS 构建需先下载 Kotlin/Native 工具链，在无法本地验证的情况下改动日志基础设施风险较高。
5. 每次合并上游后，重新执行资源一致性检查（见 `docs/download-process.md` 附录脚本）。

### 合并操作备忘

```bash
# 1. 备份当前分支
git branch backup/pre-merge-$(date +%Y%m%d-%H%M%S)

# 2. 拉取上游并试探合并
git fetch upstream --tags
git merge --no-commit --no-ff upstream/master

# 3. 解决冲突（按上表策略），确认无残留后提交
git diff --name-only --diff-filter=U      # 应为空

# 4. 推送
git push origin HEAD:master
```

