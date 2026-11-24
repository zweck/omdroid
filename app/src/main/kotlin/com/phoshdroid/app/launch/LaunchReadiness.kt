package com.phoshdroid.app.launch

/**
 * When the host may hand the screen over to the guest, per
 * `docs/omarchy/launch-contract.md` section 2.
 */
sealed interface ReadyDecision {
    data class Done(
        val reason: ReadyReason,
    ) : ReadyDecision {
        /** True when the desktop was drawn; false for a timeout or a dead session. */
        val ready: Boolean get() = reason == ReadyReason.MARKER || reason == ReadyReason.FALLBACK
    }

    /** Not ready yet; keep polling. */
    data object Waiting : ReadyDecision
}

enum class ReadyReason { MARKER, FALLBACK, TIMEOUT, SESSION_DIED }

/**
 * Pure form of the contract's wait rule:
 *
 * - the ready marker wins: the desktop was fully drawn once in this session;
 * - a guest image without the marker writer falls back to a `hypr/<instance>`
 *   directory that has existed for [FALLBACK_INSTANCE_AGE_MS];
 * - the session process dying gives up at once;
 * - otherwise [HARD_TIMEOUT_MS] is the cap.
 */
object LaunchReadiness {
    const val FALLBACK_INSTANCE_AGE_MS = 25_000L
    const val HARD_TIMEOUT_MS = 120_000L

    fun decide(
        markerPresent: Boolean,
        instanceDirAgeMs: Long?,
        elapsedMs: Long,
        sessionAlive: Boolean,
    ): ReadyDecision {
        if (!sessionAlive) return ReadyDecision.Done(ReadyReason.SESSION_DIED)
        if (markerPresent) return ReadyDecision.Done(ReadyReason.MARKER)
        if (instanceDirAgeMs != null && instanceDirAgeMs >= FALLBACK_INSTANCE_AGE_MS) {
            return ReadyDecision.Done(ReadyReason.FALLBACK)
        }
        if (elapsedMs >= HARD_TIMEOUT_MS) return ReadyDecision.Done(ReadyReason.TIMEOUT)
        return ReadyDecision.Waiting
    }
}
