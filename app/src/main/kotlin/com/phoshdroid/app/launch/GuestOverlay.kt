package com.phoshdroid.app.launch

import android.annotation.SuppressLint
import android.content.res.AssetManager
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Keeps the guest's `/etc/phoshdroid/` identical to the scripts this APK was
 * built from.
 *
 * Before round 3 the rootfs was extracted once and never touched again, so a
 * fix in `rootfs/overlay/etc/phoshdroid/` reached an existing install only
 * after the user wiped 4 GB of app data — and one round was spent on a device
 * whose guest scripts predated the fix. Gradle copies the packager's overlay
 * files into the APK as `assets/guest-overlay/etc/phoshdroid/…`; this object
 * writes them into the extracted guest whenever the bytes differ.
 */
object GuestOverlay {
    /** Asset directory the Gradle task fills; the guest paths start after it. */
    const val ASSET_ROOT = "guest-overlay"

    private const val EXECUTABLE = "rwxr-xr-x"

    /**
     * Writes every [files] entry (relative path to content) under [guestRoot]
     * when the file is missing or its bytes differ, and returns the paths it
     * changed. Nothing is ever deleted; an identical file is not touched at all,
     * so its mtime carries the provenance of the last real change.
     *
     * Every written `.sh` and `.py`, and `start.sh` whatever its suffix, is
     * made owner rwx / group and other r-x — the packager's modes, which matter
     * because the guest runs these as uid 1000 out of a rootfs that proot
     * presents read-only except for this tree's permissions.
     */
    fun sync(
        files: Map<String, ByteArray>,
        guestRoot: File,
    ): List<String> {
        val changed = mutableListOf<String>()
        for ((path, content) in files) {
            // The asset tree is ours, but a path is what decides where bytes
            // land: refuse anything that could leave the guest root.
            if (path.contains("..") || path.startsWith("/")) continue
            val target = File(guestRoot, path)
            if (target.isFile && target.readBytes().contentEquals(content)) continue

            val parent = target.parentFile
            parent?.mkdirs()
            // Write beside the target and rename: a session that starts while
            // this runs must never see a half-written script.
            val temp = File(parent, "${target.name}.new")
            temp.writeBytes(content)
            if (!temp.renameTo(target)) {
                // A rename onto an existing file can fail on some filesystems;
                // fall back to an in-place rewrite rather than skipping the file.
                temp.delete()
                target.writeBytes(content)
            }
            if (isExecutable(path)) setExecutable(target)
            changed.add(path)
        }
        return changed
    }

    private fun isExecutable(path: String): Boolean =
        path.endsWith(".sh") || path.endsWith(".py") || path.substringAfterLast('/') == "start.sh"

    // The guest reads these as uid 1000 through proot, so group and other must
    // be able to read them; this branch only runs where the POSIX view is
    // unavailable, which Android 12+ is not.
    @SuppressLint("SetWorldReadable")
    private fun setExecutable(file: File) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(EXECUTABLE))
        } catch (_: Exception) {
            // No POSIX view: the File API is the best that can be done.
            file.setReadable(true, false)
            file.setWritable(true, true)
            file.setExecutable(true, false)
        }
    }
}

/**
 * The Android edge: reads the overlay tree the Gradle `syncGuestOverlay` task
 * puts in the APK's assets. Kept out of [GuestOverlay] so the write logic
 * stays testable without Android classes.
 */
class GuestOverlayAssets(
    private val assets: AssetManager,
) {
    fun read(): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        walk(GuestOverlay.ASSET_ROOT, files)
        return files
    }

    private fun walk(
        assetDir: String,
        files: MutableMap<String, ByteArray>,
    ) {
        val children = assets.list(assetDir) ?: return
        for (child in children) {
            val path = "$assetDir/$child"
            val grandchildren = assets.list(path)
            if (grandchildren != null && grandchildren.isNotEmpty()) {
                walk(path, files)
            } else {
                // `list` on a file answers an empty array, which is how the
                // asset API distinguishes a file from a directory.
                val relative = path.removePrefix("${GuestOverlay.ASSET_ROOT}/")
                files[relative] = assets.open(path).use { it.readBytes() }
            }
        }
    }
}
