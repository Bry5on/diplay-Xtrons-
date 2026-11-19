package com.shilapi.xcertplay.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothIdentityTest {
    @Test fun theSameAddressHasTheSameTokenAndADifferentAddressDoesNot() {
        val one = "AA:BB:CC:DD:EE:01"
        val two = "AA:BB:CC:DD:EE:02"
        assertEquals(BluetoothIdentity.token(one), BluetoothIdentity.token(one.lowercase()))
        assertTrue(BluetoothIdentity.token(one) != BluetoothIdentity.token(two))
        assertEquals("unusable", BluetoothIdentity.token("02:00:00:00:00:00"))
        assertEquals("absent", BluetoothIdentity.token(null))
    }

    @Test fun theReportNamesWhichAddressWasAdvertisedWithoutPrintingIt() {
        val adapter = "11:22:33:44:55:66"
        val line = BluetoothIdentity.line(adapter, adapter, adapter, "66:55:44:33:22:11", role = "sent")
        assertTrue(line.contains("advertisedSource=adapter"))
        assertTrue(line.contains("adapterMatchesSettings=yes"))
        assertTrue(line.contains("iap2Name=blue"))
        assertTrue(line.contains("vendorMac=not-exposed"))
        assertTrue(!line.contains(adapter))
        val other = BluetoothIdentity.line("99:88:77:66:55:44", adapter, adapter, null, role = "visible")
        assertTrue(other.contains("advertisedSource=other"))
        assertTrue(other.contains("wifi=absent"))
    }
}

class BluetoothVendorManifestTest {
    @Test fun aDisconnectHfpActionIsTheOnlyOneSent() {
        val actions = listOf(
            "com.vendor.action.DISCONNECT_HFP",
            "com.vendor.action.UNPAIR",
            "com.vendor.action.OPEN_SETTINGS",
            "android.bluetooth.adapter.action.STATE_CHANGED",
        )
        assertEquals(listOf("com.vendor.action.DISCONNECT_HFP"), BluetoothVendorManifest.safeDisconnect(actions))
    }

    @Test fun twoDisconnectActionsAreNotChosen() {
        val actions = listOf("com.vendor.action.DISCONNECT_HFP", "com.vendor.action.DISCONNECT_A2DP")
        assertEquals(2, BluetoothVendorManifest.safeDisconnect(actions).size)
    }

    @Test fun theManifestStringPoolYieldsTheDisconnectAction() {
        val text = "com.vendor.action.DISCONNECT_HFP"
        val found = BluetoothVendorManifest.interesting("com.vendor", pool(text, "layout_main"))
        assertEquals(listOf(text), found)
        assertEquals(listOf(text), BluetoothVendorManifest.safeDisconnect(found))
    }

    private fun pool(vararg values: String): ByteArray {
        val body = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        val header = 28
        val stringsStart = header + values.size * 4
        body.putShort(0x0001)
        body.putShort(header.toShort())
        body.putInt(0)
        body.putInt(values.size)
        body.putInt(0)
        body.putInt(0)
        body.putInt(stringsStart)
        body.putInt(0)
        var cursor = 0
        val encoded = values.map { value ->
            val start = cursor
            val chars = value.toByteArray(Charsets.UTF_16LE)
            cursor += 2 + chars.size + 2
            start to chars
        }
        encoded.forEach { body.putInt(it.first) }
        encoded.forEach { (_, chars) ->
            body.putShort(chars.size.div(2).toShort())
            body.put(chars)
            body.putShort(0)
        }
        val out = ByteArray(body.position())
        body.rewind()
        body.get(out)
        return out
    }
}
