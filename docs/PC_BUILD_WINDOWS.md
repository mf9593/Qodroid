# Qodroid 双终端（Windows）电脑端构建说明

> 手机侧 UI / 引擎代码已完成，但**运行时载荷必须在电脑上构建**（需要 Docker + NDK +
> 交叉编译工具链，手机环境不具备）。本文档是发给电脑的构建清单。

---

## 一、结论：需要哪些 .so 文件

| 用途 | QEMU 二进制 | Android 产物名 | 位置 |
|---|---|---|---|
| **Linux（默认 Alpine）** | `qemu-system-aarch64` | `libqemu-system-aarch64.so` | `app/src/main/jniLibs/arm64-v8a/` |
| **Windows（x86/x64 镜像）** | `qemu-system-x86_64` | `libqemu-system-x86_64.so` | `app/src/main/jniLibs/arm64-v8a/` |

**两个 .so 都需要**，缺一不可：

- `libqemu-system-aarch64.so` — 现有产物，跑内置 Alpine Linux（`VmMode.LINUX`）
- `libqemu-system-x86_64.so` — **新增**，跑 Windows x86/x64 镜像（`VmMode.WINDOWS`）

> 命名说明：你说的 "qemu-86" 是笔误，标准名是 `qemu-system-x86_64`（64 位 x86）。
> 手机是 arm64 宿主，x86_64 是**被模拟的 guest 架构**，靠 QEMU TCG 软件模拟翻译，
> 所以能跑，但**性能远低于原生**（见第五节）。

另外还需要**两个 UEFI 固件文件**（Windows 必须 UEFI 启动，无 BIOS 回退）：

| 文件 | 用途 |
|---|---|
| `AAVMF_CODE.fd` | UEFI 固件代码（只读） |
| `AAVMF_VARS.fd` | UEFI 变量存储（可写，首次启动创建） |

放到 `app/src/main/assets/qemu/`，由 App 启动时提取到 `filesDir`。

---

## 二、电脑端环境要求

```
Linux x86_64 / macOS / Windows (Docker Desktop + WSL2)
Docker（BuildKit 默认开启）
磁盘 ≥ 40GB（QEMU 源码 ~150MB、依赖静态库、镜像层）
内存 ≥ 8GB
网络可访问 github.com / kernel.org / dl-cdn.alpinelinux.org / ftp.gnu.org
```

> 注意：现有 `Dockerfile` 的 qemu-builder 阶段是 **x86_64 宿主**设计，
> macOS / Windows WSL2 的 Docker Desktop 底层也是 x86_64，可直接用。

---

## 三、现有 Linux 构建（确认基线可用）

```bash
cd <仓库>/Podroid-main
./build-all.sh qemu
```

产物应出现在：

```
app/src/main/jniLibs/arm64-v8a/
├── libqemu-system-aarch64.so   ← Linux VM
├── libslirp.so
├── libpodroid-bridge.so
└── libpodroid-launcher.so
app/src/main/assets/qemu/
├── efi-virtio.rom
└── keymaps/
```

---

## 四、新增：构建 x86_64 QEMU（Windows 用）

现有 `Dockerfile` 第 317 行 `--target-list=aarch64-softmmu` 只构建了 aarch64 guest。
需要**新增一个构建阶段**产出 x86_64 guest 的 QEMU。

### 4.1 修改 Dockerfile

在 `qemu-builder` 阶段之后（`COPY --from=qemu-builder` 之前）追加一个阶段：

```dockerfile
FROM debian:bookworm AS qemu-x86-builder
ARG QEMU_VERSION=11.0.4
ENV QEMU_DIR=qemu-${QEMU_VERSION}
COPY --from=qemu-builder /opt/qemu-out /opt/qemu-out
RUN cd ${QEMU_DIR} \
    && ./configure \
        --cc=/opt/qemu-out/bin/aarch64-linux-android26-clang \
        --cross-prefix=/opt/qemu-out/bin/aarch64-linux-android26- \
        --extra-cflags="-fPIC -DANDROID -I/opt/qemu-out/include -I/opt/qemu-out/include/glib-2.0" \
        --extra-ldflags="-L/opt/qemu-out/lib -Wl,-z,max-page-size=16384" \
        --prefix=/opt/qemu-x86-out \
        --target-list=x86_64-softmmu \
        --enable-tcg --enable-slirp --enable-virtfs --enable-libusb --enable-pie \
        --disable-docs --disable-gtk --disable-sdl --disable-vnc \
        --disable-vhost-user --disable-plugins --with-coroutine=ucontext \
    && make -j$(nproc) install
RUN cp /opt/qemu-x86-out/bin/qemu-system-x86_64 /opt/qemu-x86-out/libqemu-system-x86_64.so
```

然后在最终镜像阶段追加：

```dockerfile
COPY --from=qemu-x86-builder /opt/qemu-x86-out/libqemu-system-x86_64.so /libqemu-system-x86_64.so
```

### 4.2 修改 build-all.sh

在 `build_qemu()` 函数的 `docker cp` 段（第 147-150 行附近）追加：

```bash
docker cp podroid-qemu-extract:/libqemu-system-x86_64.so "$JNILIBS/"
```

并在 16KB 校验段追加：

```bash
verify_16kb_align "$JNILIBS/libqemu-system-x86_64.so"
```

### 4.3 执行

```bash
./build-all.sh qemu
```

### 4.4 验证产物

