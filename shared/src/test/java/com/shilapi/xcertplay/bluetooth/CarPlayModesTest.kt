package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayModesTest {
    @Test fun aPhoneCallEndIsReportedAfterTheCallWasActive() {
        val tracker = CarPlayModeTracker()
        assertEquals("started", tracker.apply(CarPlayModes.parse(modes(phone = true))))
        assertEquals("unchanged", tracker.apply(CarPlayModes.parse(modes(speech = CarPlayModes.SPEAKING))))
        assertEquals("ended", tracker.apply(CarPlayModes.parse(modes(phone = false, speech = -1))))
        assertEquals(false, tracker.busy)
    }

    @Test fun siriRecognizingThenNoneEndsTheSession() {
        val tracker = CarPlayModeTracker()
        assertEquals("started", tracker.apply(CarPlayModes.parse(modes(speech = CarPlayModes.RECOGNIZING))))
        assertEquals("ended", tracker.apply(CarPlayModes.parse(modes(speech = -1))))
    }

    @Test fun anOmittedFieldDoesNotClearTheOther() {
        val tracker = CarPlayModeTracker()
        tracker.apply(CarPlayModes.parse(modes(phone = true, speech = -1)))
        assertEquals("unchanged", tracker.apply(CarPlayModes.parse(modes(speech = -1))))
        assertEquals(true, tracker.busy)
    }

    @Test fun aReturnCycleWaitsUntilTheCallIsOverAndNotTooSoon() {
        assertEquals("call-active", CarPlayModes.skipReturn(busy = true, millisSinceLast = 60_000, cycleInProgress = false))
        assertEquals("in-progress", CarPlayModes.skipReturn(busy = false, millisSinceLast = 60_000, cycleInProgress = true))
        assertEquals("recent", CarPlayModes.skipReturn(busy = false, millisSinceLast = 10_000, cycleInProgress = false))
        assertNull(CarPlayModes.skipReturn(busy = false, millisSinceLast = 30_000, cycleInProgress = false))
        assertEquals(1_500L, CarPlayModes.RETURN_DEBOUNCE_MILLIS)
    }

    private fun modes(phone: Boolean? = null, speech: Int? = null): Map<String, Any?> {
        val states = mutableListOf<Map<String, Any?>>()
        if (phone != null) states += mapOf("appStateID" to 2, "state" to phone)
        if (speech != null) states += mapOf("appStateID" to 1L, "speechMode" to speech)
        return mapOf("appStates" to states)
    }
}
