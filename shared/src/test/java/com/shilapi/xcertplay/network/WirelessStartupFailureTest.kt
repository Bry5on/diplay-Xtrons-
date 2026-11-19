package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessStartupFailureTest {
    @Test fun aRadioCycleOrAReachedSessionDoesNotEndRecovery() {
        assertTrue(WirelessStartupFailure.BLUETOOTH_UNUSABLE.suppressedByRadioCycle(sessionReached = false, radioCycleSuspended = true))
        assertTrue(WirelessStartupFailure.BLUETOOTH_UNUSABLE.suppressedByRadioCycle(sessionReached = true, radioCycleSuspended = false))
        assertFalse(WirelessStartupFailure.BLUETOOTH_UNUSABLE.suppressedByRadioCycle(sessionReached = false, radioCycleSuspended = false))
        assertFalse(WirelessStartupFailure.HOTSPOT_NOT_READY.suppressedByRadioCycle(sessionReached = true, radioCycleSuspended = true))
    }
}
