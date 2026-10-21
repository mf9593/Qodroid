# Podroid 电脑端完整构建指南

> 目标：在电脑上用 Docker 构建出 VM 载荷（内核/initramfs/rootfs/QEMU），
> 与手机上的 UI 开发并行协作。电脑负责"重活"，手机负责 UI 迭代。

---

## 为什么要在电脑上构建

| 部分 | 谁构建 | 原因 |
|---|---|---|
| Java/Kotlin UI（app 模块） | **手机即可** | 手机已配好 arm64 Gradle + SDK + NDK 环境 |
| 自定义 Linux 内核 + initramfs | **必须电脑** | 需要 Docker 交叉编译 aarch64 内核 |
| Alpine rootfs（squashfs） | **必须电脑** | Dockerfile.rootfs 按 x86_64 宿主设计 |
| QEMU for Android + 桥接库 | **必须电脑** | Dockerfile 硬编码 `linux-x86_64` NDK 工具链路径 |

**关键事实**：Dockerfile 的 qemu-builder 阶段写死了
`ENV NDK=/opt/ndk LLVM=/opt/ndk/toolchains/llvm/prebuilt/linux-x86_64`
（Dockerfile 第 239 行），所以 **Docker 宿主必须是 x86_64**（macOS/Windows WSL2 的
Docker Desktop 即可，底层也是 x86_64 虚拟机）。arm64 电脑上该步骤会失败。

---

## 一、电脑端环境要求

- Linux x86_64 / macOS 或 Windows（Docker Desktop + WSL2）
- Docker（BuildKit 默认开启）
- 磁盘 ≥ 30GB（内核源码 ~140MB、QEMU 依赖、Alpine 包、镜像层）
- 内存建议 ≥ 8GB
- 网络能访问 github.com / kernel.org / dl-cdn.alpinelinux.org
  （公司网络建议挂代理；build-all.sh 的 qemu 阶段自带 `--network=host`）

---

## 二、完整流程（电脑全量，最省事）

```bash
cd <仓库>/Podroid-main
./build-all.sh all
```

`all` = `initramfs` → `rootfs` → `qemu` → `apk`，依次产出：

| 产物 | 自动放置路径 |
|---|---|
| `vmlinuz-virt`（内核） | `app/src/main/assets/vmlinuz-virt` |
| `initrd.img`（initramfs） | `app/src/main/assets/initrd.img` |
| `alpine-rootfs.squashfs`（~225MB） | `app/src/main/assets/alpine-rootfs.squashfs` |
| `libqemu-system-aarch64.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `libslirp.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `libpodroid-bridge.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `libpodroid-launcher.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `efi-virtio.rom` | `app/src/main/assets/qemu/` |
| `qemu/keymaps/*` | `app/src/main/assets/qemu/keymaps/` |

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`（完整可启动）。

> build-all.sh 内部用 `$ASSETS` / `$JNILIBS` 变量指向上述目录，
> 构建完项目目录里就已经是正确位置，无需手动搬移。

---

## 三、电脑只构建 VM 载荷（配合手机做 UI）

若 APK 由手机构建，电脑只需产出 7 个载荷文件：

```bash
./build-all.sh initramfs   # vmlinuz-virt + initrd.img（内核+initramfs）
./build-all.sh rootfs      # alpine-rootfs.squashfs
./build-all.sh qemu        # 4 个 .so + efi-virtio.rom + keymaps/
```

然后把这些文件同步回手机的项目目录（git push/pull，或直接拷贝覆盖）：

- 覆盖到 `app/src/main/assets/` 和 `app/src/main/jniLibs/arm64-v8a/`（路径见上表）
- 手机端直接 `gradle :app:assembleDebug` 即可打出**完整可启动的 APK**

---

## 四、手机端：仅开发 UI（可与三并行）

手机已配置好 arm64 原生构建链（Gradle 9.3.1、SDK platform 36、NDK r29、aapt2
arm64 override，均在系统级配置，项目文件是干净的）。

```bash
# 手机 Terminal（Choccy Proot Ubuntu）
cd /storage/emulated/0/Download/code/Podroid-main
export ANDROID_HOME=/opt/android-sdk
gradle :app:assembleDebug        # 用 gradle，不要用 ./gradlew（FUSE 无法执行 wrapper）
```

- UI 代码位置：`app/src/main/java/com/pxkf/qodroid/ui/` 的 Compose 界面，
  以及 `app/src/main/res/` 资源
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 载荷缺失时：APK 可安装、UI 可正常浏览调试；但点 "Start VM" 起不来
  （`PodroidApplication` 提取 assets 时缺失文件只记日志、不崩溃）

---

## 五、常见问题

1. **qemu 阶段慢/失败**：首次要编译 glib/pixman/libslirp 等静态库，很慢属正常；
   网络问题加代理后重跑 `./build-all.sh qemu`（Docker 会缓存已完成的层）。
2. **16KB 页对齐**：`build-all.sh qemu` 结束会自动用 ELF 解析校验
   `--- verify_16kb_align ---`，失败即报错，不要跳过。
3. **版本一致性**：rootfs 内置的 `system-version` 取自
   `app/build.gradle.kts` 的 `versionCode`，改版本号后 rootfs 要重build。
4. **手机 gradle 版本**：AGP 9.1.0 要求 Gradle ≥ 9.3.1，手机全局 gradle 已是 9.3.1；
   电脑上若直接 `./gradlew` 会自动下载 wrapper 指定的 9.3.1。

---

## 六、产物目录速查（替换点）

```
Podroid-main/
├── app/src/main/assets/
│   ├── vmlinuz-virt              ← 内核（电脑 initramfs 产物）
│   ├── initrd.img                ← initramfs（电脑 initramfs 产物）
│   ├── alpine-rootfs.squashfs    ← rootfs（电脑 rootfs 产物）
│   └── qemu/
│       ├── efi-virtio.rom        ← 电脑 qemu 产物
│       └── keymaps/              ← 电脑 qemu 产物
└── app/src/main/jniLibs/arm64-v8a/
    ├── libqemu-system-aarch64.so ← 电脑 qemu 产物（16KB 对齐校验）
    ├── libslirp.so               ← 电脑 qemu 产物
    ├── libpodroid-bridge.so      ← 电脑 qemu 产物
    └── libpodroid-launcher.so    ← 电脑 qemu 产物
    └── libtermux.so              ← 手机/电脑 Gradle ndk-build 自动生成，勿手动覆盖
```

> 注意 `libtermux.so` 由 Gradle 的 externalNativeBuild（ndkBuild）自动编译，
> **不要**把它当成电脑产物拷贝；它只依赖 kernel/rootfs 之外的终端 JNI。
