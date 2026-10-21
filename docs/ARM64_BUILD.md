# Podroid ARM64 Linux 原生构建指南（不用 Docker）

> 目标：在 **arm64 Linux** 主机（如 Apple Silicon Linux VM、树莓派、
> 或 QEMU 里的 ARM64 Linux）上直接构建出 Podroid 所需的全部 `.so`
> 载荷文件，**不依赖 Docker**，也**不依赖 x86_64 主机**。

---

## 为什么需要这篇文档

官方 `build-all.sh` / `Dockerfile` 是围绕 **x86_64 主机 + Docker** 设计的：

- `Dockerfile` 第 239 行硬编码 `ENV LLVM=/opt/ndk/toolchains/llvm/prebuilt/linux-x86_64`
- rootfs 阶段有 `FROM --platform=linux/arm64/v8`（arm64 反而原生）
- qemu 阶段需要 NDK 的 x86_64 宿主 clang 做交叉编译

所以 **arm64 主机上用 Docker 跑官方脚本会在 qemu 阶段失败**。
本篇给出 arm64 Linux 上的原生替代路径。

**要点**：交叉编译的目标（Android arm64）和宿主（arm64 Linux）架构一致，
NDK 自带的 `linux-aarch64` 宿主工具链可以直接用，无需 qemu-user 模拟。

---

## 需要产出的 .so 文件

| 文件 | 来源 | 说明 |
|---|---|---|
| `libqemu-system-aarch64.so` | QEMU 11.0.4 交叉编译 | Android 版 QEMU 主程序（16KB 页对齐） |
| `libslirp.so` | QEMU 构建产物 | 用户态网络栈 |
| `libpodroid-bridge.so` | `podroid-bridge.c` | virtio-console ↔ PTY 中继 |
| `libpodroid-launcher.so` | `podroid-launcher.c` | PR_SET_PDEATHSIG 包装器 |
| `libtermux.so` | `terminal-emulator` 模块 | Gradle ndk-build 自动生成，**不需要手动构建** |

另外两个非 .so 但相关的载荷：
- `efi-virtio.rom`（QEMU 产物，放 `assets/qemu/`）
- `qemu/keymaps/`（QEMU 产物）

---

## 一、环境准备（arm64 Linux）

```bash
# 系统包（Debian/Ubuntu arm64）
sudo apt-get update
sudo apt-get install -y \
    wget curl unzip xz-utils ca-certificates git bzip2 ninja-build \
    python3 python3-pip pkg-config flex bison make cmake autoconf \
    automake libtool libglib2.0-dev libglib2.0-bin gettext \
    libintl-perl binutils-aarch64-linux-gnu patchelf \
    gcc g++ meson

# 磁盘 & 内存
# 建议 ≥ 30GB 磁盘、≥ 8GB 内存（QEMU 编译很吃内存）
```

### NDK：必须用 linux-aarch64 宿主版

```bash
# 下载 ARM64 宿主版 NDK（注意文件名是 -aarch64-，不是 -linux.zip）
wget https://dl.google.com/android/repository/android-ndk-r27c-linux-aarch64.zip
unzip android-ndk-r27c-linux-aarch64.zip -d /opt
export NDK=/opt/android-ndk-r27c
# 关键：这个路径存在才说明是 arm64 版
ls $NDK/toolchains/llvm/prebuilt/linux-aarch64/bin/aarch64-linux-android26-clang
```

> ⚠️ 官方下载页的 `android-ndk-r27c-linux.zip` 是 x86_64 宿主版；
> arm64 主机必须下 `-linux-aarch64.zip`（文件名带 aarch64 后缀）。

### 环境变量

```bash
export NDK=/opt/android-ndk-r27c
export LLVM=$NDK/toolchains/llvm/prebuilt/linux-aarch64
export CC=$LLVM/bin/aarch64-linux-android26-clang
export AR=$LLVM/bin/llvm-ar
export RANLIB=$LLVM/bin/llvm-ranlib
export PREFIX=/opt/podroid-deps
mkdir -p $PREFIX/{lib,include,lib/pkgconfig}

# pkg-config 交叉设置（与 Dockerfile 相同的 aarch64-android-pkg-config 技巧）
cat > /usr/local/bin/aarch64-android-pkg-config << 'EOF'
#!/bin/sh
export PKG_CONFIG_LIBDIR=/opt/podroid-deps/lib/pkgconfig
export PKG_CONFIG_PATH=
exec pkg-config "$@"
