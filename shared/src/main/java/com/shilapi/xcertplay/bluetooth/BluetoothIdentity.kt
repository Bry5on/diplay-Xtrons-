package com.shilapi.xcertplay.bluetooth

import java.security.MessageDigest

/**
 * Compares the Bluetooth address DiPlay tells the iPhone with the addresses Android can read.
 * The report stores a short digest, not the address itself.
 */
internal object BluetoothIdentity {
    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun usable(mac: String?): Boolean =
        mac != null && MAC.matches(mac) &&
            !mac.equals("00:00:00:00:00:00", ignoreCase = true) &&
            !mac.startsWith("02:00:00:00:00:", ignoreCase = true)

    /** Eight hex characters. Equal digests mean the same address; this is not the address. */
    fun token(mac: String?): String {
        if (mac.isNullOrBlank()) return "absent"
        if (!usable(mac)) return "unusable"
        val digest = MessageDigest.getInstance("SHA-256").digest(mac.uppercase().toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun wifiAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.firstOrNull { it.name.startsWith("wlan") }
            ?.hardwareAddress
            ?.takeIf { it.size == 6 }
            ?.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    fun line(advertised: String?, adapter: String?, settings: String?, wifi: String?, role: String): String {
        val source = when {
            usable(adapter) && advertised.equals(adapter, ignoreCase = true) -> "adapter"
            usable(settings) && advertised.equals(settings, ignoreCase = true) -> "settings"
            usable(advertised) -> "other"
            else -> "absent"
        }
        val settingsMatch = when {
            !usable(adapter) || !usable(settings) -> "unknown"
            adapter.equals(settings, ignoreCase = true) -> "yes"
            else -> "no"
        }
        return "Bluetooth identity: role=$role advertisedSource=$source " +
            "advertised=${token(advertised)} adapter=${token(adapter)} settings=${token(settings)} " +
            "wifi=${token(wifi)} adapterMatchesSettings=$settingsMatch " +
            "iap2Name=blue airplayBluetoothIDs=same-as-iap2 vendorMac=not-exposed"
    }
}
