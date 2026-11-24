package com.phoshdroid.app.launch

/**
 * Resolves Omarchy's integer UI scale (contract section 4).
 *
 * The user may store `auto`, `1` or `2`; a stored `1`/`2` keeps meaning itself.
 * `auto` picks 2 when the display is at least 240 dpi and its shorter side is
 * at least 720 px (a logical width of 360 at scale 2), else 1.
 */
object UiScale {
    const val AUTO = "auto"

    fun resolve(
        stored: String?,
        densityDpi: Int,
        shorterWindowEdgePx: Int,
    ): Int =
        when (stored?.trim()) {
            "1" -> 1
            "2" -> 2
            else -> auto(densityDpi, shorterWindowEdgePx)
        }

    fun auto(
        densityDpi: Int,
        shorterWindowEdgePx: Int,
    ): Int = if (densityDpi >= 240 && shorterWindowEdgePx / 2 >= 360) 2 else 1
}
