package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LaunchReadinessTest {

    @Test
    fun `marker means ready with reason marker`() {
        val decision =
            LaunchReadiness.decide(
                markerPresent = true,
                instanceDirAgeMs = null,
                elapsedMs = 1_000,
                sessionAlive = true,
            )

        assertEquals(ReadyDecision.Done(ReadyReason.MARKER), decision)
    }

    @Test
    fun `instance dir older than 25 s means ready with reason fallback`() {
        val decision =
            LaunchReadiness.decide(
                markerPresent = false,
                instanceDirAgeMs = 25_000,
                elapsedMs = 26_000,
                sessionAlive = true,
            )

        assertEquals(ReadyDecision.Done(ReadyReason.FALLBACK), decision)
    }

    @Test
    fun `young instance dir keeps waiting`() {
        val decision =
            LaunchReadiness.decide(
                markerPresent = false,
                instanceDirAgeMs = 24_000,
                elapsedMs = 26_000,
                sessionAlive = true,
            )

        assertEquals(ReadyDecision.Waiting, decision)
    }

    @Test
    fun `hard cap gives up with reason timeout`() {
        val decision =
            LaunchReadiness.decide(
                markerPresent = false,
                instanceDirAgeMs = null,
                elapsedMs = 120_000,
                sessionAlive = true,
            )

        assertEquals(ReadyDecision.Done(ReadyReason.TIMEOUT), decision)
    }

    @Test
    fun `dead session gives up at once even before the cap`() {
        val decision =
            LaunchReadiness.decide(
                markerPresent = false,
                instanceDirAgeMs = null,
                elapsedMs = 2_000,
                sessionAlive = false,
            )

        assertEquals(ReadyDecision.Done(ReadyReason.SESSION_DIED), decision)
    }
}
