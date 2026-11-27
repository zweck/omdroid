package com.phoshdroid.app.launch

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Gives every guest application Android's named time zone before the session starts. */
object GuestTimezone {
    /** Missing/invalid zones leave an existing configuration intact. */
    fun sync(guestRoot: File, zoneId: String): Boolean {
        if (zoneId.isEmpty() || zoneId.startsWith('/') ||
            zoneId.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            !zoneId.matches(Regex("[A-Za-z0-9_+./-]+"))
        ) return false

        val root = guestRoot.canonicalFile.toPath()
        val zoneDir = File(guestRoot, "usr/share/zoneinfo").canonicalFile.toPath()
        val zoneFile = zoneDir.resolve(zoneId).toFile().canonicalFile.toPath()
        val etc = File(guestRoot, "etc").canonicalFile.toPath()
        if (!zoneDir.startsWith(root) || !zoneFile.startsWith(zoneDir) ||
            !etc.startsWith(root) || !Files.isDirectory(etc) || !Files.isRegularFile(zoneFile)
        ) return false

        // A relative link resolves inside both the Android filesystem and proot.
        // Keeping the zone name also lets Qt discover the system zone identity.
        val target = Paths.get("../usr/share/zoneinfo/$zoneId")
        val localtime = etc.resolve("localtime")
        if (Files.isSymbolicLink(localtime) && Files.readSymbolicLink(localtime) == target) return false

        val temp = Files.createTempFile(etc, ".localtime-", ".new")
        try {
            Files.delete(temp)
            Files.createSymbolicLink(temp, target)
            Files.move(temp, localtime, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return true
    }
}
