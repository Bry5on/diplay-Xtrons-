package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryBluetoothSnapshotTest {
    @Test fun aConnectedHandsFreeProfileIsReportedWithoutDeviceNames() {
        val line = FactoryBluetoothSnapshot.summary(
            listOf(
                FactoryBluetoothSnapshot.Probe("HFP", "disconnected", 0),
                FactoryBluetoothSnapshot.Probe("A2DP", "connected", 1),
                FactoryBluetoothSnapshot.Probe("A2DP_SINK", "unavailable", null),
                FactoryBluetoothSnapshot.Probe("HFP_CLIENT", "connected", 1),
            ),
            reason = null,
        )
        assertTrue(line, line.startsWith("Bluetooth audio: state=active "))
        assertTrue(line, line.contains("HFP_CLIENT=connected:1"))
        assertTrue(line, line.contains("A2DP_SINK=unavailable"))
        assertFalse(line.lowercase().contains("name="))
        assertFalse(line.contains("00:"))
    }

    @Test fun noAdapterIsUnknownRatherThanDisconnected() {
        assertEquals(
            "Bluetooth audio: state=unknown reason=no-adapter",
            FactoryBluetoothSnapshot.summary(emptyList(), "no-adapter"),
        )
    }

    @Test fun everyProfileDisconnectedIsNone() {
        val line = FactoryBluetoothSnapshot.summary(
            listOf(
                FactoryBluetoothSnapshot.Probe("HFP", "disconnected", 0),
                FactoryBluetoothSnapshot.Probe("A2DP", "disconnected", 0),
                FactoryBluetoothSnapshot.Probe("A2DP_SINK", "disconnected", 0),
                FactoryBluetoothSnapshot.Probe("HFP_CLIENT", "disconnected", 0),
            ),
            reason = null,
        )
        assertTrue(line, line.startsWith("Bluetooth audio: state=none "))
    }

    @Test fun anUnreadableProfileIsNotTreatedAsDisconnected() {
        val line = FactoryBluetoothSnapshot.summary(
            listOf(
                FactoryBluetoothSnapshot.Probe("HFP", "security", null),
                FactoryBluetoothSnapshot.Probe("HFP_CLIENT", "unavailable", null),
            ),
            reason = null,
        )
        assertTrue(line, line.startsWith("Bluetooth audio: state=unknown "))
    }
}
