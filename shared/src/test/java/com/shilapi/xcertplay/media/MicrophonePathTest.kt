package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePathTest {
    @Test fun bluetoothScoIsTheFactoryHandsFreeMicEvenWhenTheCarPhoneSettingIsOn() {
        assertEquals(
            MicrophonePath.FACTORY_HFP_SCO,
            MicrophonePath.classify(
                "telephony",
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                routedDeviceType = 7,
                clientSilenced = false,
                carPhoneAudio = true,
            ),
        )
        assertEquals("BLUETOOTH_SCO", MicrophonePath.routedDeviceName(7))
    }

    @Test fun aSilencedCabinMicIsNotReportedAsAWorkingCarPlayMic() {
        assertEquals(
            MicrophonePath.SILENCED,
            MicrophonePath.classify("telephony", MediaRecorder.AudioSource.VOICE_COMMUNICATION, 15, true),
        )
    }

    @Test fun siriStaysOnItsOwnPath() {
        assertEquals(
            MicrophonePath.SIRI,
            MicrophonePath.classify("speechrecognition", MediaRecorder.AudioSource.VOICE_RECOGNITION, 15, false),
        )
    }

    @Test fun carPhoneAudioNamesTheVoiceRecognitionCallMic() {
        assertEquals(
            MicrophonePath.CARPLAY_MIC_CAR_PHONE_STATE,
            MicrophonePath.classify(
                "telephony",
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                15,
                false,
                carPhoneAudio = true,
            ),
        )
        assertEquals(
            MicrophonePath.CARPLAY_MIC_VOICE_COMMUNICATION,
            MicrophonePath.classify("telephony", MediaRecorder.AudioSource.VOICE_COMMUNICATION, 15, false, true),
        )
    }

    @Test fun theReportLineCarriesTheFactsAHeadUnitLogNeeds() {
        val line = MicrophonePath.report(
            audioType = "telephony",
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            routedDeviceType = 15,
            clientSilenced = "false",
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            otherRecordings = "none",
            carPhoneAudio = true,
            aecAvailable = true,
            aecEnabled = false,
            nsAvailable = false,
            nsEnabled = false,
            effects = "car-phone-audio",
        )
        assertTrue(line, line.contains("path=${MicrophonePath.CARPLAY_MIC_CAR_PHONE_STATE}"))
        assertTrue(line, line.contains("routedDevice=BUILTIN_MIC"))
        assertTrue(line, line.contains("clientSilenced=false"))
        assertTrue(line, line.contains("audioMode=3(IN_COMMUNICATION)"))
        assertTrue(line, line.contains("aecAvailable=true aecEnabled=false"))
        assertTrue(line, line.contains("nsAvailable=false nsEnabled=false effects=car-phone-audio"))
        assertTrue(line, line.contains("carPhoneAudio=true"))
    }
}
