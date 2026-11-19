package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.bluetooth.BluetoothAudioPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtronsIxHeadUnitTest {
    @Test fun anXtronsIxBoardOrKingPadFingerprintMatches() {
        assertTrue(XtronsIxHeadUnit.matches(XtronsIxHeadUnit.Identity(board = "SPRD XTRONS_IX")))
        assertTrue(XtronsIxHeadUnit.matches(XtronsIxHeadUnit.Identity(product = "XTRONS-IX")))
        assertTrue(XtronsIxHeadUnit.matches(XtronsIxHeadUnit.Identity(
            fingerprint = "Vastking/KingPad_SA10_EEA:12/SP1A.210812.016/userdebug",
        )))
    }

    @Test fun otherHeadUnitsDoNotMatch() {
        assertFalse(XtronsIxHeadUnit.matches(XtronsIxHeadUnit.Identity(product = "geely_fx11", board = "SPRD")))
        assertFalse(XtronsIxHeadUnit.matches(XtronsIxHeadUnit.Identity(product = "ums512", hardware = "unisoc")))
        assertFalse(XtronsIxHeadUnit.matches(XtronsIxHeadUnit.Identity(product = "robolectric")))
    }

    @Test fun bluetoothAudioDropDefaultsOnOnlyForThisHeadUnitUntilTheDriverChooses() {
        assertTrue(BluetoothAudioPreference.enabled(stored = null, xtronsIx = true))
        assertFalse(BluetoothAudioPreference.enabled(stored = null, xtronsIx = false))
        assertFalse(BluetoothAudioPreference.enabled(stored = false, xtronsIx = true))
        assertTrue(BluetoothAudioPreference.enabled(stored = true, xtronsIx = false))
    }
}
