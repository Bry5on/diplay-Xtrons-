package com.shilapi.xcertplay.bluetooth

/**
 * What to do when the headset and music profile services do not answer.
 *
 * Android's connection state uses the same values as [android.bluetooth.BluetoothProfile]:
 * 0 disconnected, 2 connected. A missing reading is not treated as disconnected. When the
 * profile services never answer, one radio-off is used and the head unit is allowed to turn it
 * back on. Repeating that off is not used: the second off fought the car stack.
 */
internal object BluetoothLinkPlan {
    const val DISCONNECTED = 0

    fun powerOffRadio(connectionState: Int?, profilesUnreadable: Boolean = false): Boolean =
        !profilesUnreadable && connectionState != DISCONNECTED

    /** One off-then-back cycle when the profile services never answer. */
    fun oneShotRadioCycle(profilesUnreadable: Boolean): Boolean = profilesUnreadable

    fun stateName(connectionState: Int?): String = when (connectionState) {
        null -> "unreadable"
        DISCONNECTED -> "disconnected"
        1 -> "connecting"
        2 -> "connected"
        3 -> "disconnecting"
        else -> connectionState.toString()
    }
}
