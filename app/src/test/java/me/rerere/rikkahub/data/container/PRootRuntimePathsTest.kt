package me.rerere.rikkahub.data.container

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PRootRuntimePathsTest {
    @Test
    fun `resolveContainerSymlinkTarget maps absolute rootfs target inside root`() {
        withTempRootfs { root ->
            val linkPath = root.resolve("bin/sh")

            val resolved = resolveContainerSymlinkTarget(root, linkPath, "/bin/busybox")

            assertEquals(root.resolve("bin/busybox"), resolved)
        }
    }

    @Test
    fun `toHostSymlinkTarget rewrites absolute rootfs target to relative host link`() {
        withTempRootfs { root ->
            val linkPath = root.resolve("bin/sh")

            val hostTarget = toHostSymlinkTarget(root, linkPath, "/bin/busybox")

            assertEquals("busybox", hostTarget)
        }
    }

    @Test
    fun `isUsableContainerFile accepts symlink that resolves inside rootfs`() {
        withTempRootfs { root ->
            val binDir = Files.createDirectories(root.resolve("bin"))
            val busybox = binDir.resolve("busybox")
            Files.write(busybox, "busybox".toByteArray())
            Files.createSymbolicLink(binDir.resolve("sh"), Path.of("busybox"))

            assertTrue(isUsableContainerFile(root, binDir.resolve("sh")))
            assertNull(diagnoseContainerFileIssue(root, binDir.resolve("sh")))
        }
    }

    @Test
    fun `diagnoseContainerFileIssue reports missing target for unresolved rootfs symlink`() {
        withTempRootfs { root ->
            val binDir = Files.createDirectories(root.resolve("bin"))
            Files.createSymbolicLink(binDir.resolve("sh"), Path.of("/definitely-missing-rikkahub-bin-sh"))

            assertFalse(isUsableContainerFile(root, binDir.resolve("sh")))
            assertEquals("missing", diagnoseContainerFileIssue(root, binDir.resolve("sh")))
        }
    }

    private fun withTempRootfs(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("pr-rootfs-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
