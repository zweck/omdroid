package com.phoshdroid.app.launch

/** What a launcher entry point does with a launch request. */
enum class LaunchAction {
    /** A bootstrap is already running in this process: show the splash and wait. */
    WAIT,

    /** The desktop is up: bring the display activity forward. */
    FORWARD,

    /** Nothing is up: start a session. */
    START,
}

/**
 * The one launch rule, shared by `onCreate`, `onNewIntent` and `onResume`.
 *
 * Round 2 tied the hand-over to `bootstrapCompleted`, a per-process flag that
 * nothing clears. After the notification's Stop action, a guest exit or a
 * guest crash the flag was still set, so the next icon tap forwarded the user
 * to an X display nothing was drawing on. The rule now also asks whether the
 * session is alive: a completed bootstrap with a dead session is a fresh start,
 * not a hand-over.
 */
object LaunchDecision {
    fun decide(
        bootstrapInProgress: Boolean,
        bootstrapCompleted: Boolean,
        sessionRunning: Boolean,
    ): LaunchAction =
        when {
            bootstrapInProgress -> LaunchAction.WAIT
            bootstrapCompleted && sessionRunning -> LaunchAction.FORWARD
            // Everything else — a fresh process, a stopped session, a session
            // that exited or crashed, an earlier bootstrap that failed — starts
            // a session. A failed bootstrap never sets bootstrapCompleted.
            else -> LaunchAction.START
        }
}
