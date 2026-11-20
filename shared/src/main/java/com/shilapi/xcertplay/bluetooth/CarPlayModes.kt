package com.shilapi.xcertplay.bluetooth

/**
 * Phone-call and Siri state from an AirPlay modesChanged command.
 *
 * iOS sends these even when the sound itself is on the head unit's hands-free link.
 * A missing field means "unchanged", not "ended".
 */
internal object CarPlayModes {
    const val SPEECH = 1
    const val PHONE = 2
    const val SPEAKING = 1
    const val RECOGNIZING = 2
    const val RETURN_DEBOUNCE_MILLIS = 1_500L
    const val RETURN_GAP_MILLIS = 10_000L

    data class Update(val phoneCall: Boolean?, val speechMode: Int?)

    fun parse(params: Map<String, Any?>): Update {
        val states = (params["appStates"] as? List<*>) ?: (params["appState"] as? List<*>) ?: return Update(null, null)
        var phone: Boolean? = null
        var speech: Int? = null
        for (item in states) {
            val map = item as? Map<*, *> ?: continue
            when (intValue(map["appStateID"])) {
                PHONE -> phone = boolValue(map["state"])
                SPEECH -> speech = intValue(map["speechMode"])
            }
        }
        return Update(phone, speech)
    }

    fun speechName(mode: Int): String = when (mode) {
        SPEAKING -> "speaking"
        RECOGNIZING -> "recognizing"
        -1, 0 -> "none"
        else -> mode.toString()
    }

    fun busy(phoneCall: Boolean, speechMode: Int): Boolean =
        phoneCall || speechMode == SPEAKING || speechMode == RECOGNIZING

    /** Null means a return cycle is allowed. */
    fun skipReturn(busy: Boolean, millisSinceLast: Long, cycleInProgress: Boolean): String? = when {
        busy -> "call-active"
        cycleInProgress -> "in-progress"
        millisSinceLast in 0 until RETURN_GAP_MILLIS -> "recent"
        else -> null
    }

    /** How long to wait when a return was skipped only because the last one was recent. */
    fun deferReturnMillis(millisSinceLast: Long): Long? =
        if (millisSinceLast in 0 until RETURN_GAP_MILLIS) RETURN_GAP_MILLIS - millisSinceLast else null

    private fun intValue(value: Any?): Int? = when (value) {
        is Boolean -> if (value) 1 else 0
        is Number -> value.toInt()
        else -> null
    }

    private fun boolValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> null
    }
}

/** Remembers the last phone and Siri state and reports when a session starts or ends. */
internal class CarPlayModeTracker {
    private var phoneCall = false
    private var speechMode = -1
    private var wasBusy = false

    val busy: Boolean get() = CarPlayModes.busy(phoneCall, speechMode)

    fun phoneLabel(): String = phoneCall.toString()

    fun speechLabel(): String = CarPlayModes.speechName(speechMode)

    fun apply(update: CarPlayModes.Update): String {
        update.phoneCall?.let { phoneCall = it }
        update.speechMode?.let { speechMode = it }
        val nowBusy = busy
        val event = when {
            nowBusy && !wasBusy -> "started"
            !nowBusy && wasBusy -> "ended"
            else -> "unchanged"
        }
        wasBusy = nowBusy
        return event
    }

    fun reset() {
        phoneCall = false
        speechMode = -1
        wasBusy = false
    }
}
