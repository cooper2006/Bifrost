# Bifrost 下载指南

Bifrost 是一款跨平台的三星设备固件下载工具，支持 Windows、macOS、Linux、Android 和 iOS。本文档详细介绍如何获取和安装 Bifrost。

---

## 下载渠道

| 平台 | 下载地址 |
|------|----------|
| Windows / macOS / Linux | https://bifrost.zwander.dev |
| GitHub Releases | https://github.com/zacharee/SamloaderKotlin/releases |
| iOS TestFlight | https://testflight.apple.com/join/PVmWZNZn |

---

## 系统要求

### 平台兼容性

| 系统        | x86 | x86_64 | ARMv7 | ARM64 |
|-------------|-----|--------|-------|-------|
| Windows     | ❌   | ✅      | ❌     | ✅     |
| macOS       | ❌   | ✅      | ❌     | ✅     |
| Android     | ✅   | ✅      | ✅     | ✅     |
| Debian 系   | ❌   | ✅      | ❌     | ✅     |
| 通用 Linux  | ❌   | ✅      | ❌     | ✅     |
| iOS         | ❌   | ❌      | ❌     | ✅     |

### Linux 字体要求

Linux 系统需要安装以下字体系列（每个类别至少安装一个）：

**Sans Serif（无衬线）**
- Noto Sans
- DejaVu Sans

**Serif（衬线）**
- Noto Serif
- DejaVu Serif
- Times New Roman

**Monospace（等宽）**
- Noto Sans Mono
- DejaVu Sans Mono

**Cursive（手写体）**
- Comic Sans MS

---

## 各平台下载与安装

### Windows

**选择对应架构的安装包：**
- Intel / AMD 处理器：下载文件名以 `windows-amd64` 结尾的 `.zip` 文件
- ARM64 处理器（如 Surface Pro X）：下载文件名以 `windows-aarch64` 结尾的 `.zip` 文件

**安装步骤：**
1. 下载对应架构的 `.zip` 文件
2. 解压到任意目录（建议解压到 `C:\Program Files\Bifrost`）
3. 运行 `Bifrost.exe`
4. （可选）创建桌面快捷方式以便快速启动

> **提示：** 如果程序启动后出现空白画面，请尝试以管理员身份运行。如果电脑有双显卡（集显 + 独显），尝试切换 GPU 后再运行。

---

### macOS

**选择对应芯片的安装包：**
- Intel Mac：下载文件名以 `mac-amd64` 结尾的 `.zip` 文件
- Apple Silicon Mac（M1/M2/M3 系列）：下载文件名以 `mac-aarch64` 结尾的 `.zip` 文件

**安装步骤：**
1. 下载对应芯片的 `.zip` 文件
2. 解压后将 `Bifrost.app` 拖入 `Applications` 文件夹
3. 首次启动时，如果系统提示无法验证开发者，请前往 **系统设置 > 隐私与安全性**，点击"仍要打开"

---

### Linux

**Debian / Ubuntu / 基于 Debian 的系统：**
- 下载文件名以 `.deb` 结尾的安装包
- 使用以下命令安装：

```bash
sudo dpkg -i bifrost_*.deb
sudo apt install -f
```

**其他 Linux 发行版：**
- 下载文件名以 `.tar.gz` 结尾的压缩包
- 解压后直接运行：

```bash
tar -xzf bifrost-*.tar.gz
cd bifrost-*
./bifrost
```

**架构选择：**
- Intel / AMD 处理器：选择 `amd64` 版本
- ARM64 处理器（如 Raspberry Pi）：选择 `aarch64` 或 `arm64` 版本

---

### Android

**下载方式：**
- 从 GitHub Releases 下载 `bifrost_android_` 开头的 `.apk` 文件
- 或者从 bifrost.zwander.dev 获取

**安装步骤：**
1. 下载 `.apk` 文件
2. 在 Android 设备上打开该文件
3. 如果系统提示禁止安装未知来源应用，请前往 **设置 > 安全**，开启允许安装未知来源应用
4. 按提示完成安装

> **注意：** Android 上下载的固件会先保存到 Bifrost 内部数据目录，再复制到目标目录。这是为了支持多线程下载，通常仍比单线程直连更快。

---

### iOS

Bifrost 的 iOS 版本通过 TestFlight 分发。

**加入方式：**
1. 在 iOS 设备上打开 Safari 浏览器
2. 访问 https://testflight.apple.com/join/PVmWZNZn
3. 点击加入 TestFlight
4. 如果 TestFlight 已满员，请等待作者释放新名额

> **注意：** iOS 版本需要 Apple 的 TestFlight 应用，可在 App Store 免费下载。

---

## 常见下载问题

### 下载速度慢
三星服务器有时会限速到约 3 MiB/s。对于较老的设备，速度可能更慢。如果当前区域或 CSC 下载慢，可以尝试切换到其他区域。

### 下载时返回错误 400/401
这是三星服务器端的问题。尝试使用不同的区域（Region）或 CSC 码。

### 检查更新时返回错误 403
三星服务器端的问题，可能该设备已停止固件服务，或尚未开始提供服务。请检查型号是否正确，或尝试不同的 CSC。

### 无法下载手表（Watch）固件
三星不提供手表固件的完整文件，因此 Bifrost 无法下载手表固件。

---

## 更新 Bifrost

- **桌面版：** 关注 GitHub Releases 或 bifrost.zwander.dev 获取最新版本
- **Android 版：** 从以上渠道下载新版 APK 覆盖安装
- **iOS 版：** 通过 TestFlight 自动更新

更新日志请查看项目中的 CHANGELOG.md。

---

## 许可证

Bifrost 使用 MIT 许可证。详情请参见项目中的 LICENSE.txt。

---

*如有其他问题，请参阅项目主页 README 中的 FAQ 与故障排除部分。*
