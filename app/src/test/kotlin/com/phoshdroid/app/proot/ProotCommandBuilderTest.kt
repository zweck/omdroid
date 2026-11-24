package com.phoshdroid.app.proot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProotCommandBuilderTest {

    private fun builder() = ProotCommandBuilder(nativeLibDir = "/native", prefixDir = "/prefix", distroName = "omarchy")

    @Test
    fun `exports the output hint when both dimensions are positive`() {
        val env = builder().buildEnvironment(width = 3200, height = 1971, scale = 2, refreshMhz = 120_000)

        assertEquals("3200", env["OMARCHY_OUTPUT_WIDTH"])
        assertEquals("1971", env["OMARCHY_OUTPUT_HEIGHT"])
    }

    @Test
    fun `omits the output hint when both dimensions are zero`() {
        val env = builder().buildEnvironment(width = 0, height = 0, scale = 2, refreshMhz = 120_000)

        assertFalse(env.containsKey("OMARCHY_OUTPUT_WIDTH"))
        assertFalse(env.containsKey("OMARCHY_OUTPUT_HEIGHT"))
    }

    @Test
    fun `omits the output hint when only one dimension is positive`() {
        val env = builder().buildEnvironment(width = 0, height = 1971, scale = 2, refreshMhz = 120_000)

        assertFalse(env.containsKey("OMARCHY_OUTPUT_WIDTH"))
        assertFalse(env.containsKey("OMARCHY_OUTPUT_HEIGHT"))
    }

    @Test
    fun `omits the output hint when a dimension is negative`() {
        val env = builder().buildEnvironment(width = 3200, height = -1, scale = 2, refreshMhz = 120_000)

        assertFalse(env.containsKey("OMARCHY_OUTPUT_WIDTH"))
        assertFalse(env.containsKey("OMARCHY_OUTPUT_HEIGHT"))
    }
}
