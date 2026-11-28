package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoftwareResolutionTest {

    @Test
    fun `software at scale 2 undocked halves`() {
        assertTrue(SoftwareResolution.shouldScaleHalf(GpuMode.SOFTWARE, scale = 2, docked = false))
    }

    @Test
    fun `kgsl at scale 2 undocked stays native`() {
        assertFalse(SoftwareResolution.shouldScaleHalf(GpuMode.KGSL, scale = 2, docked = false))
    }

    @Test
    fun `software at scale 1 undocked stays native`() {
        // A 1080p phone at scale 1 has no spare pixels to give back.
        assertFalse(SoftwareResolution.shouldScaleHalf(GpuMode.SOFTWARE, scale = 1, docked = false))
    }

    @Test
    fun `software at scale 2 docked stays native`() {
        // The monitor is a real target and the dock path stays on native at scale 1 (a3).
        assertFalse(SoftwareResolution.shouldScaleHalf(GpuMode.SOFTWARE, scale = 2, docked = true))
    }

    @Test
    fun `a forced software setting counts on an adreno device`() {
        // The user chose software on a phone whose KGSL node is fine: the guest really is on
        // llvmpipe, so the half path applies.
        val gpu = GpuModeRule.resolve(GpuModeRule.SOFTWARE, kgslUsable = true)
        assertEquals(GpuMode.SOFTWARE, gpu)
        assertTrue(SoftwareResolution.shouldScaleHalf(gpu, scale = 2, docked = false))
    }

    @Test
    fun `auto that resolves to software counts`() {
        // No usable Adreno node (Mali, Xclipse, PowerVR): auto lands on the CPU rasteriser.
        val gpu = GpuModeRule.resolve(GpuModeRule.AUTO, kgslUsable = false)
        assertEquals(GpuMode.SOFTWARE, gpu)
        assertTrue(SoftwareResolution.shouldScaleHalf(gpu, scale = 2, docked = false))
    }

    @Test
    fun `auto that resolves to kgsl stays native`() {
        val gpu = GpuModeRule.resolve(GpuModeRule.AUTO, kgslUsable = true)
        assertEquals(GpuMode.KGSL, gpu)
        assertFalse(SoftwareResolution.shouldScaleHalf(gpu, scale = 2, docked = false))
    }

    @Test
    fun `the half path writes scaled at 200 and runs the guest at 1`() {
        val session = SoftwareResolution.forSession(GpuMode.SOFTWARE, scale = 2, docked = false)
        assertTrue(session.halfResolution)
        assertEquals("scaled", session.resolutionMode)
        assertEquals("200", session.displayScale)
        assertEquals(1, session.guestScale)
    }

    @Test
    fun `every other case writes native at 100 and keeps the resolved scale`() {
        listOf(
            SoftwareResolution.forSession(GpuMode.KGSL, scale = 2, docked = false),
            SoftwareResolution.forSession(GpuMode.SOFTWARE, scale = 1, docked = false),
            SoftwareResolution.forSession(GpuMode.SOFTWARE, scale = 2, docked = true),
        ).forEach { session ->
            assertFalse(session.halfResolution)
            assertEquals("native", session.resolutionMode)
            assertEquals("100", session.displayScale)
        }
        assertEquals(
            2,
            SoftwareResolution.forSession(GpuMode.KGSL, scale = 2, docked = false).guestScale,
        )
        assertEquals(
            1,
            SoftwareResolution.forSession(GpuMode.SOFTWARE, scale = 1, docked = false).guestScale,
        )
        assertEquals(
            2,
            SoftwareResolution.forSession(GpuMode.SOFTWARE, scale = 2, docked = true).guestScale,
        )
    }

    @Test
    fun `the predicted root halves each axis`() {
        // The tablet's real root is 3200x1971; LorieView's width * 100 / 200 gives the same
        // 985, so the X root the guest starts with is what the scale asks for.
        val half = SoftwareResolution.xRoot(XGeometry(3200, 1971, 0))
        assertEquals(XGeometry(1600, 985, 0), half)
    }

    @Test
    fun `an odd side loses the half pixel, as the surface division does`() {
        assertEquals(XGeometry(1600, 985, 0), SoftwareResolution.xRoot(XGeometry(3201, 1971, 0)))
    }

    @Test
    fun `the half root keeps the bottom gap`() {
        assertEquals(XGeometry(1600, 1068, 7), SoftwareResolution.xRoot(XGeometry(3200, 2136, 7)))
    }
}
