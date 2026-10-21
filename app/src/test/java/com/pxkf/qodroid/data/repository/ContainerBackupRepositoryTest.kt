package com.pxkf.qodroid.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerBackupRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsMissingBackupDirectoryAndParents() {
        val dir = File(temporaryFolder.root, "Qodroid/backups")

        assertTrue(ContainerBackupRepository.ensureBackupDirectory(dir))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun acceptsExistingDirectoryWithoutChangingContents() {
        val dir = temporaryFolder.newFolder("backups")
        val archive = File(dir, "container.tar").apply { writeText("keep") }

        assertTrue(ContainerBackupRepository.ensureBackupDirectory(dir))
        assertEquals("keep", archive.readText())
    }

    @Test
    fun rejectsExistingFileWithoutDeletingIt() {
        val file = temporaryFolder.newFile("backups").apply { writeText("keep") }

        assertFalse(ContainerBackupRepository.ensureBackupDirectory(file))
        assertTrue(file.isFile)
        assertEquals("keep", file.readText())
    }

    @Test
    fun rejectsFileInParentPath() {
        val parent = temporaryFolder.newFile("Qodroid").apply { writeText("keep") }

        assertFalse(ContainerBackupRepository.ensureBackupDirectory(File(parent, "backups")))
        assertEquals("keep", parent.readText())
    }

    @Test
    fun splitsInventoryIntoContainersAndImages() {
        val output = """
            --PODROID-CONTAINERS--
            my-nginx
            redis
            --PODROID-IMAGES--
            docker.io/library/nginx:latest
            alpine:3.20
        """.trimIndent()

        val (containers, images) = ContainerBackupRepository().parseInventory(output)

        assertEquals(listOf("my-nginx", "redis"), containers)
        assertEquals(listOf("docker.io/library/nginx:latest", "alpine:3.20"), images)
    }

    @Test
    fun treatsEmptySectionsAsEmptyRatherThanMissing() {
        // What podman prints for a guest with nothing in it: both headers, no rows.
        val output = "--PODROID-CONTAINERS--\n--PODROID-IMAGES--\n"

        val (containers, images) = ContainerBackupRepository().parseInventory(output)

        assertTrue(containers.isEmpty())
        assertTrue(images.isEmpty())
    }

    @Test
    fun ignoresLinesBeforeTheFirstHeader() {
        // A partial transcript can carry something ahead of the first header; it
        // must not become a container to tap, or tapping it backs up a name that
        // was never in the guest.
        val output = "podman ps -a --format '{{.Names}}'\n--PODROID-CONTAINERS--\nreal\n--PODROID-IMAGES--"

        val (containers, images) = ContainerBackupRepository().parseInventory(output)

        assertEquals(listOf("real"), containers)
        assertTrue(images.isEmpty())
    }

    @Test
    fun onlyAnExactHeaderLineStartsASection() {
        // A container name is listed, never tested for, so a name that merely
        // contains the header text has to survive as a name.
        val output = "--PODROID-CONTAINERS--\n--PODROID-CONTAINERS--lookalike\n--PODROID-IMAGES--"

        val (containers, _) = ContainerBackupRepository().parseInventory(output)

        assertEquals(listOf("--PODROID-CONTAINERS--lookalike"), containers)
    }

    @Test
    fun noMirrorLeavesReferenceUntouched() {
        assertEquals("nginx", ContainerBackupRepository().withMirror("nginx", ""))
        assertEquals("quay.io/org/app:1", ContainerBackupRepository().withMirror("quay.io/org/app:1", ""))
    }

    @Test
    fun officialSingleWordImageGetsLibraryPrefix() {
        val repo = ContainerBackupRepository()
        assertEquals("docker.m.daocloud.io/library/nginx", repo.withMirror("nginx", "docker.m.daocloud.io"))
        assertEquals("docker.m.daocloud.io/library/nginx:latest", repo.withMirror("nginx:latest", "docker.m.daocloud.io"))
    }

    @Test
    fun dockerIoPrefixedReferenceIsRewritten() {
        val repo = ContainerBackupRepository()
        assertEquals(
            "docker.m.daocloud.io/library/nginx:latest",
            repo.withMirror("docker.io/library/nginx:latest", "docker.m.daocloud.io"),
        )
        assertEquals(
            "docker.m.daocloud.io/gitea/gitea:latest",
            repo.withMirror("docker.io/gitea/gitea:latest", "docker.m.daocloud.io"),
        )
    }

    @Test
    fun namespacedReferenceKeepsItsNamespace() {
        assertEquals(
            "docker.m.daocloud.io/gitea/gitea:latest",
            ContainerBackupRepository().withMirror("gitea/gitea:latest", "docker.m.daocloud.io"),
        )
    }

    @Test
    fun otherRegistriesAreLeftAlone() {
        val repo = ContainerBackupRepository()
        assertEquals("quay.io/org/app:1", repo.withMirror("quay.io/org/app:1", "docker.m.daocloud.io"))
        assertEquals("ghcr.io/org/app:1", repo.withMirror("ghcr.io/org/app:1", "docker.m.daocloud.io"))
    }
}
