package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class GuestOverlayTest {

    @TempDir
    lateinit var guestRoot: File

    private fun permissions(file: File): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `writes a missing file and reports it`() {
        val start = "#!/bin/sh\necho start\n".toByteArray()

        val changed = GuestOverlay.sync(mapOf("etc/phoshdroid/start.sh" to start), guestRoot)

        assertEquals(listOf("etc/phoshdroid/start.sh"), changed)
        assertArrayEquals(start, File(guestRoot, "etc/phoshdroid/start.sh").readBytes())
    }

    @Test
    fun `leaves an identical file alone`() {
        val content = "print('fit')\n".toByteArray()
        val target = File(guestRoot, "etc/phoshdroid/fit-x11.py").apply {
            parentFile!!.mkdirs()
            writeBytes(content)
            setLastModified(1_700_000_000_000L)
        }

        val changed = GuestOverlay.sync(mapOf("etc/phoshdroid/fit-x11.py" to content), guestRoot)

        assertTrue(changed.isEmpty())
        assertEquals(1_700_000_000_000L, target.lastModified())
    }

    @Test
    fun `replaces a file whose content differs`() {
        val target = File(guestRoot, "etc/phoshdroid/session-child.sh").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/sh\nold\n")
        }

        val changed =
            GuestOverlay.sync(
                mapOf("etc/phoshdroid/session-child.sh" to "#!/bin/sh\nnew\n".toByteArray()),
                guestRoot,
            )

        assertEquals(listOf("etc/phoshdroid/session-child.sh"), changed)
        assertEquals("#!/bin/sh\nnew\n", target.readText())
    }

    @Test
    fun `sets the executable bit on scripts`() {
        GuestOverlay.sync(
            mapOf(
                "etc/phoshdroid/start.sh" to "#!/bin/sh\n".toByteArray(),
                "etc/phoshdroid/fit-x11.py" to "print(1)\n".toByteArray(),
            ),
            guestRoot,
        )

        assertEquals("rwxr-xr-x", permissions(File(guestRoot, "etc/phoshdroid/start.sh")))
        assertEquals("rwxr-xr-x", permissions(File(guestRoot, "etc/phoshdroid/fit-x11.py")))
    }

    @Test
    fun `rejects a path that tries to leave the guest root`() {
        val changed = GuestOverlay.sync(mapOf("../escape.sh" to "x".toByteArray()), guestRoot)

        assertTrue(changed.isEmpty())
        assertEquals(0, guestRoot.listFiles()?.size ?: 0)
    }
}
