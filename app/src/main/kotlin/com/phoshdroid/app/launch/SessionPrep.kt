package com.phoshdroid.app.launch

import java.io.File

/**
 * Prepares the shared prefix for a new guest session, on the host side and
 * before anything starts writing into it.
 *
 * `<prefix>/tmp/omarchy-runtime` is the guest's `XDG_RUNTIME_DIR`: nothing in
 * it is meant to survive a session, so it is wiped completely and recreated
 * 0700. Sockets left in `<prefix>/tmp` by a killed proot process and the
 * X/Wayland sockets of the previous run are removed too. Logs are kept: they
 * are the only evidence after a failed start.
 */
class SessionPrep(
    private val tmpDir: File,
) {
    private val runtimeDir = File(tmpDir, "omarchy-runtime")

    /**
     * Prepares the prefix and returns the paths it removed, in order, so the
     * launcher can log them.
     *
     * [keepXServer] is true when this process already runs the X server: its
     * socket and lock under `<prefix>/tmp` belong to that live server, so a
     * second bootstrap must leave them alone — removing them would not stop
     * the server, only cut every future client off from it.
     */
    fun prepare(keepXServer: Boolean = false): List<String> {
        val removed = mutableListOf<String>()
        tmpDir.mkdirs()

        if (runtimeDir.exists()) {
            runtimeDir.deleteRecursively()
            removed.add(runtimeDir.absolutePath)
        }
        runtimeDir.mkdirs()
        // XDG_RUNTIME_DIR must be private to the app (0700): a world-writable
        // dir lets another uid in the proot namespace create the sockets.
        // File.set*() only touches the owner bits when granting with
        // ownerOnly=true, so clear every class first and then grant owner-only.
        runtimeDir.setReadable(false, false)
        runtimeDir.setWritable(false, false)
        runtimeDir.setExecutable(false, false)
        runtimeDir.setReadable(true, true)
        runtimeDir.setWritable(true, true)
        runtimeDir.setExecutable(true, true)

        val stale = mutableListOf<File>()
        if (!keepXServer) {
            stale.add(File(tmpDir, ".X11-unix/X0"))
            stale.add(File(tmpDir, ".X0-lock"))
        }
        stale.add(File(tmpDir, "wayland-omarchy"))
        stale.add(File(tmpDir, "wayland-omarchy.lock"))
        // `proot-<pid>` sockets and `proot-<pid>-*` directories from dead
        // sessions. `proot-exit.log` deliberately does not match.
        tmpDir.listFiles()?.filter { PROOT_ENTRY.matches(it.name) }?.let { stale.addAll(it) }

        stale.forEach { entry ->
            if (entry.exists()) {
                entry.deleteRecursively()
                removed.add(entry.absolutePath)
            }
        }
        return removed
    }

    companion object {
        private val PROOT_ENTRY = Regex("""proot-\d+(-.*)?""")
    }
}
