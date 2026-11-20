package com.shilapi.xcertplay.bluetooth

internal interface AudioProfileClient {
    val label: String
    fun connected(): Set<String>
    fun policy(address: String): Int?
    fun write(address: String, policy: Int): ProfileWrite
    fun disconnect(address: String): ProfileDisconnect
    /** Null when this profile's proxy opened. */
    val unavailable: String?
}

/**
 * Drops one audio profile and later puts its connection policy back.
 * A refused or missing API is logged and does not throw.
 */
internal class BluetoothAudioActuator(private val log: (String) -> Unit) {
    fun drop(client: AudioProfileClient, addresses: Set<String>, rememberPolicy: Boolean): List<BluetoothAudioPolicy.SavedPolicy> {
        if (client.unavailable != null) {
            log("Bluetooth audio: profile=${client.label} unavailable reason=${client.unavailable} action=none")
            return emptyList()
        }
        val connected = client.connected()
        val targets = BluetoothAudioPolicy.targets(null, addresses).ifEmpty { connected }
        if (targets.isEmpty() && connected.isEmpty()) {
            log("Bluetooth audio: profile=${client.label} connected=0 action=none")
            return emptyList()
        }
        val saved = mutableListOf<BluetoothAudioPolicy.SavedPolicy>()
        for (address in targets) {
            val up = connected.any { it.equals(address, ignoreCase = true) }
            val policy = client.policy(address)
            if (rememberPolicy && policy != null && policy != BluetoothAudioPolicy.OFF) {
                val wrote = client.write(address, BluetoothAudioPolicy.OFF)
                log("Bluetooth audio: profile=${client.label} connected=$up policy=$policy action=forbid result=$wrote")
                if (wrote == ProfileWrite.OK) {
                    saved += BluetoothAudioPolicy.SavedPolicy(client.label, address, policy)
                } else if (wrote == ProfileWrite.SECURITY || wrote == ProfileWrite.ABSENT) {
                    log("Bluetooth audio: profile=${client.label} fail-safe result=$wrote policy-unchanged")
                }
            } else if (rememberPolicy && policy == null && up) {
                log("Bluetooth audio: profile=${client.label} connected=true policy=unread action=disconnect-only")
            }
            if (up) {
                val result = client.disconnect(address)
                log("Bluetooth audio: profile=${client.label} action=disconnect result=$result")
                if (result == ProfileDisconnect.SECURITY || result == ProfileDisconnect.ABSENT) {
                    log("Bluetooth audio: profile=${client.label} fail-safe result=$result link-unchanged")
                }
            }
        }
        return saved
    }

    /** False when a saved policy could not be put back, so the journal must stay. */
    fun restore(client: AudioProfileClient, saved: List<BluetoothAudioPolicy.SavedPolicy>): Boolean {
        if (client.unavailable != null) {
            log("Bluetooth audio: profile=${client.label} restore skipped reason=${client.unavailable}")
            return false
        }
        var ok = true
        for (entry in saved.filter { it.profile == client.label }) {
            var result = client.write(entry.address, entry.policy)
            if (result == ProfileWrite.REJECTED && entry.policy != BluetoothAudioPolicy.ON) {
                log("Bluetooth audio: profile=${client.label} restore rejected policy=${entry.policy} fallback=${BluetoothAudioPolicy.ON}")
                result = client.write(entry.address, BluetoothAudioPolicy.ON)
            }
            log("Bluetooth audio: profile=${client.label} action=restore policy=${entry.policy} result=$result")
            if (result != ProfileWrite.OK) ok = false
        }
        return ok
    }
}
