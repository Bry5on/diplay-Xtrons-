package com.shilapi.xcertplay.bluetooth

/**
 * What to do when the headset and music profile services do not answer.
 *
 * Android's connection state uses the same values as [android.bluetooth.BluetoothProfile]:
 * 0 disconnected, 2 connected. A missing reading is not treated as disconnected. When the
 * profile services never answer, turning the Android radio off is not used: on this head unit
 * the car Bluetooth stack switches it back on within a few seconds and the hands-free link stays up.
 */
internal object BluetoothLinkPlan {
    const val DISCONNECTED = 0

    fun powerOffRadio(connectionState: Int?, profilesUnreadable: Boolean = false): Boolean =
        !profilesUnreadable && connectionState != DISCONNECTED

    fun stateName(connectionState: Int?): String = when (connectionState) {
        null -> "unreadable"
        DISCONNECTED -> "disconnected"
        1 -> "connecting"
        2 -> "connected"
        3 -> "disconnecting"
        else -> connectionState.toString()
    }
}
