package com.phoshdroid.app.launch

/** The X root size predicted for a hand-over, and the gap left below it. */
data class XGeometry(
    val width: Int,
    val height: Int,
    val bottomGap: Int,
)

/**
 * Predicts the X root size from the Android window it will be shown in
 * (contract section 1), so no resize happens at hand-over.
 *
 * - X width = window width minus the left and right display-cutout insets.
 * - X height = window height minus the top and bottom insets minus the
 *   Termux:X11 extra-key bar while it is shown. There is no top gap:
 *   Termux:X11 runs with `hideCutout=false`, so Android itself keeps the
 *   window clear of a cutout in every rotation.
 * - The bar is `round(37.5 * density * rows)` px tall, the same formula
 *   Termux:X11's `MainActivity.setTerminalToolbarView()` uses.
 * - If the result is not positive, fall back to the window size with no gap.
 *
 * Nothing is rounded to even here; the guest does that for cage's window.
 */
object DisplayGeometry {
    /** Termux:X11's default extra-key config has two rows. */
    const val DEFAULT_EXTRA_KEY_ROWS = 2

    fun compute(
        windowWidth: Int,
        windowHeight: Int,
        density: Float,
        extraKeyRows: Int,
        extraKeysShown: Boolean,
        insetLeft: Int = 0,
        insetTop: Int = 0,
        insetRight: Int = 0,
        insetBottom: Int = 0,
    ): XGeometry {
        val bottomGap =
            if (extraKeysShown && extraKeyRows > 0) {
                Math.round(37.5f * density * extraKeyRows)
            } else {
                0
            }
        val width = windowWidth - insetLeft - insetRight
        val height = windowHeight - insetTop - insetBottom - bottomGap
        if (windowWidth <= 0 || windowHeight <= 0 || width <= 0 || height <= 0) {
            return XGeometry(windowWidth, windowHeight, 0)
        }
        return XGeometry(width, height, bottomGap)
    }
}
