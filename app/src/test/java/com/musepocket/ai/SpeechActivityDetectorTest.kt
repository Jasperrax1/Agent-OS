package com.musepocket.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechActivityDetectorTest {
    @Test
    fun thirtySecondsOfQuietRemainIdleAndSpeechCanStartAfterwards() {
        val detector = SpeechActivityDetector()
        repeat(1_500) { detector.observe(80.0, 320) }
        assertFalse(detector.started)
        assertFalse(detector.ended)

        repeat(6) { detector.observe(900.0, 320) }
        assertFalse(detector.started)
        detector.observe(900.0, 320)
        assertTrue(detector.started)
    }

    @Test
    fun separatedHandlingClicksDoNotAddUpToAnUtterance() {
        val detector = SpeechActivityDetector()
        repeat(10) {
            repeat(2) { detector.observe(2_000.0, 320) }
            repeat(8) { detector.observe(100.0, 320) }
        }
        assertFalse(detector.started)
    }

    @Test
    fun briefPauseDoesNotStopSpeechAndFullTrailingSilenceDoes() {
        val detector = SpeechActivityDetector()
        repeat(7) { detector.observe(1_000.0, 320) }
        repeat(30) { detector.observe(75.0, 320) }
        assertFalse(detector.ended)
        detector.observe(1_000.0, 320)
        repeat(42) { detector.observe(75.0, 320) }
        assertFalse(detector.ended)
        detector.observe(75.0, 320)
        assertTrue(detector.ended)
    }
}
