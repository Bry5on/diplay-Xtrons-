package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.provider.Settings

/**
 * The address DiPlay tells the iPhone. Android's adapter call returns a placeholder to a normal
 * app, and the previous fallback is a hash of the accessory key, not a radio. A readable
 * controller address replaces that hash.
 */
internal object BluetoothControllerAddress {
    data class Found(val address: String, val chosen: String, val localBit: String, val probes: String)

    fun select(readings: List<Pair<String, String?>>, synthetic: String): Found {
        val probes = readings.joinToString(" ") { (source, value) -> "$source=${BluetoothIdentity.token(value)}" }
        val hit = readings.firstOrNull { BluetoothIdentity.usable(it.second) }
        val address = hit?.second ?: synthetic
        val chosen = hit?.first ?: "synthetic"
        return Found(address, chosen, BluetoothIdentity.localBit(address), probes)
    }

    fun read(context: Context, adapterAddress: String?, synthetic: String): Found {
        val readings = mutableListOf(
            "manual" to BluetoothAudioPreference.manualAddress(context),
            "adapter" to adapterAddress,
            "secure" to text { Settings.Secure.getString(context.contentResolver, "bluetooth_address") },
            "global" to text { Settings.Global.getString(context.contentResolver, "bluetooth_address") },
            "system" to text { Settings.System.getString(context.contentResolver, "bluetooth_address") },
            "sysfs" to sysfs(),
        )
        PROPERTY_KEYS.forEach { (label, key) -> readings += label to property(key) }
        return select(readings, synthetic)
    }

    private val PROPERTY_KEYS = listOf(
        "prop-bdroid" to "persist.service.bdroid.bdaddr",
        "prop-vendor-bdroid" to "persist.vendor.service.bdroid.bdaddr",
        "prop-boot" to "ro.boot.btmacaddr",
        "prop-ro-vendor" to "ro.vendor.bluetooth.bdaddr",
        "prop-persist-vendor" to "persist.vendor.bluetooth.address",
    )

    private fun text(read: () -> String?): String? = runCatching(read).getOrNull()

    private fun sysfs(): String? {
        val files = runCatching {
            java.io.File("/sys/class/bluetooth").listFiles()?.map { java.io.File(it, "address") }
        }.getOrNull().orEmpty().ifEmpty { listOf(java.io.File("/sys/class/bluetooth/hci0/address")) }
        return files.firstNotNullOfOrNull { file ->
            runCatching { if (file.isFile && file.canRead()) file.readText().trim() else null }.getOrNull()
                ?.takeIf { BluetoothIdentity.usable(it) }
        }
    }

    private fun property(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as String).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
