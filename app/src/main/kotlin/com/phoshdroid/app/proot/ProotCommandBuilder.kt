package com.phoshdroid.app.proot

/**
 * Builds the proot command for the Omarchy guest.
 *
 * The guest session runs as uid/gid 1000 via proot's `-i` mapping instead of
 * fake root (`-0`) plus `su`: no PAM/password, and apps that treat
 * `geteuid() == 0` as unsupported get a normal user. The guest therefore needs
 * no root-side prep; the app prepares the runtime dir and fake /proc files
 * before launch.
 */
class ProotCommandBuilder(
    private val nativeLibDir: String,
    private val prefixDir: String,
    private val distroName: String,
) {
    private val prootPath = "$nativeLibDir/libproot.so"
    private val rootfsDir get() = "$prefixDir/var/lib/proot-distro/installed-rootfs/$distroName"
    private val fakeProc get() = "$rootfsDir/etc/phoshdroid/fake-proc"

    /** The guest's XDG_RUNTIME_DIR, bound below; the Wayland socket lives here. */
    val runtimeDir: String get() = "$prefixDir/tmp/omarchy-runtime"

    fun buildLoginCommand(
        startupScript: String,
        bindSdcard: Boolean = false,
    ): List<String> {
        val cmd =
            mutableListOf(
                prootPath,
                "--kill-on-exit",
                "--link2symlink",
                "-i",
                "1000:1000",
                "-w",
                "/home/omarchy",
                "-r",
                rootfsDir,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "$prefixDir/tmp:/tmp",
                "-b",
                "$prefixDir/tmp:/dev/shm",
                // XDG_RUNTIME_DIR: the guest's compositor and its clients must
                // see the same socket.
                "-b",
                "$runtimeDir:/run/user/1000",
                // Android denies /proc/bus/pci/devices to apps. Chromium and
                // some EGL/Vulkan probes treat the denial as fatal before they
                // reach GPU init; an empty file is a valid, accurate answer.
                "-b",
                "/dev/null:/proc/bus/pci/devices",
                // Android SELinux blocks /proc/stat, /proc/meminfo, /proc/loadavg,
                // /proc/uptime and /proc/cpuinfo for apps. Shadow them with the
                // static plausible values from the rootfs overlay so status
                // readers and system monitors do not bail out.
                "-b",
                "$fakeProc/stat:/proc/stat",
                "-b",
                "$fakeProc/meminfo:/proc/meminfo",
                "-b",
                "$fakeProc/loadavg:/proc/loadavg",
                "-b",
                "$fakeProc/uptime:/proc/uptime",
                "-b",
                "$fakeProc/cpuinfo:/proc/cpuinfo",
            )

        if (bindSdcard) {
            cmd.add("-b")
            cmd.add("/sdcard:/home/omarchy/Phone")
            cmd.add("-b")
            cmd.add("/sdcard:/sdcard")
        }

        cmd.add("/bin/bash")
        cmd.add(startupScript)

        return cmd
    }

    fun buildEnvironment(
        width: Int,
        height: Int,
        scale: Int,
        refreshMhz: Int,
        bindSdcard: Boolean = false,
    ): Map<String, String> {
        val hyprlandStage = "/opt/omarchy-android/hyprland"
        val mesa = "/opt/omarchy-android/mesa"
        val env =
            mutableMapOf(
                "HOME" to "/home/omarchy",
                "USER" to "omarchy",
                "LOGNAME" to "omarchy",
                "XDG_RUNTIME_DIR" to "/run/user/1000",
                "WAYLAND_DISPLAY" to "wayland-omarchy",
                "XDG_SESSION_TYPE" to "wayland",
                "XDG_CURRENT_DESKTOP" to "Hyprland",
                "XDG_SESSION_DESKTOP" to "Hyprland",
                "LANG" to "C.UTF-8",
                "LC_ALL" to "C.UTF-8",
                "TERM" to "xterm-256color",
                "OMARCHY_PATH" to "/usr/share/omarchy",
                "OMARCHY_PROOT" to "1",
                "OMARCHY_GPU_MODE" to "kgsl",
                "OMARCHY_SCALE" to scale.toString(),
                "OMARCHY_REFRESH_MHZ" to refreshMhz.toString(),
                "PATH" to "$hyprlandStage/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "XDG_DATA_DIRS" to "$hyprlandStage/share:/usr/local/share:/usr/share",
                // The /opt copies are the Android-patched builds (KGSL + the
                // Android DMA-BUF allocator); the /usr copies are stock Arch
                // builds without them, so they must never win.
                // The host binary itself (libproot.so) needs its own bionic
                // dependencies from nativeLibraryDir; the /opt entries are for
                // the guest's compositor and are ignored by host binaries.
                "LD_LIBRARY_PATH" to "$nativeLibDir:$mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib",
                "LIBGL_DRIVERS_PATH" to "$mesa/root/usr/lib/dri",
                "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
                "VK_ICD_FILENAMES" to "$mesa/freedreno_icd.json",
                "FD_KGSL_ENABLE_DMABUF" to "1",
                "AQ_NO_KMS_REQUIREMENT" to "1",
                "AQ_NO_MODIFIERS" to "1",
                "AQ_WAYLAND_REFRESH_MHZ" to refreshMhz.toString(),
                "HYPRLAND_ANDROID_DMABUF" to "1",
                "HYPRLAND_SHM_DAMAGE" to "1",
                "HYPRLAND_EGL_NO_MODIFIERS" to "1",
                "HYPRLAND_EGL_SURFACELESS" to "1",
                "HYPRLAND_NO_SD_NOTIFY" to "1",
                "HYPRLAND_NO_SD_VARS" to "1",
                "HYPRLAND_NO_RT" to "1",
                "QT_QPA_PLATFORM" to "wayland",
                "GDK_BACKEND" to "wayland",
                "SDL_VIDEODRIVER" to "wayland",
                "XWAYLAND_NO_GLAMOR" to "1",
                "NO_AT_BRIDGE" to "1",
                "QT_ACCESSIBILITY" to "0",
                "GTK_A11Y" to "none",
                // proot needs a writable temp dir; the guest sees the prefix tmp.
                "PROOT_TMP_DIR" to "$prefixDir/tmp",
                "TMPDIR" to "/tmp",
                // proot's ELF loader — must be executable (in nativeLibDir)
                "PROOT_LOADER" to "$nativeLibDir/libproot-loader.so",
            )
        // A hint only means something when both dimensions are known; the guest
        // falls back to the X root otherwise (contract section 1). In Lua 0 is
        // truthy, so exporting a zero here would force a 0x0 mode.
        if (width > 0 && height > 0) {
            env["OMARCHY_OUTPUT_WIDTH"] = width.toString()
            env["OMARCHY_OUTPUT_HEIGHT"] = height.toString()
        }
        if (bindSdcard) {
            env["OMARCHY_SHARE"] = "storage"
        }
        return env
    }
}
