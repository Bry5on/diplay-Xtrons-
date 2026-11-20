package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioActuatorTest {
    @Test fun aConnectedProfileIsForbiddenAndDisconnectedAndCanBeRestored() {
        val client = FakeClient(label = "A2DP", connected = mutableSetOf("11:22:33:44:55:66"), policy = 1000)
        val logs = mutableListOf<String>()
        val actuator = BluetoothAudioActuator(logs::add)
        val saved = actuator.drop(client, setOf("11:22:33:44:55:66"), rememberPolicy = true)
        assertEquals(listOf(BluetoothAudioPolicy.SavedPolicy("A2DP", "11:22:33:44:55:66", 1000)), saved)
        assertEquals(0, client.policy)
        assertTrue(client.connected().isEmpty())
        assertTrue(logs.any { it.contains("action=forbid") && it.contains("result=OK") })
        assertTrue(logs.any { it.contains("action=disconnect") && it.contains("result=OK") })

        assertTrue(actuator.restore(client, saved))
        assertEquals(1000, client.policy)
        assertTrue(logs.any { it.contains("action=restore") && it.contains("result=OK") })
    }

    @Test fun anUnreadablePolicyIsDisconnectedWithoutBeingRewritten() {
        val client = FakeClient(label = "HFP", connected = mutableSetOf("11:22:33:44:55:66"), policy = null)
        val logs = mutableListOf<String>()
        val saved = BluetoothAudioActuator(logs::add).drop(client, setOf("11:22:33:44:55:66"), rememberPolicy = true)
        assertTrue(saved.isEmpty())
        assertTrue(client.connected().isEmpty())
        assertTrue(logs.any { it.contains("policy=unread") })
        assertTrue(logs.none { it.contains("action=forbid") })
    }

    @Test fun aPrivilegedRejectionLeavesTheProfileAlone() {
        val client = FakeClient(label = "HFP_CLIENT", connected = mutableSetOf("11:22:33:44:55:66"), policy = 100,
            write = ProfileWrite.SECURITY, disconnect = ProfileDisconnect.SECURITY)
        val logs = mutableListOf<String>()
        val saved = BluetoothAudioActuator(logs::add).drop(client, setOf("11:22:33:44:55:66"), rememberPolicy = true)
        assertTrue(saved.isEmpty())
        assertEquals(setOf("11:22:33:44:55:66"), client.connected())
        assertEquals(100, client.policy)
        assertTrue(logs.any { it.contains("fail-safe") && it.contains("SECURITY") })
    }

    @Test fun aRejectedAutoConnectPolicyFallsBackToAllowed() {
        val client = FakeClient(label = "A2DP_SINK", connected = mutableSetOf(), policy = 1000, rejectAbove = 100)
        val logs = mutableListOf<String>()
        val actuator = BluetoothAudioActuator(logs::add)
        val saved = actuator.drop(client, setOf("11:22:33:44:55:66"), rememberPolicy = true)
        assertEquals(0, client.policy)
        assertFalse(actuator.restore(client, saved) && client.policy == 1000)
        assertEquals(BluetoothAudioPolicy.ON, client.policy)
        assertTrue(logs.any { it.contains("fallback=${BluetoothAudioPolicy.ON}") })
    }

    @Test fun aMissingProfileIsSkipped() {
        val client = FakeClient(label = "A2DP", connected = mutableSetOf("11:22:33:44:55:66"), policy = 100,
            unavailable = "timeout")
        val logs = mutableListOf<String>()
        assertTrue(BluetoothAudioActuator(logs::add).drop(client, emptySet(), true).isEmpty())
        assertEquals(setOf("11:22:33:44:55:66"), client.connected())
        assertTrue(logs.single().contains("unavailable reason=timeout action=none"))
    }

    private class FakeClient(
        override val label: String,
        private val connected: MutableSet<String>,
        var policy: Int?,
        private val write: ProfileWrite? = null,
        private val disconnect: ProfileDisconnect = ProfileDisconnect.OK,
        override val unavailable: String? = null,
        private val rejectAbove: Int? = null,
    ) : AudioProfileClient {
        override fun connected(): Set<String> = connected.toSet()

        override fun policy(address: String): Int? = policy

        override fun write(address: String, policy: Int): ProfileWrite {
            write?.let { return it }
            if (rejectAbove != null && policy > rejectAbove) return ProfileWrite.REJECTED
            this.policy = policy
            return ProfileWrite.OK
        }

        override fun disconnect(address: String): ProfileDisconnect {
            if (disconnect == ProfileDisconnect.OK) connected.remove(address)
            return disconnect
        }
    }
}
