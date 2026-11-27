package com.phoshdroid.app.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AndroidRotationLockTest {
    private val portrait = Configuration.ORIENTATION_PORTRAIT
    private val landscape = Configuration.ORIENTATION_LANDSCAPE

    @Test fun `portrait natural displays keep all four orientations`() {
        assertEquals("portrait", AndroidRotationLock.currentPreference(portrait, 0, false))
        assertEquals("landscape", AndroidRotationLock.currentPreference(landscape, 1, false))
        assertEquals("reverse portrait", AndroidRotationLock.currentPreference(portrait, 2, false))
        assertEquals("reverse landscape", AndroidRotationLock.currentPreference(landscape, 3, false))
    }

    @Test fun `landscape natural tablets keep all four orientations`() {
        assertEquals("landscape", AndroidRotationLock.currentPreference(landscape, 0, false))
        assertEquals("reverse portrait", AndroidRotationLock.currentPreference(portrait, 1, false))
        assertEquals("reverse landscape", AndroidRotationLock.currentPreference(landscape, 2, false))
        assertEquals("portrait", AndroidRotationLock.currentPreference(portrait, 3, false))
    }

    @Test fun `OEM reverse default rotation is honored`() {
        assertEquals("reverse landscape", AndroidRotationLock.currentPreference(landscape, 1, true))
        assertEquals("landscape", AndroidRotationLock.currentPreference(landscape, 3, true))
        assertEquals("portrait", AndroidRotationLock.currentPreference(portrait, 1, true))
        assertEquals("reverse portrait", AndroidRotationLock.currentPreference(portrait, 3, true))
    }

    @Test fun `unknown geometry cannot silently lock to a different orientation`() {
        assertNull(AndroidRotationLock.currentPreference(Configuration.ORIENTATION_UNDEFINED, 0, false))
        assertNull(AndroidRotationLock.currentPreference(portrait, -1, false))
        assertNull(AndroidRotationLock.currentPreference(portrait, 4, false))
    }

    @Test fun `state follows the embedded display preference and unknown stays unknown`() {
        assertEquals(false, AndroidRotationLock.locked("auto"))
        for (value in listOf("portrait", "landscape", "reverse portrait", "reverse landscape")) {
            assertEquals(true, AndroidRotationLock.locked(value))
        }
        assertNull(AndroidRotationLock.locked(null))
        assertNull(AndroidRotationLock.locked("unexpected"))
    }

    @Test fun `unlock returns to system rotation and reverse locks use the correct activity request`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, AndroidRotationLock.requestedOrientation("auto"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, AndroidRotationLock.requestedOrientation("reverse portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, AndroidRotationLock.requestedOrientation("reverse landscape"))
    }
}
