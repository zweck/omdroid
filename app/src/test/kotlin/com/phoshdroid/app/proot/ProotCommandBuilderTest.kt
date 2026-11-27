package com.phoshdroid.app.proot

import com.phoshdroid.app.launch.GpuMode
import com.phoshdroid.app.launch.GpuModeRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProotCommandBuilderTest {

    private fun builder() = ProotCommandBuilder(nativeLibDir = "/native", prefixDir = "/prefix", distroName = "omarchy", androidSessionId = "12345678-1234-1234-1234-123456789abc")

    @Test
    fun `exports the output hint when both dimensions are positive`() {
        val env = builder().buildEnvironment(width = 3200, height = 1971, scale = 2, refreshMhz = 120_000)

        assertEquals("3200", env["OMARCHY_OUTPUT_WIDTH"])
        assertEquals("1971", env["OMARCHY_OUTPUT_HEIGHT"])
    }

    @Test
    fun `omits the output hint when both dimensions are zero`() {
        val env = builder().buildEnvironment(width = 0, height = 0, scale = 2, refreshMhz = 120_000)

        assertFalse(env.containsKey("OMARCHY_OUTPUT_WIDTH"))
        assertFalse(env.containsKey("OMARCHY_OUTPUT_HEIGHT"))
    }

    @Test
    fun `omits the output hint when only one dimension is positive`() {
        val env = builder().buildEnvironment(width = 0, height = 1971, scale = 2, refreshMhz = 120_000)

        assertFalse(env.containsKey("OMARCHY_OUTPUT_WIDTH"))
        assertFalse(env.containsKey("OMARCHY_OUTPUT_HEIGHT"))
    }

    @Test
    fun `omits the output hint when a dimension is negative`() {
        val env = builder().buildEnvironment(width = 3200, height = -1, scale = 2, refreshMhz = 120_000)

        assertFalse(env.containsKey("OMARCHY_OUTPUT_WIDTH"))
        assertFalse(env.containsKey("OMARCHY_OUTPUT_HEIGHT"))
    }
    // The environment as it was before the software mode existed (0.0.17), spelled out so that no
    // KGSL entry can change by accident. The two OMDROID_GPU keys are new and asserted separately.
    private val kgslEnvironment =
        mapOf(
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
            "OMDROID_ANDROID_SESSION" to "12345678-1234-1234-1234-123456789abc",
            "OMARCHY_GPU_MODE" to "kgsl",
            "OMARCHY_SCALE" to "2",
            "OMARCHY_REFRESH_MHZ" to "120000",
            "PATH" to "/opt/omarchy-android/hyprland/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "XDG_DATA_DIRS" to "/opt/omarchy-android/hyprland/share:/usr/local/share:/usr/share",
            "LD_LIBRARY_PATH" to "/native:/opt/omarchy-android/mesa/root/usr/lib:/opt/omarchy-android/aquamarine/lib",
            "LIBGL_DRIVERS_PATH" to "/opt/omarchy-android/mesa/root/usr/lib/dri",
            "MESA_LOADER_DRIVER_OVERRIDE" to "kgsl",
            "VK_ICD_FILENAMES" to "/opt/omarchy-android/mesa/freedreno_icd.json",
            "FD_KGSL_ENABLE_DMABUF" to "1",
            "AQ_NO_KMS_REQUIREMENT" to "1",
            "AQ_NO_MODIFIERS" to "1",
            "AQ_WAYLAND_REFRESH_MHZ" to "120000",
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
            "PROOT_TMP_DIR" to "/prefix/tmp",
            "TMPDIR" to "/tmp",
            "PROOT_LOADER" to "/native/libproot-loader.so",
            "OMARCHY_OUTPUT_WIDTH" to "3200",
            "OMARCHY_OUTPUT_HEIGHT" to "1971",
        )

    private fun env(
        gpu: GpuMode,
        source: String = GpuModeRule.SOURCE_AUTO,
    ) = builder().buildEnvironment(
        width = 3200,
        height = 1971,
        scale = 2,
        refreshMhz = 120_000,
        gpu = gpu,
        gpuSource = source,
    )

    @Test
    fun `the KGSL environment is exactly the one 0_0_17 passed plus the OMDROID_GPU pair`() {
        val env = env(GpuMode.KGSL)

        assertEquals(kgslEnvironment + mapOf("OMDROID_GPU" to "kgsl", "OMDROID_GPU_SOURCE" to "auto"), env)
    }

    @Test
    fun `the software environment keeps the nested Wayland path and swaps the GPU variables`() {
        val env = env(GpuMode.SOFTWARE)

        assertEquals("software", env["OMDROID_GPU"])
        assertEquals("software", env["OMARCHY_GPU_MODE"])
        assertEquals("swrast", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("1", env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("llvmpipe", env["GALLIUM_DRIVER"])
        // The three Adreno-only variables are gone.
        assertFalse(env.containsKey("VK_ICD_FILENAMES"))
        assertFalse(env.containsKey("FD_KGSL_ENABLE_DMABUF"))
        assertFalse(env.containsKey("HYPRLAND_ANDROID_DMABUF"))
        // The patched Mesa still wins, and the buffer path does not depend on the GPU.
        assertEquals(kgslEnvironment["LD_LIBRARY_PATH"], env["LD_LIBRARY_PATH"])
        assertEquals(kgslEnvironment["LIBGL_DRIVERS_PATH"], env["LIBGL_DRIVERS_PATH"])
        for (key in listOf("AQ_NO_KMS_REQUIREMENT", "AQ_NO_MODIFIERS", "HYPRLAND_SHM_DAMAGE", "HYPRLAND_EGL_SURFACELESS")) {
            assertEquals("1", env[key], key)
        }
    }

    @Test
    fun `the two modes differ only in the GPU variables`() {
        val kgsl = env(GpuMode.KGSL)
        val software = env(GpuMode.SOFTWARE)
        val gpuKeys =
            setOf(
                "OMDROID_GPU",
                "OMARCHY_GPU_MODE",
                "MESA_LOADER_DRIVER_OVERRIDE",
                "VK_ICD_FILENAMES",
                "FD_KGSL_ENABLE_DMABUF",
                "HYPRLAND_ANDROID_DMABUF",
                "LIBGL_ALWAYS_SOFTWARE",
                "GALLIUM_DRIVER",
            )

        assertEquals(kgsl - gpuKeys, software - gpuKeys)
    }

    @Test
    fun `the source reaches the guest unchanged`() {
        assertEquals("auto", env(GpuMode.KGSL, GpuModeRule.SOURCE_AUTO)["OMDROID_GPU_SOURCE"])
        assertEquals("setting", env(GpuMode.KGSL, GpuModeRule.SOURCE_SETTING)["OMDROID_GPU_SOURCE"])
    }

    @Test
    fun `a caller that says nothing about the GPU gets KGSL and no guest fallback`() {
        val env = builder().buildEnvironment(width = 0, height = 0, scale = 2, refreshMhz = 120_000)

        assertEquals("kgsl", env["OMDROID_GPU"])
        assertEquals("setting", env["OMDROID_GPU_SOURCE"])
    }
    @Test
    fun `only the current service session matches its guest environment`() {
        val first = ProotCommandBuilder("/native", "/prefix", "omarchy")
        val second = ProotCommandBuilder("/native", "/prefix", "omarchy")
        val env = first.buildEnvironment(0, 0, 2, 60_000)
        assertEquals(first.androidSessionId, env["OMDROID_ANDROID_SESSION"])
        org.junit.jupiter.api.Assertions.assertNotEquals(second.androidSessionId, first.androidSessionId)
    }
}
