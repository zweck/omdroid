package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class GuestTimezoneTest {
    @TempDir lateinit var root: File

    private fun prepare(): File {
        File(root, "etc").mkdirs()
        return File(root, "usr/share/zoneinfo/Europe/Paris").apply {
            parentFile.mkdirs()
            writeText("TZif-test")
        }
    }

    @Test fun `creates a resolvable named zone link and is idempotent`() {
        val zone = prepare()
        assertTrue(GuestTimezone.sync(root, "Europe/Paris"))
        val localtime = File(root, "etc/localtime")
        assertEquals(zone.canonicalFile, localtime.canonicalFile)
        assertEquals(Path.of("../usr/share/zoneinfo/Europe/Paris"), Files.readSymbolicLink(localtime.toPath()))
        assertFalse(GuestTimezone.sync(root, "Europe/Paris"))
    }

    @Test fun `replaces an absolute old link without writing its target`() {
        prepare()
        val outside = File(root, "outside").apply { writeText("preserve") }
        Files.createSymbolicLink(File(root, "etc/localtime").toPath(), outside.toPath())
        assertTrue(GuestTimezone.sync(root, "Europe/Paris"))
        assertEquals("preserve", outside.readText())
    }

    @Test fun `replaces a copied localtime file`() {
        prepare()
        File(root, "etc/localtime").writeText("old zone")
        assertTrue(GuestTimezone.sync(root, "Europe/Paris"))
        assertEquals("TZif-test", File(root, "etc/localtime").readText())
    }

    @Test fun `missing and malformed zones preserve existing configuration`() {
        prepare()
        val localtime = File(root, "etc/localtime").apply { writeText("preserve") }
        listOf("", "../outside", "/UTC", "Europe//Paris", "Europe/./Paris", "UTC\n", "Missing/Zone").forEach {
            assertFalse(GuestTimezone.sync(root, it), it)
            assertEquals("preserve", localtime.readText())
        }
    }

    @Test fun `rejects zone aliases escaping the zone directory`() {
        prepare()
        val outside = File(root, "outside").apply { writeText("preserve") }
        Files.createSymbolicLink(File(root, "usr/share/zoneinfo/Escape").toPath(), outside.toPath())
        assertFalse(GuestTimezone.sync(root, "Escape"))
        assertFalse(File(root, "etc/localtime").exists())
    }

    @Test fun `updates a previous zone on the next session`() {
        prepare()
        File(root, "usr/share/zoneinfo/UTC").writeText("UTC")
        assertTrue(GuestTimezone.sync(root, "UTC"))
        assertTrue(GuestTimezone.sync(root, "Europe/Paris"))
        assertEquals("TZif-test", File(root, "etc/localtime").readText())
    }
}
