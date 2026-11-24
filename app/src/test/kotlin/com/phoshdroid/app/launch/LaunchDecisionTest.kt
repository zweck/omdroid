package com.phoshdroid.app.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LaunchDecisionTest {

    @Test
    fun `waits while a bootstrap is in progress`() {
        assertEquals(
            LaunchAction.WAIT,
            LaunchDecision.decide(bootstrapInProgress = true, bootstrapCompleted = false, sessionRunning = false),
        )
    }

    @Test
    fun `in progress wins over a completed bootstrap and a running session`() {
        assertEquals(
            LaunchAction.WAIT,
            LaunchDecision.decide(bootstrapInProgress = true, bootstrapCompleted = true, sessionRunning = true),
        )
    }

    @Test
    fun `forwards when the bootstrap completed and the session is running`() {
        assertEquals(
            LaunchAction.FORWARD,
            LaunchDecision.decide(bootstrapInProgress = false, bootstrapCompleted = true, sessionRunning = true),
        )
    }

    @Test
    fun `starts after a completed bootstrap whose session stopped`() {
        assertEquals(
            LaunchAction.START,
            LaunchDecision.decide(bootstrapInProgress = false, bootstrapCompleted = true, sessionRunning = false),
        )
    }

    @Test
    fun `starts in a fresh process`() {
        assertEquals(
            LaunchAction.START,
            LaunchDecision.decide(bootstrapInProgress = false, bootstrapCompleted = false, sessionRunning = false),
        )
    }

    @Test
    fun `starts when a session is flagged running but this process never bootstrapped`() {
        assertEquals(
            LaunchAction.START,
            LaunchDecision.decide(bootstrapInProgress = false, bootstrapCompleted = false, sessionRunning = true),
        )
    }

    @Test
    fun `starts after a failed bootstrap`() {
        // bootstrapCompleted stays false when bootstrap() returns false, so the
        // error screen's Start again button and a later icon tap both START.
        assertEquals(
            LaunchAction.START,
            LaunchDecision.decide(bootstrapInProgress = false, bootstrapCompleted = false, sessionRunning = false),
        )
    }
}
