package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothProfileMethodsTest {
    @Test fun priorityIsReadAndClearedOnAnApi29StyleProxy() {
        val proxy = PriorityProxy()
        assertEquals(1000, BluetoothProfileMethods.readPolicy(proxy, DEVICE))
        assertEquals(ProfileWrite.OK, BluetoothProfileMethods.writePolicy(proxy, DEVICE, 0))
        assertEquals(0, proxy.priority)
        assertEquals(ProfileDisconnect.OK, BluetoothProfileMethods.disconnect(proxy, DEVICE))
        assertEquals(1, proxy.disconnects)
    }

    @Test fun autoConnectIsRejectedByTheWrapper() {
        val proxy = PriorityProxy()
        assertEquals(ProfileWrite.REJECTED, BluetoothProfileMethods.writePolicy(proxy, DEVICE, 1000))
        assertEquals(1000, proxy.priority)
    }

    @Test fun connectionPolicyIsPreferredWhenTheMethodExists() {
        val proxy = ConnectionPolicyProxy()
        assertEquals(ProfileWrite.OK, BluetoothProfileMethods.writePolicy(proxy, DEVICE, 0))
        assertEquals(0, proxy.policy)
        assertEquals(0, BluetoothProfileMethods.readPolicy(proxy, DEVICE))
    }

    @Test fun aSecurityExceptionDoesNotEscape() {
        val proxy = RejectingProxy()
        assertEquals(ProfileWrite.SECURITY, BluetoothProfileMethods.writePolicy(proxy, DEVICE, 0))
        assertEquals(ProfileDisconnect.SECURITY, BluetoothProfileMethods.disconnect(proxy, DEVICE))
    }

    @Test fun aProxyWithoutTheMethodsIsAbsent() {
        assertNull(BluetoothProfileMethods.readPolicy(Any(), DEVICE))
        assertEquals(ProfileWrite.ABSENT, BluetoothProfileMethods.writePolicy(Any(), DEVICE, 0))
        assertEquals(ProfileDisconnect.ABSENT, BluetoothProfileMethods.disconnect(Any(), DEVICE))
    }

    private class PriorityProxy {
        var priority = 1000
        var disconnects = 0
        fun getPriority(device: Any): Int = priority
        fun setPriority(device: Any, value: Int): Boolean {
            if (value != 0 && value != 100) return false
            priority = value
            return true
        }
        fun disconnect(device: Any): Boolean {
            disconnects++
            return true
        }
    }

    private class ConnectionPolicyProxy {
        var policy = 100
        fun getConnectionPolicy(device: Any): Int = policy
        fun setConnectionPolicy(device: Any, value: Int): Boolean {
            policy = value
            return true
        }
        fun disconnect(device: Any): Boolean = true
    }

    private class RejectingProxy {
        fun setPriority(device: Any, value: Int): Boolean = throw SecurityException("privileged")
        fun disconnect(device: Any): Boolean = throw SecurityException("privileged")
    }

    private companion object {
        const val DEVICE = "phone"
    }
}
