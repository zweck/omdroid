package com.phoshdroid.app.proot

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionExitTest {

    @Test
    fun `retries a non-zero exit inside the cold-start window`() {
        assertTrue(SessionExit.shouldRetry(exitCode = 1, ranForMs = 5_000))
    }

    @Test
    fun `does not retry an exit 0`() {
        assertFalse(SessionExit.shouldRetry(exitCode = 0, ranForMs = 5_000))
    }

    @Test
    fun `does not retry once the session outlived the window`() {
        assertFalse(SessionExit.shouldRetry(exitCode = 1, ranForMs = SessionExit.RETRY_WINDOW_MS))
        assertFalse(SessionExit.shouldRetry(exitCode = 1, ranForMs = 3_600_000))
    }

    @Test
    fun `does not retry an exit 0 that outlived the window`() {
        assertFalse(SessionExit.shouldRetry(exitCode = 0, ranForMs = 3_600_000))
    }
}
