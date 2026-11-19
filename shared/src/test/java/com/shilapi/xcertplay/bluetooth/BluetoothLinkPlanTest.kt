package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLinkPlanTest {
    @Test fun aConnectedOrUnreadableLinkTurnsTheRadioOff() {
        assertTrue(BluetoothLinkPlan.powerOffRadio(2))
        assertTrue(BluetoothLinkPlan.powerOffRadio(1))
        assertTrue(BluetoothLinkPlan.powerOffRadio(null))
        assertFalse(BluetoothLinkPlan.powerOffRadio(BluetoothLinkPlan.DISCONNECTED))
    }

    @Test fun aDisconnectedReadingIsIgnoredWhenProfileServicesDidNotAnswer() {
        assertTrue(
            BluetoothLinkPlan.powerOffRadio(
                BluetoothLinkPlan.DISCONNECTED,
                profilesUnreadable = true,
            ),
        )
    }

    @Test fun stateNamesStayFreeOfAddresses() {
        assertEquals("connected", BluetoothLinkPlan.stateName(2))
        assertEquals("disconnected", BluetoothLinkPlan.stateName(0))
        assertEquals("unreadable", BluetoothLinkPlan.stateName(null))
    }
}

class BluetoothLinkMethodsTest {
    @Test fun theAdapterServiceDisconnectIsUsedBeforeTheDeviceMethod() {
        val service = ServiceWithDisconnect()
        val device = DeviceWithDisconnect()
        assertEquals("disconnectAllEnabledProfiles=ok", BluetoothLinkMethods.disconnect(service, device))
        assertEquals(1, service.calls)
        assertEquals(0, device.calls)
    }

    @Test fun aMissingMethodFallsThroughToTheDevice() {
        val device = DeviceWithDisconnect()
        assertEquals("disconnect=ok", BluetoothLinkMethods.disconnect(service = Any(), device = device))
        assertEquals(1, device.calls)
    }

    @Test fun aPrivilegedRejectionDoesNotThrow() {
        assertEquals("security", BluetoothLinkMethods.disconnect(RejectingService(), device = "phone"))
    }

    @Test fun noDeviceIsReported() {
        assertEquals("no-device", BluetoothLinkMethods.disconnect(service = Any(), device = null))
    }

    private class ServiceWithDisconnect {
        var calls = 0
        fun disconnectAllEnabledProfiles(device: Any): Boolean {
            calls++
            return true
        }
    }

    private class DeviceWithDisconnect {
        var calls = 0
        fun disconnect(): Boolean {
            calls++
            return true
        }
    }

    private class RejectingService {
        fun disconnectAllEnabledProfiles(device: Any): Boolean = throw SecurityException("privileged")
    }
}
