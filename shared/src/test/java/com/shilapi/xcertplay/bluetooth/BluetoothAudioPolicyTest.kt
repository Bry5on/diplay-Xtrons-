package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioPolicyTest {
    @Test fun audioProfilesStayUpUntilTheRfcommBootstrapIsReleased() {
        assertFalse(BluetoothAudioPolicy.shouldIsolate(enabled = true, sessionUp = true, bootstrapReleased = false))
        assertFalse(BluetoothAudioPolicy.shouldIsolate(enabled = true, sessionUp = false, bootstrapReleased = true))
        assertFalse(BluetoothAudioPolicy.shouldIsolate(enabled = false, sessionUp = true, bootstrapReleased = true))
        assertTrue(BluetoothAudioPolicy.shouldIsolate(enabled = true, sessionUp = true, bootstrapReleased = true))
    }

    @Test fun theSelectedPhoneAndConnectedDevicesAreBothTargets() {
        assertEquals(
            setOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"),
            BluetoothAudioPolicy.targets("aa:bb:cc:dd:ee:ff", setOf("11:22:33:44:55:66")),
        )
    }

    @Test fun aJournalRoundTripsWithoutDroppingAPolicy() {
        val saved = listOf(
            BluetoothAudioPolicy.SavedPolicy("A2DP", "11:22:33:44:55:66", BluetoothAudioPolicy.AUTO_CONNECT),
            BluetoothAudioPolicy.SavedPolicy("HFP_CLIENT", "AA:BB:CC:DD:EE:FF", BluetoothAudioPolicy.ON),
        )
        assertEquals(saved, BluetoothAudioPolicy.decode(BluetoothAudioPolicy.encode(saved)))
        assertEquals(emptyList<BluetoothAudioPolicy.SavedPolicy>(), BluetoothAudioPolicy.decode(""))
        assertEquals(emptyList<BluetoothAudioPolicy.SavedPolicy>(), BluetoothAudioPolicy.decode("bad"))
    }
}
