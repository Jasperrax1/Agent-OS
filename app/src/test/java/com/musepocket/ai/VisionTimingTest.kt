package com.musepocket.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionTimingTest {
    @Test
    fun fastResultWaitsForTheRestOfTheTwoSeconds() {
        assertEquals(2_200L, generationHoldMs(0L))
        assertEquals(1_450L, generationHoldMs(750L))
    }

    @Test
    fun slowResultHasNoAdditionalWait() {
        assertEquals(0L, generationHoldMs(2_200L))
        assertEquals(0L, generationHoldMs(2_700L))
    }
}
