/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Lists container backup archives written by the guest `podroid-backup` tool
 * into Downloads/Qodroid/backups when sharing is enabled.
 */
package com.pxkf.qodroid.data.repository

import android.os.Environment
import com.pxkf.qodroid.util.ShellQuote
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ContainerBackupFile(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val absolutePath: String,
)

@Singleton
class ContainerBackupRepository @Inject constructor() {
    companion object {
        const val BACKUP_SUBDIR = "Qodroid/backups"

        /**
         * Which container engine to talk to, chosen inside the guest rather than
         * hard-coded.
         *
         * The rootfs ships podman, but a guest running 1Panel usually has docker
         * instead and no podman at all — 1Panel installs and manages its own
         * docker, so the containers the user actually runs live there. Every
         * operation below has an identical docker equivalent (ps, images,
         * inspect, save, load, export, import, pull), so picking whichever binary
         * exists is enough rather than forking each command in two. The chosen
         * name is exported as $E for the caller.
         */
        // podman is only chosen when it actually holds containers or images.
        // A rootfs that ships both otherwise leaves an empty podman winning the
        // "which binary exists" test and hiding every container the user really
        // runs — those live in the docker that 1Panel manages.
        private const val ENGINE_DETECT =
            "if command -v podman >/dev/null 2>&1; then " +
                "if [ -n \"\$(podman ps -aq 2>/dev/null)\" ] || " +
                "   [ -n \"\$(podman images -q 2>/dev/null)\" ]; then E=podman; " +
                "elif command -v docker >/dev/null 2>&1; then E=docker; " +
                "else E=podman; fi; " +
                "elif command -v docker >/dev/null 2>&1; then E=docker; " +
                "else E=; fi; "

        /** Echoed instead of a result when the guest has neither engine. */
        private const val NO_ENGINE_MSG = "no_container_engine"

        /**
         * Run [body] with $E naming the container engine present in the guest.
         *
         * The `false` keeps the run's exit status honest when there is nothing to
         * run against: an absent engine is a failure, not an empty success.
         */
        private fun withEngine(body: String): String =
            ENGINE_DETECT + "if [ -n \"\$E\" ]; then $body; else echo \"$NO_ENGINE_MSG\"; false; fi"

        /**
         * Where backups went before the Qodroid rename.
         *
         * Still written by a rootfs image built before the rename — the guest's
         * `podroid-backup` script carries its own copy of the path, and the image
         * is rebuilt separately from the app (see build-all.sh). Listing this
         * folder too keeps existing archives visible in the window between the
         * app update and the image rebuild, instead of looking deleted.
         */
        const val LEGACY_BACKUP_SUBDIR = "Podroid/backups"

        /**
         * Section headers framing [inventoryCommand]'s output.
         *
         * All three come back through a single shell round trip, so they need
         * something to tell them apart that a container name cannot be mistaken
         * for.
         */
        internal const val INVENTORY_ENGINE = "--PODROID-ENGINE--"
        internal const val INVENTORY_CONTAINERS = "--PODROID-CONTAINERS--"
        internal const val INVENTORY_IMAGES = "--PODROID-IMAGES--"

        internal fun ensureBackupDirectory(dir: File): Boolean = runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            // Recheck even if mkdirs returned false: another caller may have
            // created the directory. An existing regular file is not usable.
            dir.isDirectory
        }.getOrDefault(false)
    }

    fun backupDirectory(): File = File(downloadsDir(), BACKUP_SUBDIR)

    fun guestBackupPathLabel(downloadsSharingEnabled: Boolean = isDownloadsReachable()): String =
        if (downloadsSharingEnabled) "/mnt/downloads/Qodroid/backups"
        else "/var/backups/Qodroid"

    fun isDownloadsReachable(): Boolean {
        val dir = backupDirectory()
        // Create Downloads/Qodroid/backups if absent: the app holds All-files
        // access whenever Downloads sharing is on, so a successful mkdirs both
        // confirms reachability and gives the guest an existing target under the
        // 9p/virtio-9p mount. Nothing else creates this dir, so without it every
        // backup would fall back to the guest-internal path and never surface in
        // Downloads.
        return ensureBackupDirectory(dir)
    }

    fun listBackupFiles(): List<ContainerBackupFile> {
        val current = archivesIn(backupDirectory())
        if (current.isNotEmpty()) return current
        // Only consulted when the new folder holds nothing, so once the guest
        // starts writing to Qodroid/backups the legacy branch retires by itself.
        val legacy = File(downloadsDir(), LEGACY_BACKUP_SUBDIR)
            ?.takeIf { it.isDirectory }
            ?: return emptyList()
        return archivesIn(legacy)
    }

    private fun downloadsDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun archivesIn(dir: File): List<ContainerBackupFile> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter {
                it.isFile &&
                    (it.name.endsWith(".tar") || it.name.endsWith(".tar.gz"))
            }
            ?.map { f ->
                ContainerBackupFile(
                    name = f.name,
                    sizeBytes = f.length(),
                    lastModifiedMs = f.lastModified(),
                    absolutePath = f.absolutePath,
                )
            }
            ?.sortedByDescending { it.lastModifiedMs }
            ?: emptyList()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun exportCommand(containerName: String): String =
        withEngine(exportContainerCommand(containerName))

    fun listCommand(): String =
        withEngine(
            "echo 'containers:'; \$E ps -a --format '{{.Names}}'; " +
            "echo 'images:'; \$E images --format '{{.Repository}}:{{.Tag}}'"
        )

    /**
     * Resolve a container name before archiving it.
     *
     * `podman inspect` exits 0 when the name names a container in the VM and 1
     * when it does not, which is precisely the question to settle first:
     * `podman export` also refuses, but only after it has walked the export, so
     * a typo then arrives as a slow failure with podman's terse
     * `no such container` instead of a direct answer.
     */
    fun containerExistsCommand(containerName: String): String =
        withEngine(
            "\$E inspect --format={{.Name}} " + ShellQuote.quote(containerName.trim())
        )

    /**
     * Route a Docker Hub image reference through a mirror registry.
     *
     * Only Docker Hub (or unqualified) references are rewritten — references
     * that already name another registry are left alone. Official single-word
     * images (`nginx`) become `mirror/library/nginx`, since most mirrors serve
     * the official namespace under `library/`.
     */
    fun withMirror(imageRef: String, mirrorHost: String): String {
        val ref = imageRef.trim()
        if (mirrorHost.isBlank() || ref.isBlank()) return ref
        val hasSlash = ref.contains("/")
        val first = if (hasSlash) ref.substringBefore("/") else ""
        val otherRegistry = hasSlash && first != "docker.io" && first != "index.docker.io" &&
            (first.contains(".") || first.contains(":"))
        return when {
            otherRegistry -> ref
            first == "docker.io" || first == "index.docker.io" -> "$mirrorHost/${ref.substringAfter("/")}"
            !hasSlash -> "$mirrorHost/library/$ref"
            else -> "$mirrorHost/$ref"
        }
    }

    /**
     * Save an image to a tar, pulling it through [mirror] first when one is set.
     *
     * `podman save` only saves what is local, so with a mirror configured the
     * image is pulled (via the mirrored reference) before saving — which is the
     * whole point of picking a mirror. Without a mirror the image is saved
     * straight away, since it is already local.
     */
    fun saveImageCommand(imageRef: String, mirror: String): String {
        val q = ShellQuote.quote(imageRef.trim())
        // Decide which destination is real at run time: the shared folder may be
        // off or the 9p mount may have failed, and writing into a guest path that
        // is not a mountpoint produces a backup the phone never sees.
        val pathGuard =
            "if mountpoint -q /mnt/downloads 2>/dev/null; then root=/mnt/downloads/Qodroid/backups; " +
                "else root=/var/backups/Qodroid; fi; " +
                "mkdir -p \"\$root\" && "
        if (mirror.isBlank()) {
            return withEngine(
                pathGuard +
                "out=\"\$root/\$(echo $q | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar\" && " +
                "\$E save $q -o \"\$out\" && echo \"saved $q -> \$out\""
            )
        }
        // With a mirror the image is not local yet, so it must be pulled first —
        // the whole point of picking a mirror.
        val mq = ShellQuote.quote(withMirror(imageRef, mirror))
        return withEngine(
            pathGuard +
                "out=\"\$root/\$(echo $mq | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar\" && " +
                "\$E pull $mq && \$E save $mq -o \"\$out\" && echo \"saved $mq -> \$out\""
        )
    }

    /**
     * Export every container the guest has, in one go — the closest thing to a
     * one-tap backup: no name to look up first.
     *
     * With nothing to back up this produces no files and exits 0, which the
     * the caller reports as "nothing exported" rather than a failure.
     */
    fun allCommand(): String =
        withEngine(
            "if mountpoint -q /mnt/downloads 2>/dev/null; then root=/mnt/downloads/Qodroid/backups; else root=/var/backups/Qodroid; fi; " +
            "mkdir -p \"\$root\"; " +
            "for id in \$( \$E ps -aq 2>/dev/null); do " +
            "name=\$( \$E inspect -f '{{.Name}}' \"\$id\" 2>/dev/null | sed 's#^/##'); " +
            "out=\"\$root/\$(printf '%s' \"\$name\" | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar\"; " +
            "\$E export \"\$id\" -o \"\$out\" && echo \"exported \$name -> \$out\"; done"
        )

    /**
     * Restore an archive into the VM.
     *
     * The archive lives on the phone (its host path is known), so the guest
     * reads it through the same Downloads share it was written to — the path is
     * mapped rather than assumed, so archives listed from the legacy
     * Podroid/backups folder restore from there too.
     *
     * Two archive kinds land in that folder and only one loads directly.
     * `podman save` writes an image archive with a `manifest.json`, which
     * `podman load` reads. `podman export` writes the bare filesystem of a
     * container, with no manifest, and `load` refuses it — that needs
     * `podman import`, which builds an image from a filesystem tar. Trying
     * load first and falling back means a single restore button covers both
     * directions: archives written here, and archives written by hand in the
     * guest terminal.
     *
     * The imported image is named from the file. Export writes
     * `&lt;container&gt;-&lt;timestamp&gt;.tar`, so a restored image stays
     * recognisable as the container it came from.
     */
    fun loadImageCommand(hostPath: String): String {
        val guestPath = hostPath.replace(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            "/mnt/downloads",
        )
        val q = ShellQuote.quote(guestPath)
        // `load -i` exists on both engines, but `import -i` does not: docker import
        // takes the archive as a positional argument and has no -i flag. Reading
        // from stdin works for both, so that is used rather than forking the branch.
        return withEngine(
            "if \$E load -i $q; then echo \"loaded $q\"; " +
                "else name=\$(basename $q | sed 's/\\.tar\\.gz\$//; s/\\.tar\$//'); " +
                "echo \"not an image archive; importing \$name\"; " +
                "\$E import - < $q \"\$name\"; fi"
        )
    }


    /**
     * The names the guest actually has, so they can be offered as choices.
     *
     * A hand-typed container name fails invisibly: podman reports
     * `no container with name or ID ... found` on stderr, which ends up in the
     * terminal transcript and nowhere the backup screen was reading. Asking the
     * guest what exists removes the guess rather than explaining it afterwards.
     */
    fun inventoryCommand(): String =
        withEngine(
            "echo '$INVENTORY_ENGINE'; echo \"\$E\"; " +
                "echo '$INVENTORY_CONTAINERS'; \$E ps -a --format '{{.Names}}'; " +
                "echo '$INVENTORY_IMAGES'; \$E images --format '{{.Repository}}:{{.Tag}}'"
        )

    /**
     * Split [inventoryCommand]'s output into container names and image refs.
     *
     * Nothing that is not under a header is treated as a name. Output read from a
     * live terminal transcript can be partial, and a partial read that promoted
     * whatever happened to precede the first header would offer it as a
     * suggestion to tap.
     */
    fun parseInventory(output: String): Pair<List<String>, List<String>> {
        val containers = mutableListOf<String>()
        val images = mutableListOf<String>()
        var current: MutableList<String>? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when (line) {
                INVENTORY_CONTAINERS -> current = containers
                INVENTORY_IMAGES -> current = images
                "" -> Unit
                else -> current?.add(line)
            }
        }
        return containers to images
    }

    /**
     * The engine [inventoryCommand] reported, or "" when it did not get that far.
     *
     * The page shows it so "which daemon is this talking to" is answerable
     * without going to the terminal: the same VM can hold podman and docker
     * together, and only one of them holds the containers the user actually
     * runs. A VM with neither, or a read that stopped before the line, is "".
     */
    fun parseEngine(output: String): String {
        val lines = output.lines()
        val header = lines.indexOfFirst { it.trim() == INVENTORY_ENGINE }
        if (header < 0) return ""
        val value = lines.getOrNull(header + 1)?.trim() ?: ""
        return if (value.startsWith("--PODROID-")) "" else value
    }

    /**
     * The direct export of one container's filesystem to a tar archive.
     *
     * Only ever run inside [withEngine], so \$E names the container engine.
     */
    fun exportContainerCommand(containerName: String): String {
        val q = ShellQuote.quote(containerName.trim())
        // Decide which target is real at run time, as before: the
        // shared folder may be off or the 9p mount may have failed. mkdir -p on
        // a guest path that is not a mountpoint would silently create /mnt/downloads
        // inside the VM disk and write the archive there — a backup the phone
        // never sees, which is exactly the "wrong path" failure this avoids.
        return "if mountpoint -q /mnt/downloads 2>/dev/null; then root=/mnt/downloads/Qodroid/backups; " +
            "else root=/var/backups/Qodroid; fi; " +
            "mkdir -p \"\$root\" && " +
            "out=\"\$root/\$(echo $q | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar\" && " +
            "\$E export $q -o \"\$out\" && echo \"exported $q -> \$out\""
    }
}
