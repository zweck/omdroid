package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UiScaleTest {

    @Test
    fun `tablet resolves to 2`() {
        // Fact 10: 3200x2136 window at 352 dpi.
        assertEquals(2, UiScale.resolve("auto", densityDpi = 352, shorterWindowEdgePx = 2136))
    }

    @Test
    fun `1080x2400 phone at 420 resolves to 2`() {
        assertEquals(2, UiScale.resolve(null, densityDpi = 420, shorterWindowEdgePx = 1080))
    }

    @Test
    fun `1920x1200 at 240 resolves to 2`() {
        assertEquals(2, UiScale.auto(densityDpi = 240, shorterWindowEdgePx = 1200))
    }

    @Test
    fun `1280x800 at 160 resolves to 1`() {
        assertEquals(1, UiScale.auto(densityDpi = 160, shorterWindowEdgePx = 800))
    }

    @Test
    fun `720x1600 at 320 resolves to 2`() {
        assertEquals(2, UiScale.auto(densityDpi = 320, shorterWindowEdgePx = 720))
    }

    @Test
    fun `600x1024 at 240 resolves to 1`() {
        assertEquals(1, UiScale.auto(densityDpi = 240, shorterWindowEdgePx = 600))
    }

    @Test
    fun `a stored choice keeps meaning itself`() {
        assertEquals(1, UiScale.resolve("1", densityDpi = 352, shorterWindowEdgePx = 2136))
        assertEquals(2, UiScale.resolve("2", densityDpi = 160, shorterWindowEdgePx = 800))
    }

    @Test
    fun `an unknown stored value falls back to auto`() {
        assertEquals(2, UiScale.resolve("garbage", densityDpi = 352, shorterWindowEdgePx = 2136))
    }
}
