<p align="right">
  <b>简体中文</b> · <a href="README.en.md">English</a>
</p>

<div align="center">

<img src="docs/qodroid-logo.png" alt="Qodroid" width="112" />

# Qodroid

**在 Android 手机上运行真实的 Linux 与 Windows 虚拟机，无需 root。**

Qodroid 是 [ExTV/Podroid](https://github.com/ExTV/Podroid) 的修改版（GPL v2，包名 `com.pxkf.qodroid`），基于 QEMU / AVF，在 arm64 手机上运行带独立内核的 Alpine Linux，也可以加载 x86 磁盘镜像启动 Windows。

[![License: GPL v2](https://img.shields.io/badge/license-GPLv2-blue?style=flat-square)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![arm64](https://img.shields.io/badge/arch-arm64-orange?style=flat-square)
![No root](https://img.shields.io/badge/root-not%20required-22c55e?style=flat-square)

[**下载 APK**](https://github.com/mf9593/Qodroid/releases/latest) · [**下载页**](https://mf9593.github.io/Qodroid/) · [**使用文档（英文）**](https://mf9593.github.io/Qodroid/docs/guide/) · [**上游 Podroid**](https://github.com/ExTV/Podroid)

</div>

## 界面预览

<table>
  <tr>
    <td align="center" width="50%">
      <img src="img/screenshot-1.jpg" width="260" alt="Qodroid 主控页" /><br />
      <sub>主控：选择 Linux / Windows 并启动</sub>
    </td>
    <td align="center" width="50%">
      <img src="img/screenshot-2.jpg" width="260" alt="Qodroid 终端页" /><br />
      <sub>终端：xterm-256color 与快捷键栏</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <img src="img/screenshot-3.jpg" width="260" alt="Qodroid 文件页" /><br />
      <sub>文件：浏览 Linux 文件系统</sub>
    </td>
    <td align="center" width="50%">
      <img src="img/screenshot-4.jpg" width="260" alt="Qodroid 设置页" /><br />
      <sub>设置：主题、语言与虚拟机资源</sub>
    </td>
  </tr>
</table>

<sub>截图为中文界面；应用内可切换英文。</sub>

## 这是什么

Qodroid 是一台跑在 Android 手机里的完整虚拟机，不是 chroot，也不是 proot。

它基于 [ExTV/Podroid](https://github.com/ExTV/Podroid) 修改而来（GPL v2，包名 `com.pxkf.qodroid`），在 arm64 手机上运行带独立内核的 Alpine Linux。Podman / Docker / LXC 的行为与服务器一致；也可以加载自己的 x86 磁盘镜像，启动 Windows 虚拟机。内置终端、X11 / VNC 桌面查看器与容器管理，开箱即用。

## 核心特性

- **真实的 Linux VM**：Alpine Linux + 自编译内核，支持 `apk` 包管理，系统与数据可持久化。
- **Windows 虚拟机**：加载自有 x86 磁盘镜像，q35 + OVMF/UEFI 固件，内置 VNC 桌面查看器。
- **双引擎**：普通设备使用 QEMU（TCG）；支持 pKVM 的设备可切换到 AVF 硬件加速。
- **内置终端**：完整 xterm-256color，100+ 配色主题与多种等宽字体，实时调整窗口大小，快捷键齐全。
- **X11 / VNC 桌面**：在应用内查看 Linux 图形界面，支持触摸、触摸板、键盘、鼠标与音频。
- **容器开箱即用**：预装 Podman、Docker 与 LXC；检测到 podman 为空时可自动改用 docker。
- **SSH、端口转发与 USB 直通**：从电脑 SSH 进入虚拟机，把 guest 端口暴露到局域网，或直通 USB 设备。
- **共享与备份**：手机 Download 目录可挂载进虚拟机；容器与镜像支持备份 / 恢复。
- **中英双语、离线文档**：内置使用文档、常见问题与 GPL v2 许可证全文，断网也能阅读。
- **无需 root**：支持 Android 8+ 的 arm64 设备，不需要解锁 Bootloader 或刷机。

<details>
<summary><b>Qodroid 2.0.0 相比上游的主要改动</b></summary>

- **Windows 虚拟机支持**：可直接加载 x86 磁盘镜像，q35 + OVMF/UEFI，内置 VNC 桌面查看器。
- **Windows 启动优化**：
  - 默认启用 USB 绝对定位指针（`-usb -device usb-tablet`），触屏下鼠标不再漂移；
  - VNC 增加「暴露到局域网」开关，默认仅本机 `127.0.0.1`，开启后允许局域网其它设备连接；
  - Windows 模式下隐藏「自动登录 Linux」选项；
  - 若 Windows 无法联网，请在其网卡手动把 DNS 设为 `8.8.8.8` / `223.5.5.5`（QEMU slirp 的内置 DNS 在 Android 上转发不可靠；ping 不通是 slirp 不转发 ICMP 所致，不代表断网）。
- **容器引擎回落**：检测到 podman 为空时自动改用 docker（适配使用 1Panel 等管理工具、其容器位于 docker 的场景）；容器备份同时支持 docker 与 podman，不再依赖 guest 内的旧脚本目录。
- **X11 / VNC 查看器改进**：
  - 底部快捷键栏与终端对齐，补全 `DEL` / `ENTER` / `SHIFT` / `PASTE`；
  - 新增 `WIN` 键——单按发送 Win 键弹出开始菜单；
  - 默认触摸模式改为触摸板（Trackpad），设置面板默认选中同步。
- **移除磁盘镜像备份**：备份 / 恢复统一走容器备份，不再包含虚拟机磁盘镜像导出。
- **关于页**：新增「开源与许可证」区块，内置 GPL v2 全文，断网可见。
- **内置使用文档**：关于页可进入「使用文档」，涵盖启动、创建容器、端口转发、备份与常见问题。
- **其他**：文件页灰色提示居中；更新日志独立成页；镜像源下拉与当前值显示。

</details>

## 快速开始

1. 从 [Releases](https://github.com/mf9593/Qodroid/releases/latest) 下载最新 APK。
2. 安装并打开 Qodroid；首次安装需允许「安装未知来源应用」。
3. 选择 **Linux** 或 **Windows**，点击 **启动虚拟机**。
4. 等待状态变为 **Ready**，打开终端开始使用。

> **默认凭据**：Linux 账号 `root`，密码 `podroid`，SSH 端口 `9922`。修改密码会中断应用内的自动登录与文件管理，请谨慎操作。

```sh
# 容器开箱即用
podman run --rm alpine echo "hello from a container"
docker run -d -p 8080:80 nginx

# 端口转发：TCP / UDP，QEMU 与 AVF 后端均可用
podroid-forward add 8080 8080 tcp
podroid-forward clean                # 清空全部端口转发规则

# 从电脑 SSH 进入虚拟机
ssh root@<手机IP> -p 9922
```

## Windows 虚拟机

- 加载自有 x86 磁盘镜像，使用 q35 + OVMF/UEFI 固件。
- 默认启用 USB 绝对定位指针，触屏下鼠标不再漂移。
- 桌面通过内置 VNC 查看器显示；VNC 默认只监听本机，可按需暴露到局域网。
- 底部快捷键栏包含 `DEL` / `ENTER` / `WIN` / `F1`–`F12`。
- 如果 Windows 无法联网，请在网卡中手动设置 DNS 为 `8.8.8.8` / `223.5.5.5`。

> `ping` 不通是正常的：QEMU slirp 不转发 ICMP，判断联网请直接打开网页。

## 构建

需要 Docker、JDK 与 Android SDK / NDK。

```sh
git clone https://github.com/mf9593/Qodroid.git
cd Qodroid

./build-all.sh all     # 内核、rootfs、QEMU 与 APK
./build-all.sh test    # 运行测试
```

分组件构建与工具链细节见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `app/` | Android 应用：Compose UI、虚拟机控制与设置 |
| `terminal-emulator/` | 终端模拟器引擎 |
| `terminal-view/` | 终端视图组件 |
| `build-rootfs/` | Alpine rootfs 构建脚本 |
| `build-tools/` | 构建辅助工具 |
| `docs/`、`img/` | 文档与截图 |

## 文档与支持

- 应用内：**关于 → 使用文档**（中英双语，离线可读）
- 在线：[使用文档（英文）](https://mf9593.github.io/Qodroid/docs/guide/)
- 问题反馈：[GitHub Issues](https://github.com/mf9593/Qodroid/issues)

## 贡献

欢迎各种形式的贡献：Bug 报告、内核配置调整、新主题、UI 优化、X11 输入修复等。

- **Pull Request**：请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，保持改动聚焦，推送前运行 `./build-all.sh test`，并在描述中说明原因。
- **Bug 报告**：请附上设备型号、Android 版本、复现步骤和诊断日志（应用内 **设置 → 导出诊断日志**）。

## 致谢

| 项目 | 用途 |
| --- | --- |
| [QEMU](https://www.qemu.org) | 机器模拟 |
| [Termux](https://github.com/termux/termux-app) | 终端模拟器引擎 |
| [Alpine Linux](https://alpinelinux.org) | Guest 发行版 |

完整列表见 [CREDITS.md](CREDITS.md)。

## 许可证

[GPL v2](LICENSE)。Qodroid 基于 [ExTV/Podroid](https://github.com/ExTV/Podroid)，版权归原作者及贡献者所有。如果这个项目对你有帮助，欢迎点一个 Star。

---

<p align="center">
  <a href="README.en.md">English</a> · <b>简体中文</b>
</p>
