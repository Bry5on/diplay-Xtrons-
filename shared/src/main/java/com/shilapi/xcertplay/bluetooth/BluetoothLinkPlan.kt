package com.shilapi.xcertplay.bluetooth

/**
 * What to do when the headset and music profile services do not answer.
 *
 * Android's connection state uses the same values as [android.bluetooth.BluetoothProfile]:
 * 0 disconnected, 2 connected. A missing reading is not treated as disconnected: on a head unit
 * whose profile services time out, that is the only way to make the iPhone drop Bluetooth audio.
 */
internal object BluetoothLinkPlan {
    const val DISCONNECTED = 0

    fun powerOffRadio(connectionState: Int?): Boolean = connectionState != DISCONNECTED

    fun stateName(connectionState: Int?): String = when (connectionState) {
        null -> "unreadable"
        DISCONNECTED -> "disconnected"
        1 -> "connecting"
        2 -> "connected"
        3 -> "disconnecting"
        else -> connectionState.toString()
    }
}
