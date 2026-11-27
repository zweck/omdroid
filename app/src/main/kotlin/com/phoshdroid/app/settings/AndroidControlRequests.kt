package com.phoshdroid.app.settings

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.util.UUID

/** Streaming, bounded consumption; no guest-sized list, recursive deletion, or follow-link read. */
class AndroidControlRequests(
    private val directory: SecureDirectoryStream<Path>,
    private val notBefore: Long = 0,
) : Closeable {
    private var listing: SecureDirectoryStream<Path>? = null
    private var iterator: Iterator<Path>? = null
    private var lastActionAt: Long? = null
    var hasMore = false
        private set

    fun take(now: Long = System.currentTimeMillis(), elapsed: Long = System.nanoTime() / 1_000_000): List<String> {
        if (listing == null) {
            listing = directory.newDirectoryStream(Paths.get("."), NOFOLLOW_LINKS)
            iterator = listing!!.iterator()
        }
        var action: String? = null
        val entries = iterator!!
        repeat(64) {
            if (!entries.hasNext()) return@repeat
            val name = entries.next().fileName
            if (!name.toString().endsWith(".request")) return@repeat
            try {
                val attributes = directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                val modified = attributes.lastModifiedTime().toMillis()
                if (attributes.isDirectory) {
                    // Remove empty directories; quarantine nonempty ones without traversing guest-sized trees.
                    try { directory.deleteDirectory(name) } catch (_: Exception) {
                        directory.move(name, directory, Paths.get(".rejected-${UUID.randomUUID()}"))
                    }
                } else if (action == null && (lastActionAt == null || elapsed - lastActionAt!! >= 500) &&
                    attributes.isRegularFile && attributes.size() <= 32 && modified >= notBefore && now - modified in 0..30_000
                ) {
                    directory.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                        // Read at most 33 bytes even if the writer grows the file after the size check.
                        val bytes = ByteBuffer.allocate(33)
                        while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
                        action = parse(String(bytes.array(), 0, bytes.position(), Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {
                // Invalid, vanished, or replaced entries never produce an action.
            } finally {
                try { directory.deleteFile(name) } catch (_: Exception) { }
            }
        }
        hasMore = entries.hasNext()
        if (!hasMore) close()
        if (action != null) lastActionAt = elapsed
        return listOfNotNull(action)
    }

    override fun close() {
        listing?.close()
        listing = null
        iterator = null
        hasMore = false
    }

    companion object {
        val actions = setOf("wifi", "bluetooth", "display", "rotation-lock", "rotation-auto", "volume", "airplane", "battery", "settings")
        fun parse(text: String): String? = text.trim().takeIf { text.length <= 32 && it in actions }
    }
}