```bash
ls -la app/src/main/jniLibs/arm64-v8a/libqemu-system-*.so
file app/src/main/jniLibs/arm64-v8a/libqemu-system-x86_64.so
# 期望: ELF shared object, 64-bit LSB, ARM aarch64
```

---

## 五、AAVMF UEFI 固件（Windows 必需）

AAVMF 是 TianoCore 的 UEFI 固件，Windows 必须从 UEFI 引导。

```bash
git clone https://github.com/tianocore/edk2.git
cd edk2
git submodule update --init --recursive

# 安装依赖
sudo apt-get install -y python3-pip
pip3 install --break-system-packages --user --no-cache-dir \
    pip wheel pytooling edk2-basetools

make -C BaseTools -j$(nproc)

# 构建 ARM 版 AAVMF（手机是 arm64 宿主，但 Windows x86 镜像需要 x64 固件）
cd ArmVirtQemu-Aarch64
./Build.sh
```

产物：

```
Build/ArmVirtQemu-Aarch64/RELEASEGCC5/FV/QEMU_EFI.fd
```

复制到 App assets：

```bash
cd <仓库>/Podroid-main
cp edk2/Build/ArmVirtQemu-Aarch64/RELEASEGCC5/FV/QEMU_EFI.fd \
   app/src/main/assets/qemu/AAVMF_CODE.fd
# VARS 首次启动由 QEMU 创建，也可预置一份：
cp app/src/main/assets/qemu/AAVMF_CODE.fd \
   app/src/main/assets/qemu/AAVMF_VARS.fd
```

> 如果跑 x86_64 Windows 镜像，固件应为 x64 版（`OvmfPkgX64`），不是 ARM 版。
> 推荐：
>
> ```bash
> cd edk2
> make -C OvmfPkgX64 -j$(nproc)
> cp Build/OvmfPkgX64/RELEASEGCC5/FV/QEMU_EFI.fd app/src/main/assets/qemu/AAVMF_CODE.fd
> cp Build/OvmfPkgX64/RELEASEGCC5/FV/QEMU_VARS.fd app/src/main/assets/qemu/AAVMF_VARS.fd
> ```

---

## 六、Windows ARM64 镜像

手机是 arm64 宿主，QEMU 跑 Windows 有两种路径：

| 路径 | 镜像类型 | 需要 QEMU | 性能 |
|---|---|---|---|
| **A. Windows on ARM** | ARM64 版 Windows 11 | `qemu-system-aarch64`（已有） | 较好（原生架构） |
| **B. Windows x86/x64** | 标准 x64 Windows | `qemu-system-x86_64`（新增） | 差（TCG 软件模拟） |

**推荐路径 A**：用 ARM64 版 Windows 11 镜像，直接复用现有 `libqemu-system-aarch64.so`，
只需补 AAVMF 固件。

镜像获取（需自行下载，本文档不提供下载链接）：

- Windows on ARM 评估版：Microsoft 官方 ARM64 ISO（需微软账号）
- 转换现有 x64 镜像为 qcow2：

```bash
qemu-img convert -f raw -O qcow2 windows.iso windows.qcow2
```

把 qcow2 放到手机 `Downloads/`，App 内 Windows 标签页浏览选择即可。

---

## 七、性能预期（重要）

| 场景 | 性能 |
|---|---|
| Linux Alpine（aarch64，原生架构） | 接近原生 |
| Windows ARM64（aarch64，原生架构） | 接近原生 |
| Windows x86_64（TCG 模拟） | **极差**，仅能内存安装/启动，桌面基本不可流畅用 |

手机 CPU 单核性能有限，TCG 模拟 x86_64 会有 10-50 倍减速。
**Windows 体验建议用 ARM64 版镜像**（路径 A），不要期望 x86_64 流畅。

---

## 八、完整构建顺序

```bash
# 1. 构建 Linux QEMU（确认基线）
./build-all.sh qemu

# 2. 构建 x86_64 QEMU（Windows 用）
./build-all.sh qemu   # 修改 Dockerfile 后重跑

# 3. 构建 AAVMF 固件（Windows 必需）
# 按第五节手动构建 edk2

# 4. 构建 APK
./build-all.sh apk

# 5. 验证产物
ls -la app/src/main/jniLibs/arm64-v8a/
ls -la app/src/main/assets/qemu/
```

期望最终产物：

```
app/src/main/jniLibs/arm64-v8a/
├── libqemu-system-aarch64.so   ← Linux + Windows ARM64
├── libqemu-system-x86_64.so    ← Windows x86/x64（新增）
├── libslirp.so
├── libpodroid-bridge.so
└── libpodroid-launcher.so

app/src/main/assets/qemu/
├── efi-virtio.rom
├── AAVMF_CODE.fd               ← UEFI 固件（新增）
├── AAVMF_VARS.fd               ← UEFI 变量（新增）
└── keymaps/
```

---

## 九、常见问题

1. **16KB 页对齐**：`build-all.sh` 会自动校验，失败即报错。不要跳过。
2. **AAVMF 架构不匹配**：x86_64 Windows 用 `OvmfPkgX64`，ARM64 Windows 用
   `ArmVirtQemu-Aarch64`。搞反会启动失败。
3. **镜像格式**：App 支持 qcow2/img/raw/iso。qcow2 性能最好，iso 用于安装。
4. **性能**：x86_64 Windows 在手机上极慢，仅适合体验/内存安装。
5. **磁盘空间**：Windows 镜像通常 20-50GB，手机存储需预留。
