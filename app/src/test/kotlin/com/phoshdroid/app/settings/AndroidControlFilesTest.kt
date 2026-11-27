package com.phoshdroid.app.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class AndroidControlFilesTest {
    @TempDir lateinit var prefix: File

    @Test fun `state writes replace links without modifying their targets`() {
        File(prefix, "tmp").mkdir()
        AndroidControlFiles.open(prefix).use { files ->
            val directory = File(prefix, "tmp/omdroid-android")
            val privateFile = File(prefix, "private-prefs").also { it.writeText("private") }
            for (name in listOf("state.json", "state.json.new")) {
                Files.createSymbolicLink(File(directory, name).toPath(), privateFile.toPath())
            }
            files.publish("new state")
            assertEquals("private", privateFile.readText())
            assertEquals("new state", File(directory, "state.json").readText())
            assertFalse(Files.isSymbolicLink(File(directory, "state.json").toPath()))
        }
    }

    @Test fun `symbolic bridge and request directories are refused`() {
        File(prefix, "tmp").mkdir()
        val privateDir = File(prefix, "private").also { it.mkdir() }
        val directory = File(prefix, "tmp/omdroid-android")
        Files.createSymbolicLink(directory.toPath(), privateDir.toPath())
        assertThrows(Exception::class.java) { AndroidControlFiles.open(prefix) }
        Files.delete(directory.toPath())
        directory.mkdir()
        Files.createSymbolicLink(File(directory, "requests").toPath(), privateDir.toPath())
        assertThrows(Exception::class.java) { AndroidControlFiles.open(prefix) }
        assertEquals(0, privateDir.listFiles()!!.size)
    }

    @Test fun `directory rename race cannot redirect state publish or cleanup`() {
        File(prefix, "tmp").mkdir()
        AndroidControlFiles.open(prefix).use { files ->
            val directory = File(prefix, "tmp/omdroid-android")
            val moved = File(prefix, "tmp/moved")
            val privateDir = File(prefix, "private").also { it.mkdir() }
            val target = File(privateDir, "state.json").also { it.writeText("private") }
            Files.move(directory.toPath(), moved.toPath())
            Files.createSymbolicLink(directory.toPath(), privateDir.toPath())
            files.publish("state")
            assertEquals("private", target.readText())
            assertEquals("state", File(moved, "state.json").readText())
        }
        assertEquals("private", File(prefix, "private/state.json").readText())
    }
}
