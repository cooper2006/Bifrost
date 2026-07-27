# 致谢

* 感谢 [VanVuong41429](https://github.com/VanVuong41429) 贡献了如此多的 TAC！
* 感谢 [henr1kas](https://github.com/henr1kas) 提供了无需 IMEI 的下载方式！

# Bifrost - 三星固件下载器
这又是一款用于三星设备的固件下载器，但它有一些特别的功能。

首先，它是跨平台的。Bifrost 可运行于 Windows、Linux、macOS，甚至 Android！

Bifrost 也是一个图形化程序，在所有支持的平台上共享同一套 UI。

Bifrost 的大部分功能基于 [Samloader](https://github.com/nlscc/samloader)。其 Python 代码已被转换为 Kotlin，并经过调整以利用 Kotlin 的一些特性。

Bifrost 使用 Jetpack Compose、JetBrains Compose for Desktop 和 Kotlin Multiplatform，为所有支持的平台创建共享代码库。

# 支持

喜欢这个应用？[在此捐赠](https://www.paypal.com/donate/?hosted_button_id=EWAPDSENZ7U44)。

# 下载
对于 Windows、macOS 和 Linux，你可以从 https://bifrost.zwander.dev 下载。

也可以在 [Releases 页面](https://github.com/zacharee/SamloaderKotlin/releases) 获取二进制文件。

如果你想运行于 iOS 或在 macOS 上使用 iOS 版本，可以[在此](https://testflight.apple.com/join/PVmWZNZn)报名 TestFlight 版本。

## 平台兼容性

|               | x86 | x86_64 | ARMv7 | ARM64 |
|---------------|-----|--------|-------|-------|
| Windows       | ❌   | ✅      | ❌     | ✅     |
| macOS         | ❌   | ✅      | ❌     | ✅     |
| Android       | ✅   | ✅      | ✅     | ✅     |
| Debian 系    | ❌   | ✅      | ❌     | ✅     |
| 通用 Linux    | ❌   | ✅      | ❌     | ✅     |
| iOS           | ❌   | ❌      | ❌     | ✅     |

## Linux 注意事项
请确保已安装以下每个类别中至少一种字体系列。

### Sans Serif（无衬线）
- Noto Sans
- DejaVu Sans

### Serif（衬线）
- Noto Serif
- DejaVu Serif
- Times New Roman

### Monospace（等宽）
- Noto Sans Mono
- DejaVu Sans Mono

### Cursive（手写体）
- Comic Sans MS

# 更新日志
发布说明详见 [CHANGELOG.md](CHANGELOG.md)。

# 常见问题与故障排除

## Bifrost 无法下载手表固件
遗憾的是，三星不提供手表的完整固件文件，因此 Bifrost 无法下载它们。

## Bifrost 下载时返回 400/401 错误
这些错误来自三星服务器端。如果可以，尝试使用不同的区域/CSC。

## Bifrost 检查更新时返回 403 错误
这些错误来自三星服务器端。三星可能已停止为你的设备提供固件，或尚未开始提供。请检查型号是否正确，或尝试不同的区域/CSC。

## Bifrost 在 Windows 上打开后是空白屏幕
在某些 GPU 上，Jetpack Compose/Skia 会出现渲染问题。尝试以管理员身份运行程序。

如果你的电脑有可切换显卡，尝试使用其他 GPU。

## 下载速度慢
三星服务器有时会将下载限速到约 3MiB/s。对于较老的设备，速度可能更慢。不同的区域/CSC 可能有更快的下载速度。

## 如何知道该用哪个 CSC？
在设备上执行以下操作：
1. 打开"设置"应用。
2. 向下滚动到"关于手机"或"关于平板"并点击。
3. 点击"软件信息"。
4. 向下滚动到"服务提供商软件版本"。
5. 你会在第二行看到类似 "XAA/XAA,XAA/XAU/TMB" 或 "XAR/XAR/" 的内容。

前三个字母就是你当前的 CSC。最后三个字母是你设备的原始/固件 CSC。
以上述示例为例，第一个的当前 CSC 是 XAA，固件 CSC 是 TMB。第二个的当前 CSC 是 XAR，固件 CSC 也是 XAR。

## 如果我的 CSC 不可用，如何选择替代 CSC？
使用 CSC 选择器对话框（"区域"文本框中看起来像列表的按钮）。
你可以在其中搜索你的国家或地区，并查看所使用的不同 CSC。如果某个 CSC 关联了特定运营商，也会一并显示。

## 为什么我的杀毒软件会标记此应用？
某些杀毒软件可能将 Bifrost 标记为恶意软件。这（希望显然）是误报。

有一个名为 Bifrost 的木马恶意软件家族，属于更大的 Bifrose 家族。
杀毒软件标记 Bifrost（本应用）似乎仅仅是因为本应用与该恶意软件同名。

Bifrost（恶意软件）只影响 Windows 系统，在 Windows XP 之后功能有限。更多信息请参见[这篇维基百科文章](https://en.wikipedia.org/wiki/Bifrost_(Trojan_horse))。

Bifrost（本应用）不包含恶意软件。你可以通过浏览源代码或使用下方说明自行编译来验证这一点。

# 构建
构建本项目应该相当简单。

## 准备：
1. 确保已安装最新的 [Android Studio Canary](https://developer.android.com/studio/preview)。
2. 将本项目克隆到 Android Studio 中并让其导入。

## 桌面端

### Conveyor
Bifrost 使用 [Conveyor](https://www.hydraulic.dev/) 为不同桌面平台创建二进制文件。

Conveyor 可以从任何主机操作系统构建 Windows 和 Linux 版本，但构建 macOS 版本需要 macOS。

1. 要构建，首先从上方链接下载并安装 Conveyor。
2. 接下来，打开终端并进入项目根目录。
3. 运行 `./gradlew :desktop:build`（Windows 上为 `.\gradlew.bat :desktop:build`）。
4. 根据你的目标系统运行以下命令。
   4.1. Intel/AMD（x86）Windows：`conveyor -Kapp.machines=windows.amd64 make windows-zip`。
   4.2. ARM64 Windows：`conveyor -Kapp.machines=windows.arm64 make windows-zip`。
   4.3. x86 Debian：`conveyor -Kapp.machines=linux.amd64 make debian-package`。
   4.4. ARM64 Debian：`conveyor -Kapp.machines=linux.arm64 make debian-package`。
   4.5. x86 Linux：`conveyor -Kapp.machines=linux.amd64 make linux-tarball`。
   4.6. ARM64 Linux：`conveyor -Kapp.machines=linux.arm64 make linux-tarball`。
   4.7. Intel Mac：`conveyor -Kapp.machines=mac.amd64 make unnotarized-mac-zip`。
   4.8. Apple Silicon Mac：`conveyor -Kapp.machines=mac.arm64 make unnotarized-mac-zip`。
5. 在项目根目录的 `output` 文件夹中查看生成的二进制文件。

### Gradle
或者，你可以通过执行 `:desktop:run` 任务来运行调试版二进制文件。

`./gradlew :desktop:run`（Windows 上为 `.\gradlew :desktop:run`）。

## Android：

### 命令行：
1. 在 Android Studio 中打开 Terminal 视图（左下角）。
2. 在 Windows 上输入 `gradlew :android:build`，或在 macOS 和 Linux 上输入 `./gradlew :android:build`。
3. 构建完成后，前往 `android/build/outputs/apk/debug` 并安装 `android-debug.apk`。

### GUI：
1. 在 Android Studio 中打开 Gradle 视图（右上角）。
2. 展开项目，然后展开 "android"。
3. 展开 "Tasks"，再展开 "build"，然后双击 "build"。
4. 构建完成后，前往 `android/build/outputs/apk/debug` 并安装 `android-debug.apk`。

# 运行

## Android
下载 `bifrost_android_<VERSION>.apk` 并安装。

## Windows
- 在 Intel 或 AMD 设备上，下载以 `windows-amd64` 结尾的 .zip 文件。
- 在 ARM64 设备上，下载以 `windows-aarch64` 结尾的 .zip 文件。

## macOS
- 在 Intel Mac 上，下载以 `mac-amd64` 结尾的 .zip 文件。
- 在 Apple Silicon Mac 上，下载以 `mac-aarch64` 结尾的 .zip 文件。

## Linux
- 在 Debian 系系统上，下载 `.deb` 文件。
- 在其他 Linux 发行版上，下载 `.tar.gz` 文件。

对于 Intel 或 AMD 设备，下载 `amd64` 版本。对于 ARM64 设备，选择 `aarch64` 或 `arm64` 版本。

# 翻译

Bifrost 使用 Weblate 进行翻译。

请在[项目页面](https://hosted.weblate.org/engage/bifrost/)帮助将 Bifrost 翻译成你的语言！

<a href="https://hosted.weblate.org/engage/bifrost/">
<img src="https://hosted.weblate.org/widget/bifrost/strings/multi-auto.svg" alt="翻译状态" />
</a>

# 截图

## 桌面端：

<img src="/screenshots/DesktopDownload.png" alt="桌面端下载器" width="400"></img>
<img src="/screenshots/DesktopDecrypt.png" alt="桌面端解密器" width="400"></img>
<img src="/screenshots/DesktopHistory.png" alt="桌面端历史记录" width="400"></img>
<img src="/screenshots/DesktopSettings.png" alt="桌面端设置" width="400"></img>

## 移动端：
<img src="/screenshots/AndroidDownload.png" alt="Android 下载器" width="400"></img>
<img src="/screenshots/AndroidDecrypt.png" alt="Android 解密器" width="400"></img>
<img src="/screenshots/AndroidHistory.png" alt="Android 历史记录" width="400"></img>
<img src="/screenshots/AndroidSettings.png" alt="Android 设置" width="400"></img>

# 错误报告
Bifrost 使用 Bugsnag 进行错误报告。

<a href="https://www.bugsnag.com"><img src="https://assets-global.website-files.com/607f4f6df411bd01527dc7d5/63bc40cd9d502eda8ea74ce7_Bugsnag%20Full%20Color.svg" width="200"></a>
