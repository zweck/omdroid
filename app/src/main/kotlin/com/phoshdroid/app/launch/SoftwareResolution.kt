package com.phoshdroid.app.launch

/**
 * What one session's display resolution is: the Termux:X11 preferences, the Omarchy scale the
 * guest runs at, and whether the predicted X root halves.
 *
 * [halfResolution] is the one fact every consumer keys off, so the preferences, the X root
 * prediction, the guest's `OMARCHY_SCALE` and the live ui-scale file cannot disagree.
 */
data class SessionDisplay(
    /** Termux:X11 `displayResolutionMode`: `native`, or `scaled` on the half path. */
    val resolutionMode: String,
    /** Termux:X11 `displayScale` (percent): `100` full, `200` renders at half each axis. */
    val displayScale: String,
    /** The Omarchy integer scale the guest runs at (`OMARCHY_SCALE`, the ui-scale file). */
    val guestScale: Int,
    /** True when the guest draws at half each axis and Xlorie upscales to the surface. */
    val halfResolution: Boolean,
)

/**
 * The software-GPU half-resolution rule.
 *
 * `software` GPU mode rasterises on the CPU (llvmpipe), so at Omarchy scale 2 the tablet draws
 * 6.3 Mpx per frame at 455-500 % CPU and 865-944 MB PSS (docs/omarchy/r11-memory.md). Halving
 * each axis (1600x984 at scale 1, upscaled by Xlorie to the native panel) measured the same
 * logical desktop at 147 % typing CPU and −399 MB session PSS.
 *
 * Termux:X11 already has the machinery: `displayResolutionMode=scaled` with `displayScale=200`
 * makes LorieView render at `width * 100 / 200` while the `SurfaceView` keeps its layout size,
 * so GLES upscales the smaller texture for free, and the input handler maps touch between the
 * surface and the smaller client (LorieView.java, TouchInputHandler.java).
 *
 * The rule is deliberately narrow: only CPU rasterisation at scale 2 with no external display.
 * A docked session stays native at scale 1 (the monitor is a real GPU-sized target and the
 * brief's dock path is untouched), and a scale-1 session has nothing to halve. A forced
 * `software` setting on an Adreno device counts, as does `auto` that resolved to software.
 */
object SoftwareResolution {
    /** Termux:X11 `displayResolutionMode` for a full-size session. */
    const val MODE_NATIVE = "native"

    /** Termux:X11 `displayResolutionMode` that renders at `width * 100 / displayScale`. */
    const val MODE_SCALED = "scaled"

    /** Termux:X11 `displayScale` (percent) that halves each axis. */
    const val PERCENT_HALF = "200"

    /** Termux:X11 `displayScale` when the root stays native. */
    const val PERCENT_FULL = "100"

    /** The logical scale the half path runs at: the same desktop, drawn at half size. */
    const val HALF_GUEST_SCALE = 1

    /** True only when the guest rasterises on the CPU, at scale 2, with no external display. */
    fun shouldScaleHalf(
        gpu: GpuMode,
        scale: Int,
        docked: Boolean,
    ): Boolean = gpu == GpuMode.SOFTWARE && scale == 2 && !docked

    /** The whole display decision for a session, from the values the launcher already resolved. */
    fun forSession(
        gpu: GpuMode,
        scale: Int,
        docked: Boolean,
    ): SessionDisplay {
        val half = shouldScaleHalf(gpu, scale, docked)
        return SessionDisplay(
            resolutionMode = if (half) MODE_SCALED else MODE_NATIVE,
            displayScale = if (half) PERCENT_HALF else PERCENT_FULL,
            guestScale = if (half) HALF_GUEST_SCALE else scale,
            halfResolution = half,
        )
    }

    /**
     * The X root a half-sized session predicts: each axis halved (integer division, so an odd
     * side loses the half pixel). The guest still rounds the result down to even for cage's
     * window (contract section 1); this only mirrors what LorieView's `width * 100 / 200` does
     * with the same surface size.
     */
    fun xRoot(geometry: XGeometry): XGeometry =
        XGeometry(geometry.width / 2, geometry.height / 2, geometry.bottomGap)
}
