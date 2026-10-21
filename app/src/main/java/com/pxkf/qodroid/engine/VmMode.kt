package com.pxkf.qodroid.engine

/**
 * Boot profile for the QEMU backend. The app can launch either the built-in
 * Alpine Linux system (kernel + initramfs + squashfs, the classic Podroid
 * experience) or a user-supplied disk/ISO image selected from Downloads
 * (Windows on x86_64, a Linux distro, whatever the image contains).
 *
 * Only affects QemuEngine: the AVF backend always boots the built-in Linux
 * payload (it needs a pKVM-compatible guest).
 */
enum class VmMode {
    /** Built-in Alpine Linux: -kernel vmlinuz-virt -initrd initrd.img + squashfs. */
    LINUX,

    /**
     * User-selected image: qcow2/raw disk (UEFI boot) or ISO (CD-ROM install).
     *
     * Always an **x86_64** guest, so this runs the separate
     * `libqemu-system-x86_64.so` binary on the q35 machine with the x86 UEFI
     * firmware (OVMF) — not the arm64 emulator on `virt`. That isn't a detail:
     * the arm64 build only has `aarch64-softmmu` compiled in and cannot even
     * parse `-M q35`.
     *
     * Costs a lot: a second ~30 MB binary in the APK, and TCG-emulated x86_64 is
     * markedly slower than emulated aarch64. It exists because the guests people
     * actually want (Windows, most distros) only ship x86_64 builds.
     */
    WINDOWS,
}
