package com.phoshdroid.app.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface

/** The embedded display already persists and reapplies these forceOrientation values. */
object AndroidRotationLock {
    fun locked(preference: String?): Boolean? = when (preference) {
        "auto" -> false
        "portrait", "landscape", "reverse portrait", "reverse landscape" -> true
        else -> null
    }

    /** Match Android's natural-display and reverseDefaultRotation conventions. */
    fun currentPreference(orientation: Int, rotation: Int, reverseDefaultRotation: Boolean): String? {
        if (rotation !in Surface.ROTATION_0..Surface.ROTATION_270) return null
        val landscape = when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> return null
        }
        val quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val naturalLandscape = landscape != quarterTurn
        val normalRotation = if (landscape) {
            if (naturalLandscape) Surface.ROTATION_0
            else if (reverseDefaultRotation) Surface.ROTATION_270 else Surface.ROTATION_90
        } else {
            if (!naturalLandscape) Surface.ROTATION_0
            else if (reverseDefaultRotation) Surface.ROTATION_90 else Surface.ROTATION_270
        }
        val name = if (landscape) "landscape" else "portrait"
        return if (rotation == normalRotation) name else "reverse $name"
    }

    fun requestedOrientation(preference: String): Int = when (preference) {
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        "reverse portrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        "reverse landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
