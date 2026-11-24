package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class SessionPrepTest {

    @TempDir
    lateinit var tmpDir: File

    @Test
    fun `wipes the runtime dir completely and recreates it 0700`() {
        val runtime = File(tmpDir, "omarchy-runtime")
        File(runtime, "hypr/instance-1").mkdirs()
        File(runtime, "hypr/instance-1/socket").writeText("old")
        File(runtime, "dbus-abc").writeText("stale")
        File(runtime, "omarchy-ready").writeText("ready 1 100x100 scale=2")

        val removed = SessionPrep(tmpDir).prepare()

        assertTrue(removed.contains(runtime.absolutePath))
        assertTrue(runtime.isDirectory)
        assertEquals(0, runtime.listFiles()?.size)
        val permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(runtime.toPath()))
        assertEquals("rwx------", permissions)
    }

    @Test
    fun `removes stale proot sockets and x leftovers but keeps logs`() {
        val runtime = File(tmpDir, "omarchy-runtime").apply { mkdirs() }
        val prootSocket = File(tmpDir, "proot-4242-fake-socket").apply { writeText("s") }
        val prootDir = File(tmpDir, "proot-4242-1000").apply { mkdirs(); File(this, "f").writeText("x") }
        val exitLog = File(tmpDir, "proot-exit.log").apply { writeText("exit=1") }
        val sessionLog = File(tmpDir, "omarchy-session.log").apply { writeText("session") }
        val startLog = File(tmpDir, "phoshdroid-start.log").apply { writeText("start") }
        val x0 = File(tmpDir, ".X11-unix/X0").apply { parentFile!!.mkdirs(); writeText("x") }
        val xLock = File(tmpDir, ".X0-lock").apply { writeText("1") }
        val wayland = File(tmpDir, "wayland-omarchy").apply { writeText("w") }
        val waylandLock = File(tmpDir, "wayland-omarchy.lock").apply { writeText("w") }

        SessionPrep(tmpDir).prepare()

        assertFalse(prootSocket.exists())
        assertFalse(prootDir.exists())
        assertFalse(x0.exists())
        assertFalse(xLock.exists())
        assertFalse(wayland.exists())
        assertFalse(waylandLock.exists())
        assertTrue(exitLog.isFile)
        assertTrue(sessionLog.isFile)
        assertTrue(startLog.isFile)
        assertTrue(runtime.isDirectory)
    }

    @Test
    fun `keepXServer leaves the live X socket and lock alone`() {
        val x0 = File(tmpDir, ".X11-unix/X0").apply { parentFile!!.mkdirs(); writeText("x") }
        val xLock = File(tmpDir, ".X0-lock").apply { writeText("1") }
        val wayland = File(tmpDir, "wayland-omarchy").apply { writeText("w") }
        File(tmpDir, "omarchy-runtime/hypr").mkdirs()

        val removed = SessionPrep(tmpDir).prepare(keepXServer = true)

        assertTrue(x0.isFile)
        assertTrue(xLock.isFile)
        assertFalse(wayland.exists())
        assertFalse(removed.contains(x0.absolutePath))
        assertEquals(0, File(tmpDir, "omarchy-runtime").listFiles()?.size)
    }

    @Test
    fun `creates tmp and runtime when they do not exist yet`() {
        val missing = File(tmpDir, "usr/tmp")

        SessionPrep(missing).prepare()

        assertTrue(missing.isDirectory)
        assertTrue(File(missing, "omarchy-runtime").isDirectory)
    }
}
