package com.phoshdroid.app.settings

import java.io.File
import java.nio.file.Files

/** A private, bounded mailbox for fixed controls, never arbitrary commands or intents. */
class AndroidControlRequests(private val directory: File) {
    fun take(now: Long = System.currentTimeMillis()): List<String> {
        val requests = directory.listFiles()?.filter { it.name.endsWith(".request") }
            ?.sortedBy { it.name }?.take(16) ?: return emptyList()
        return requests.mapNotNull { file ->
            if (!file.name.endsWith(".request") || Files.isSymbolicLink(file.toPath()) || !file.isFile) {
                return@mapNotNull null
            }
            try {
                val age = now - file.lastModified()
                if (file.length() > 32 || age !in 0..30_000) null else parse(file.readText())
            } finally {
                file.delete()
            }
        }
    }

    companion object {
        val actions = setOf("wifi", "bluetooth", "display", "rotation-lock", "rotation-auto", "volume", "airplane", "battery", "settings")

        fun parse(text: String): String? = text.trim().takeIf { text.length <= 32 && it in actions }
    }
}
