/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.pxkf.qodroid.x11

object X11Constants {
    /**
     * Guest-side Xvnc port. This is what the Alpine Linux VM runs on 5900 and
     * what `hostfwd 127.0.0.1:5900→5900` forwards into the guest.
     */
    const val VNC_PORT = 5900
    const val AUDIO_PORT = 4713

    /**
     * QEMU's own VNC server, for the x86_64 Windows path.
     *
     * The guest forwards above are useless for Windows: there is no Xvnc inside
     * an x86_64 guest, so 5900 would connect to nothing. A Windows session can
     * only be seen through QEMU's built-in `-vnc` server. That is display 1,
     * i.e. 5900 + 1 — deliberately NOT 5900, so it can never collide with the
     * guest Xvnc forward if both paths are ever live at once.
     */
    const val VNC_DISPLAY = 1
    const val QEMU_VNC_PORT = VNC_PORT + VNC_DISPLAY

    /**
     * Port the viewer should dial for a given boot mode. Linux talks to the
     * guest's Xvnc; Windows talks to QEMU's framebuffer server.
     */
    fun vncPortFor(windows: Boolean): Int = if (windows) QEMU_VNC_PORT else VNC_PORT

    const val FB_WIDTH = 1280
    const val FB_HEIGHT = 720
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_CHANNELS = 2
}
