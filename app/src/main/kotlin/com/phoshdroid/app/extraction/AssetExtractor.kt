package com.phoshdroid.app.extraction

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Creates one symbolic link. `android.system.Os` exists only on Android, so the
 * syscall sits behind this seam: production runs [OsLinkCreator], the JVM tests
 * inject a fake that records the calls.
 */
internal fun interface LinkCreator {
    fun create(target: String, link: String)
}

/**
 * [Os.symlink] for the guest image's symlink members. Android's `ln` was only
 * ever a fork/exec of this syscall, and the two images carry 6,656 symlinks, so
 * one process per link was most of the first-launch extraction time.
 *
 * The syscall fails with [OsConstants.EEXIST] when the path is already there (a
 * re-run over a partially extracted tree), so the existing entry is removed
 * exactly then: `ln -sf` semantics without a delete for every entry.
 */
internal object OsLinkCreator : LinkCreator {
    override fun create(target: String, link: String) {
        try {
            Os.symlink(target, link)
        } catch (existing: ErrnoException) {
            if (existing.errno != OsConstants.EEXIST) throw existing
            File(link).delete()
            Os.symlink(target, link)
        }
    }
}

class AssetExtractor internal constructor(private val linkCreator: LinkCreator) {

    constructor() : this(OsLinkCreator)

    /** Members that could not be materialised: hard links without their target, links the kernel refused. */
    var skippedLinks: Int = 0
        private set

    fun extract(
        input: InputStream,
        totalBytes: Long,
        targetDir: File,
        onProgress: (percent: Int) -> Unit = {}
    ): Boolean {
        skippedLinks = 0
        val marker = File(targetDir, ".extraction_complete")
        if (marker.exists()) return false

        targetDir.mkdirs()
        var bytesRead = 0L
        var lastPercent = -1

        // One copy buffer and one directory cache for the whole run: the guest
        // images hold ~67k regular files and ~6.6k symlinks, so a fresh
        // BufferedOutputStream plus a fresh copy buffer per file (what
        // InputStream.copyTo allocates) and a mkdirs for every entry are paid
        // tens of thousands of times.
        val copyBuffer = ByteArray(COPY_BUFFER_BYTES)
        val createdDirs = HashSet<String>()

        CountingInputStream(input) { count ->
            bytesRead = count
            val percent = if (totalBytes > 0) ((count * 100) / totalBytes).toInt().coerceAtMost(100) else 0
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }.let { counting ->
            TarArchiveInputStream(decompress(BufferedInputStream(counting))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        createdDirs.add(outFile.path)
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        ensureParent(outFile, createdDirs)
                        try {
                            linkCreator.create(entry.linkName, outFile.absolutePath)
                        } catch (_: ErrnoException) {
                            // `ln -sf` ignored its exit status, so a link the
                            // kernel refuses leaves the member missing instead
                            // of failing the whole extraction; count it so the
                            // real-image test can see it.
                            skippedLinks++
                        }
                    } else if (entry.isLink) {
                        // Hard-link member into the archive (terminfo and zoneinfo
                        // aliases); the entry carries no data, so the member has to
                        // be materialised from its target. Android denies apps hard
                        // links inside app data, and the old `ln -f` failed without
                        // throwing, which silently left these members missing on
                        // device; copy the target instead (all copies together are
                        // only a few megabytes).
                        ensureParent(outFile, createdDirs)
                        outFile.delete()
                        val target = File(targetDir, entry.linkName)
                        try {
                            if (target.isFile) {
                                target.copyTo(outFile, overwrite = true)
                                if (target.canExecute()) {
                                    outFile.setExecutable(true)
                                }
                            } else {
                                skippedLinks++
                            }
                        } catch (_: Exception) {
                            skippedLinks++
                        }
                    } else {
                        ensureParent(outFile, createdDirs)
                        FileOutputStream(outFile).use { out ->
                            var read = tar.read(copyBuffer)
                            while (read > 0) {
                                out.write(copyBuffer, 0, read)
                                read = tar.read(copyBuffer)
                            }
                        }
                        if (entry.mode and 0b001_001_001 != 0) {
                            outFile.setExecutable(true)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        onProgress(100)
        marker.createNewFile()
        return true
    }

    /**
     * Creates [file]'s parent directory once per run instead of once per entry:
     * every member of the same directory shares the cache key.
     */
    private fun ensureParent(file: File, createdDirs: MutableSet<String>) {
        val parent = file.parentFile ?: return
        if (createdDirs.add(parent.toPath().normalize().toString())) {
            parent.mkdirs()
        }
    }

    /**
     * Picks the decompressor from the stream's own magic bytes: gzip for
     * `bootstrap.bin` (and every `rootfs.bin` up to 0.0.17) and zstd for the
     * 0.0.18 guest image, so an updated APK still extracts an old asset and the
     * bootstrap keeps its format. Unknown bytes fail loudly instead of letting
     * the tar reader report a truncated archive.
     */
    private fun decompress(input: BufferedInputStream): InputStream {
        input.mark(MAGIC_BYTES)
        val magic = ByteArray(MAGIC_BYTES)
        var read = 0
        while (read < magic.size) {
            val n = input.read(magic, read, magic.size - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        return when {
            read >= 2 && magic[0] == GZIP_MAGIC_0 && magic[1] == GZIP_MAGIC_1 ->
                GZIPInputStream(input)
            read >= 4 && magic.copyOf(4).contentEquals(ZSTD_MAGIC) ->
                ZstdInputStream(input)
            else ->
                throw IOException(
                    "unknown image compression: " +
                        magic.take(read).joinToString(" ") { "%02x".format(it) }
                )
        }
    }

    private companion object {
        const val MAGIC_BYTES = 4

        /** Large enough that a whole regular file usually lands in one write. */
        const val COPY_BUFFER_BYTES = 256 * 1024

        val GZIP_MAGIC_0: Byte = 0x1f
        val GZIP_MAGIC_1: Byte = 0x8b.toByte()
        val ZSTD_MAGIC = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())
    }
}

private class CountingInputStream(
    private val wrapped: InputStream,
    private val onBytesRead: (Long) -> Unit
) : InputStream() {

    private var totalRead = 0L

    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) {
            totalRead++
            onBytesRead(totalRead)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) {
            totalRead += n
            onBytesRead(totalRead)
        }
        return n
    }

    override fun close() = wrapped.close()
}
