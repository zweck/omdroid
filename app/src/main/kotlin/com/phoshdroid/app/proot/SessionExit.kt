package com.phoshdroid.app.proot

/**
 * What to do with the exit of a guest that ended on its own.
 *
 * Round 2 retried any non-zero exit and then left `active` set when the retry
 * succeeded, so a dead session still looked alive. The retry exists for one
 * case only: a guest that collapses during cold start (a stale prefix, Android
 * settling) usually comes up on the second attempt. A session that ran for an
 * hour and then exited — the user logged out of Hyprland, or the guest
 * crashed on real work — must not be silently restarted on state that is now
 * stale.
 */
object SessionExit {
    /** A session that dies inside this window is a cold-start collapse worth one retry. */
    const val RETRY_WINDOW_MS = 30_000L

    fun shouldRetry(
        exitCode: Int,
        ranForMs: Long,
    ): Boolean = exitCode != 0 && ranForMs < RETRY_WINDOW_MS
}
