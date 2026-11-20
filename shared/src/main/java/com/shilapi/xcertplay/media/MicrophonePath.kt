package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.media.MediaRecorder

/**
 * Names the microphone path a report can check on the head unit.
 *
 * [FACTORY_HFP_SCO] means this AudioRecord is routed to the Bluetooth hands-free mic. The other
 * CarPlay paths mean the samples are the head unit's own mic, sent up the CarPlay stream. Factory
 * Bluetooth can stay connected either way; that is a separate profile snapshot.
 */
internal object MicrophonePath {
    const val FACTORY_HFP_SCO = "factory-hfp-sco"
    const val SILENCED = "silenced"
    const val SIRI = "siri"
    const val CARPLAY_MIC_CAR_PHONE_STATE = "carplay-mic-car-phone-state"
    const val CARPLAY_MIC_VOICE_COMMUNICATION = "carplay-mic-voice-communication"
    const val CARPLAY_MIC_PLAIN = "carplay-mic-plain"
    const val UNROUTED = "unrouted"

    fun classify(
        audioType: String,
        source: Int,
        routedDeviceType: Int?,
        clientSilenced: Boolean?,
        carPhoneAudio: Boolean = false,
    ): String = when {
        routedDeviceType == BLUETOOTH_SCO -> FACTORY_HFP_SCO
        clientSilenced == true -> SILENCED
        audioType == "speechrecognition" -> SIRI
        audioType == "telephony" && source == MediaRecorder.AudioSource.VOICE_RECOGNITION && carPhoneAudio ->
            CARPLAY_MIC_CAR_PHONE_STATE
        audioType == "telephony" && source == MediaRecorder.AudioSource.VOICE_COMMUNICATION ->
            CARPLAY_MIC_VOICE_COMMUNICATION
        else -> CARPLAY_MIC_PLAIN
    }

    fun routedDeviceName(type: Int?): String = when (type) {
        null -> "unknown"
        BUILTIN_MIC -> "BUILTIN_MIC"
        BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        WIRED_HEADSET -> "WIRED_HEADSET"
        USB_HEADSET -> "USB_HEADSET"
        BUS -> "BUS"
        else -> "TYPE_$type"
    }

    fun audioModeName(mode: Int?): String = when (mode) {
        null -> "unknown"
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> mode.toString()
    }

    private const val BLUETOOTH_SCO = 7
    private const val BUILTIN_MIC = 15
    private const val WIRED_HEADSET = 3
    private const val USB_HEADSET = 22
    private const val BUS = 21

    fun report(
        audioType: String,
        source: Int,
        routedDeviceType: Int?,
        clientSilenced: String,
        audioMode: Int?,
        otherRecordings: String,
        carPhoneAudio: Boolean,
        aecAvailable: Boolean?,
        aecEnabled: Boolean,
        nsAvailable: Boolean?,
        nsEnabled: Boolean,
        effects: String,
    ): String {
        val silenced = when (clientSilenced) {
            "true" -> true
            "false" -> false
            else -> null
        }
        val path = classify(audioType, source, routedDeviceType, silenced, carPhoneAudio)
        return "path=$path routedDevice=${routedDeviceName(routedDeviceType)} " +
            "clientSilenced=$clientSilenced audioMode=${audioMode ?: "unknown"}(${audioModeName(audioMode)}) " +
            "otherRecordings=$otherRecordings carPhoneAudio=$carPhoneAudio " +
            "aecAvailable=${aecAvailable ?: "unknown"} aecEnabled=$aecEnabled " +
            "nsAvailable=${nsAvailable ?: "unknown"} nsEnabled=$nsEnabled effects=$effects"
    }
}
