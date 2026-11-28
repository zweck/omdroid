package com.phoshdroid.app.extraction

import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.IOException
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

class AssetExtractorTest {

    @TempDir
    lateinit var tempDir: File

    /**
     * Stands in for the `android.system.Os` syscall on the JVM: records every
     * call and applies it with NIO, so the archive assertions still see real
     * symbolic links on disk.
     */
    private class RecordingLinkCreator : LinkCreator {
        val calls = mutableListOf<Pair<String, String>>()

        override fun create(target: String, link: String) {
            calls += target to link
            val path = Paths.get(link)
            path.parent?.let { Files.createDirectories(it) }
            Files.deleteIfExists(path)
            Files.createSymbolicLink(path, Paths.get(target))
        }
    }

    /** Production constructs a no-arg extractor; JVM tests inject the seam. */
    private fun newExtractor() = AssetExtractor(RecordingLinkCreator())

    /** The two container formats the extractor has to accept. */
    private enum class Codec { GZIP, ZSTD }

    private fun wrap(baos: ByteArrayOutputStream, codec: Codec): OutputStream = when (codec) {
        Codec.GZIP -> GZIPOutputStream(baos)
        Codec.ZSTD -> ZstdOutputStream(baos)
    }

    private fun createTar(files: Map<String, ByteArray>, codec: Codec = Codec.GZIP): ByteArray {
        val baos = ByteArrayOutputStream()
        wrap(baos, codec).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for ((name, content) in files) {
                    val entry = tar.createArchiveEntry(File(name), name)
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    private fun createTarWithLinks(
        files: Map<String, ByteArray>,
        links: List<Pair<String, String>>,
        modes: Map<String, Int> = emptyMap(),
        codec: Codec = Codec.GZIP
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        wrap(baos, codec).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for ((name, content) in files) {
                    val entry = TarArchiveEntry(name)
                    entry.mode = modes[name] ?: TarArchiveEntry.DEFAULT_FILE_MODE
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
                for ((name, linkTarget) in links) {
                    val entry = TarArchiveEntry(name, TarConstants.LF_LINK)
                    entry.linkName = linkTarget
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    /** An archive holding every entry type the real image uses. */
    private fun createMixedTar(codec: Codec): ByteArray {
        val baos = ByteArrayOutputStream()
        wrap(baos, codec).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                val exe = TarArchiveEntry("./usr/bin/tool")
                exe.mode = 0b111_101_101
                exe.size = "#!/bin/sh\n".toByteArray().size.toLong()
                tar.putArchiveEntry(exe)
                tar.write("#!/bin/sh\n".toByteArray())
                tar.closeArchiveEntry()

                val data = TarArchiveEntry("./usr/share/data.txt")
                data.mode = 0b110_100_100
                data.size = 6
                tar.putArchiveEntry(data)
                tar.write("hello\n".toByteArray())
                tar.closeArchiveEntry()

                val symlink = TarArchiveEntry("./usr/bin/tool-link", TarConstants.LF_SYMLINK)
                symlink.linkName = "./usr/bin/tool"
                tar.putArchiveEntry(symlink)
                tar.closeArchiveEntry()

                val hardlink = TarArchiveEntry("./usr/bin/tool-copy", TarConstants.LF_LINK)
                hardlink.linkName = "./usr/share/data.txt"
                tar.putArchiveEntry(hardlink)
                tar.closeArchiveEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun extract(bytes: ByteArray, target: File = tempDir) {
        newExtractor().extract(
            input = ByteArrayInputStream(bytes),
            totalBytes = bytes.size.toLong(),
            targetDir = target
        )
    }

    @Test
    fun `extracts single file from tar zst`() {
        val content = "hello world".toByteArray()
        val archive = createTar(mapOf("test.txt" to content))

        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        val extracted = File(tempDir, "test.txt")
        assertTrue(extracted.exists())
        assertEquals("hello world", extracted.readText())
    }

    @Test
    fun `extracts nested directory structure`() {
        val archive = createTar(mapOf(
            "usr/bin/hello" to "#!/bin/sh".toByteArray(),
            "etc/config" to "key=value".toByteArray()
        ))

        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertTrue(File(tempDir, "usr/bin/hello").exists())
        assertEquals("key=value", File(tempDir, "etc/config").readText())
    }

    @Test
    fun `reports progress during extraction`() {
        val largeContent = ByteArray(10_000) { it.toByte() }
        val archive = createTar(mapOf("big.bin" to largeContent))

        val progressValues = mutableListOf<Int>()
        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir,
            onProgress = { percent -> progressValues.add(percent) }
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(100, progressValues.last())
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun `skips extraction if marker file exists`() {
        val archive = createTar(mapOf("test.txt" to "hello".toByteArray()))

        File(tempDir, ".extraction_complete").createNewFile()

        val extractor = newExtractor()
        val result = extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertFalse(result)
        assertFalse(File(tempDir, "test.txt").exists())
    }

    @Test
    fun `copies a hard link member as a file with the same content`() {
        val content = "elf payload".toByteArray()
        val archive = createTarWithLinks(
            files = mapOf("./usr/bin/ld.bfd" to content),
            links = listOf("./usr/bin/ld" to "./usr/bin/ld.bfd")
        )

        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        val link = File(tempDir, "usr/bin/ld")
        assertTrue(link.exists())
        assertTrue(Files.isRegularFile(link.toPath()))
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertArrayEquals(content, link.readBytes())
    }

    @Test
    fun `the copy is independent of its target`() {
        val content = "original bytes".toByteArray()
        val archive = createTarWithLinks(
            files = mapOf("./usr/bin/ld.bfd" to content),
            links = listOf("./usr/bin/ld" to "./usr/bin/ld.bfd")
        )

        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        File(tempDir, "usr/bin/ld.bfd").writeText("replaced")

        assertEquals("original bytes", File(tempDir, "usr/bin/ld").readText())
    }

    @Test
    fun `keeps the executable bit on a copied hard link`() {
        val archive = createTarWithLinks(
            files = mapOf("./usr/bin/ld.bfd" to "#!/bin/sh\n".toByteArray()),
            links = listOf("./usr/bin/ld" to "./usr/bin/ld.bfd"),
            modes = mapOf("./usr/bin/ld.bfd" to 0b111_101_101)
        )

        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertTrue(File(tempDir, "usr/bin/ld").canExecute())
    }

    @Test
    fun `skips a hard link whose target is missing and still completes`() {
        val archive = createTarWithLinks(
            files = mapOf("etc/hostname" to "guest".toByteArray()),
            links = listOf("./usr/bin/ld" to "./usr/bin/ld.bfd")
        )

        val extractor = newExtractor()
        val result = extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertTrue(result)
        assertTrue(File(tempDir, ".extraction_complete").exists())
        assertFalse(File(tempDir, "usr/bin/ld").exists())
        assertEquals(1, extractor.skippedLinks)
    }

    @Test
    fun `counts no skipped links for a normal archive`() {
        val archive = createTarWithLinks(
            files = mapOf("./usr/bin/ld.bfd" to "elf payload".toByteArray()),
            links = listOf("./usr/bin/ld" to "./usr/bin/ld.bfd")
        )

        val extractor = newExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertEquals(0, extractor.skippedLinks)
    }

    // The two container formats, each with the full set of entry types.

    @Test
    fun `extracts a gzip image with modes symlinks and hard links`() = assertMixedArchive(Codec.GZIP)

    @Test
    fun `extracts a zstd image with modes symlinks and hard links`() = assertMixedArchive(Codec.ZSTD)

    private fun assertMixedArchive(codec: Codec) {
        extract(createMixedTar(codec))

        assertTrue(File(tempDir, "usr/bin/tool").canExecute(), "$codec: executable bit")
        assertEquals("hello\n", File(tempDir, "usr/share/data.txt").readText())
        val link = File(tempDir, "usr/bin/tool-link")
        assertTrue(Files.isSymbolicLink(link.toPath()), "$codec: symlink not created")
        assertEquals("./usr/bin/tool", Files.readSymbolicLink(link.toPath()).toString())
        val copy = File(tempDir, "usr/bin/tool-copy")
        assertTrue(Files.isRegularFile(copy.toPath()), "$codec: hard link member missing")
        assertEquals("hello\n", copy.readText())
        assertTrue(File(tempDir, ".extraction_complete").exists(), "$codec: no completion marker")
    }

    @Test
    fun `extracts a directory file symlink and hard link member through the seam`() {
        val payload = "#!/bin/sh\nexit 0\n".toByteArray()
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("./usr/lib", TarConstants.LF_DIR))
                tar.closeArchiveEntry()

                val tool = TarArchiveEntry("./usr/lib/tool")
                tool.mode = 0b111_101_101
                tool.size = payload.size.toLong()
                tar.putArchiveEntry(tool)
                tar.write(payload)
                tar.closeArchiveEntry()

                val symlink = TarArchiveEntry("./usr/lib/tool-link", TarConstants.LF_SYMLINK)
                symlink.linkName = "./usr/lib/tool"
                tar.putArchiveEntry(symlink)
                tar.closeArchiveEntry()

                val hardlink = TarArchiveEntry("./usr/lib/tool-copy", TarConstants.LF_LINK)
                hardlink.linkName = "./usr/lib/tool"
                tar.putArchiveEntry(hardlink)
                tar.closeArchiveEntry()
            }
        }
        val archive = baos.toByteArray()
        val links = RecordingLinkCreator()

        AssetExtractor(links).extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        val dir = File(tempDir, "usr/lib")
        assertTrue(dir.isDirectory, "directory entry not created")
        assertArrayEquals(payload, File(dir, "tool").readBytes(), "regular file bytes")
        assertTrue(File(dir, "tool").canExecute(), "executable bit")

        val call = links.calls.single()
        assertEquals("./usr/lib/tool", call.first, "the seam must get the entry's linkName")
        assertEquals(
            File(dir, "tool-link").toPath().normalize().toString(),
            File(call.second).toPath().normalize().toString(),
            "the seam must get the link's own absolute path"
        )
        assertTrue(Files.isSymbolicLink(File(dir, "tool-link").toPath()))

        val copy = File(dir, "tool-copy")
        assertTrue(Files.isRegularFile(copy.toPath()), "hard link member missing")
        assertArrayEquals(payload, copy.readBytes(), "hard link copy bytes")
    }

    @Test
    fun `copies a file larger than the shared copy buffer intact`() {
        val payload = ByteArray(600_000) { (it * 7).toByte() }
        val archive = createTar(mapOf("./usr/share/big.bin" to payload))

        newExtractor().extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertArrayEquals(payload, File(tempDir, "usr/share/big.bin").readBytes())
    }

    @Test
    fun `rejects a stream that is neither gzip nor zstd`() {
        val bogus = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val thrown = assertThrows(IOException::class.java) {
            newExtractor().extract(ByteArrayInputStream(bogus), bogus.size.toLong(), tempDir)
        }
        assertTrue(thrown.message!!.contains("unknown image compression"), thrown.message)
        assertFalse(File(tempDir, ".extraction_complete").exists())
    }

    /**
     * Real-image extraction, off by default: set OMDROID_IMAGE to the packed asset
     * (rootfs.bin) and optionally OMDROID_IMAGE_TARGET to a directory with ~4 GB
     * free (defaults to the JUnit temp dir), and OMDROID_IMAGE_ENTRIES to the
     * expected number of non-directory members to check the count.
     */
    @Test
    fun `extracts the real image when OMDROID_IMAGE names it`() {
        val imagePath = System.getenv("OMDROID_IMAGE")
        assumeTrue(imagePath != null && File(imagePath).isFile, "OMDROID_IMAGE not set")
        val target = File(System.getenv("OMDROID_IMAGE_TARGET") ?: tempDir.path)
        val image = File(imagePath)

        val extractor = newExtractor()
        image.inputStream().use { input ->
            extractor.extract(input, image.length(), target)
        }

        // A second run over the same target (the release variant of this task)
        // finds the marker and returns false on purpose, so assert the tree.
        // Delete OMDROID_IMAGE_TARGET to force a fresh extraction.
        assertTrue(File(target, ".extraction_complete").exists(), "extraction did not complete")
        assertEquals(0, extractor.skippedLinks, "hard-link members missing from their targets")
        assertTrue(File(target, "etc/phoshdroid/start.sh").isFile, "guest session script missing")
        assertTrue(File(target, "usr/bin/cage").isFile, "parent compositor missing")

        System.getenv("OMDROID_IMAGE_ENTRIES")?.toIntOrNull()?.let { expected ->
            // walkFileTree does not follow symlinks (the guest has directory
            // symlinks that would loop otherwise); every non-directory entry is
            // materialised, hard-link members as copies, plus the marker.
            var count = 0
            Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isDirectory) count++
                    return FileVisitResult.CONTINUE
                }
            })
            assertEquals(expected + 1, count, "extracted non-directory members plus the marker")
        }
    }
}
