package com.phoshdroid.app.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AndroidControlRequestsTest {
    @TempDir lateinit var directory: File

    @Test fun `only fixed settings pages and rotation directions are accepted`() {
        assertEquals("wifi", AndroidControlRequests.parse("wifi\n"))
        assertEquals("rotation-lock", AndroidControlRequests.parse("rotation-lock"))
        assertEquals("rotation-auto", AndroidControlRequests.parse("rotation-auto"))
        for (input in listOf("rotation", "rotation-toggle", "intent:evil", "wifi; reboot", "../settings", "volume 100", "x".repeat(100))) {
            assertNull(AndroidControlRequests.parse(input))
        }
    }

    @Test fun `published requests are consumed once and incomplete writes ignored`() {
        File(directory, "a.request").writeText("bluetooth")
        File(directory, "b.new").writeText("wifi")
        val mailbox = AndroidControlRequests(directory)
        assertEquals(listOf("bluetooth"), mailbox.take())
        assertEquals(emptyList<String>(), mailbox.take())
        assertTrue(File(directory, "b.new").exists())
    }

    @Test fun `stale requests are not replayed`() {
        val file = File(directory, "old.request").also { it.writeText("wifi") }
        file.setLastModified(System.currentTimeMillis() - 60_000)
        assertTrue(AndroidControlRequests(directory).take().isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `symbolic links cannot consume another file`() {
        val target = File(directory, "target").also { it.writeText("wifi") }
        Files.createSymbolicLink(File(directory, "link.request").toPath(), target.toPath())
        assertTrue(AndroidControlRequests(directory).take().isEmpty())
        assertEquals("wifi", target.readText())
    }
}
