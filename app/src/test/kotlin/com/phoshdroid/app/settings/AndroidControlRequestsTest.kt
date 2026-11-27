package com.phoshdroid.app.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AndroidControlRequestsTest {
    @TempDir lateinit var prefix: File
    private fun withMailbox(block: (File, AndroidControlRequests) -> Unit) {
        File(prefix, "tmp").mkdir()
        AndroidControlFiles.open(prefix).use { files ->
            AndroidControlRequests(files.requests).use { mailbox -> block(File(prefix, "tmp/omdroid-android/requests"), mailbox) }
        }
    }

    @Test fun `only fixed settings pages and rotation directions are accepted`() {
        assertEquals("wifi", AndroidControlRequests.parse("wifi\n"))
        assertEquals("rotation-lock", AndroidControlRequests.parse("rotation-lock"))
        assertEquals("rotation-auto", AndroidControlRequests.parse("rotation-auto"))
        for (input in listOf("rotation", "rotation-toggle", "intent:evil", "wifi; reboot", "../settings", "volume 100", "x".repeat(100))) {
            assertNull(AndroidControlRequests.parse(input))
        }
    }

    @Test fun `published requests are consumed once and incomplete writes ignored`() = withMailbox { directory, mailbox ->
        File(directory, "a.request").writeText("bluetooth")
        File(directory, "b.new").writeText("wifi")
        assertEquals(listOf("bluetooth"), mailbox.take())
        assertEquals(emptyList<String>(), mailbox.take())
        assertTrue(File(directory, "b.new").exists())
    }

    @Test fun `stale and oversized requests are discarded`() = withMailbox { directory, mailbox ->
        val file = File(directory, "old.request").also { it.writeText("wifi") }
        file.setLastModified(System.currentTimeMillis() - 60_000)
        File(directory, "big.request").writeText("wifi".repeat(100))
        assertTrue(mailbox.take().isEmpty())
        assertFalse(file.exists())
        assertFalse(File(directory, "big.request").exists())
    }

    @Test fun `symbolic links cannot consume another file`() = withMailbox { directory, mailbox ->
        val target = File(prefix, "private").also { it.writeText("wifi") }
        Files.createSymbolicLink(File(directory, "link.request").toPath(), target.toPath())
        assertTrue(mailbox.take().isEmpty())
        assertEquals("wifi", target.readText())
        assertFalse(Files.isSymbolicLink(File(directory, "link.request").toPath()))
    }

    @Test fun `flood is streamed in bounded batches and surplus never launches later`() = withMailbox { directory, mailbox ->
        repeat(300) { File(directory, "$it.request").writeText("wifi") }
        assertEquals(1, mailbox.take(elapsed = 1000).size)
        assertTrue(mailbox.hasMore)
        assertTrue(directory.listFiles()!!.size >= 236)
        while (mailbox.hasMore) assertTrue(mailbox.take(elapsed = 1000).isEmpty())
        assertEquals(0, directory.listFiles()!!.size)
        File(directory, "early.request").writeText("bluetooth")
        assertTrue(mailbox.take(elapsed = 1499).isEmpty())
        File(directory, "later.request").writeText("bluetooth")
        assertEquals(listOf("bluetooth"), mailbox.take(elapsed = 1500))
    }

    @Test fun `request directories do not remain executable queue entries`() = withMailbox { directory, mailbox ->
        File(directory, "empty.request").mkdir()
        File(directory, "nested.request").mkdir()
        File(directory, "nested.request/keep").writeText("untouched")
        assertTrue(mailbox.take().isEmpty())
        assertFalse(File(directory, "empty.request").exists())
        assertFalse(File(directory, "nested.request").exists())
        assertEquals("untouched", directory.listFiles()!!.single().resolve("keep").readText())
    }

    @Test fun `replacing the request directory cannot redirect reads`() = withMailbox { directory, mailbox ->
        val moved = File(directory.parentFile, "old-requests")
        Files.move(directory.toPath(), moved.toPath())
        val privateDir = File(prefix, "private").also { it.mkdir() }
        val target = File(privateDir, "private.request").also { it.writeText("wifi") }
        Files.createSymbolicLink(directory.toPath(), privateDir.toPath())
        assertTrue(mailbox.take().isEmpty())
        assertEquals("wifi", target.readText())
    }

    @Test fun `requests from an earlier session are dropped`() {
        File(prefix, "tmp").mkdir()
        AndroidControlFiles.open(prefix).use { files ->
            val request = File(prefix, "tmp/omdroid-android/requests/old.request").also { it.writeText("wifi") }
            request.setLastModified(1000)
            AndroidControlRequests(files.requests, notBefore = 2000).use { mailbox ->
                assertTrue(mailbox.take(now = 3000).isEmpty())
                assertFalse(request.exists())
            }
        }
    }
}
