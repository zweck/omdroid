package com.phoshdroid.app.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DisplayLaunchTest {
    @Test fun `settings intents carry the selected display and fall back on a vanished display`() {
        val context = mockk<Context>(relaxed = true)
        val intent = mockk<Intent>()
        val options = mockk<ActivityOptions>()
        val bundle = mockk<Bundle>()
        mockkStatic(ActivityOptions::class)
        try {
            every { ActivityOptions.makeBasic() } returns options
            every { options.setLaunchDisplayId(7) } returns options
            every { options.toBundle() } returns bundle
            assertTrue(DisplayLaunch.start(context, intent, 7))
            verify(exactly = 1) { context.startActivity(intent, bundle) }
            verify(exactly = 0) { context.startActivity(intent) }
            every { context.startActivity(intent, bundle) } throws IllegalArgumentException("display gone")
            mockkStatic(android.util.Log::class)
            try {
                every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } returns 0
                assertFalse(DisplayLaunch.start(context, intent, 7))
                verify(exactly = 1) { context.startActivity(intent) }
            } finally { unmockkStatic(android.util.Log::class) }
        } finally { unmockkStatic(ActivityOptions::class) }
    }
}
