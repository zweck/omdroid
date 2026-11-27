package com.phoshdroid.app.settings

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView

/** All shared-path operations stay relative to open directory handles, even during a rename race. */
class AndroidControlFiles private constructor(
    val directory: SecureDirectoryStream<Path>,
    val requests: SecureDirectoryStream<Path>,
) : Closeable {
    fun publish(text: String) {
        val temporary = Paths.get("state.json.new")
        try { directory.deleteFile(temporary) } catch (_: NoSuchFileException) { }
        directory.newByteChannel(temporary, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
        }
        directory.move(temporary, directory, Paths.get("state.json"))
    }

    override fun close() {
        try { directory.deleteFile(Paths.get("state.json")) } catch (_: Exception) { }
        requests.close()
        directory.close()
    }

    companion object {
        fun open(prefix: File): AndroidControlFiles {
            // The prefix is app-private, outside all guest bind mounts. Only its tmp child is shared.
            val parent = Files.newDirectoryStream(prefix.toPath())
            require(parent is SecureDirectoryStream<Path>) { "Race-safe directory access unavailable" }
            parent.use { root ->
                root.newDirectoryStream(Paths.get("tmp"), NOFOLLOW_LINKS).use { tmp ->
                    ensureDirectory(prefix, root, tmp, "omdroid-android")
                    val directory = tmp.newDirectoryStream(Paths.get("omdroid-android"), NOFOLLOW_LINKS)
                    try {
                        ensureDirectory(prefix, root, directory, "requests")
                        return AndroidControlFiles(directory, directory.newDirectoryStream(Paths.get("requests"), NOFOLLOW_LINKS))
                    } catch (error: Exception) {
                        directory.close()
                        throw error
                    }
                }
            }
        }

        private fun ensureDirectory(
            prefix: File,
            root: SecureDirectoryStream<Path>,
            parent: SecureDirectoryStream<Path>,
            name: String,
        ) {
            val path = Paths.get(name)
            val attributes = try {
                parent.getFileAttributeView(path, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            } catch (_: NoSuchFileException) { null }
            if (attributes != null) {
                require(attributes.isDirectory && !attributes.isSymbolicLink) { "Invalid controls directory" }
                return
            }
            // mkdirat is not exposed by NIO. Create outside the guest, then rename into the pinned directory.
            val staging = Files.createTempDirectory(prefix.toPath(), ".android-controls-")
            try {
                root.move(staging.fileName, parent, path)
            } finally {
                Files.deleteIfExists(staging)
            }
        }
    }
}
