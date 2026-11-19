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
        assertTrue(line.contains("iap2Transport=blue"))
        assertTrue(line.contains("chosen=unspecified"))
        assertTrue(line.contains("vendorMac=not-exposed"))
        assertTrue(!line.contains(adapter))
        val other = BluetoothIdentity.line("99:88:77:66:55:44", adapter, adapter, null, role = "visible")
        assertTrue(other.contains("advertisedSource=other"))
        assertTrue(other.contains("wifi=absent"))
    }
}

class BluetoothControllerAddressTest {
    @Test fun aReadableControllerAddressReplacesTheSyntheticIdentity() {
        val radio = "04:BB:CC:DD:EE:10"
        val synthetic = "02:11:22:33:44:55"
        val found = BluetoothControllerAddress.select(
            listOf(
                "adapter" to "02:00:00:00:00:00",
                "secure" to null,
                "sysfs" to radio,
            ),
            synthetic,
        )
        assertEquals(radio, found.address)
        assertEquals("sysfs", found.chosen)
        assertEquals("clear", found.localBit)
        assertTrue(found.probes.contains("adapter=unusable"))
        assertTrue(found.probes.contains("secure=absent"))
        assertTrue(!found.probes.contains(radio))
    }

    @Test fun aSavedAddressIsUsedBeforeAnyProbe() {
        val typed = "04:11:22:33:44:55"
        val found = BluetoothControllerAddress.select(
            listOf("manual" to typed, "sysfs" to "04:AA:BB:CC:DD:EE"),
            "02:11:22:33:44:55",
        )
        assertEquals(typed, found.address)
        assertEquals("manual", found.chosen)
        assertEquals("clear", found.localBit)
        assertEquals("", com.shilapi.xcertplay.bluetooth.BluetoothAudioPreference.normalizeManualAddress("  "))
        assertEquals(typed, com.shilapi.xcertplay.bluetooth.BluetoothAudioPreference.normalizeManualAddress("04:11:22:33:44:55"))
        assertEquals(null, com.shilapi.xcertplay.bluetooth.BluetoothAudioPreference.normalizeManualAddress("not-a-mac"))
    }

    @Test fun theIdentityHashIsUsedOnlyWhenNoControllerAddressIsReadable() {
        val synthetic = "02:11:22:33:44:55"
        val found = BluetoothControllerAddress.select(
            listOf("adapter" to "02:00:00:00:00:00", "secure" to null, "sysfs" to "not-a-mac"),
            synthetic,
        )
        assertEquals(synthetic, found.address)
        assertEquals("synthetic", found.chosen)
        assertEquals("set", found.localBit)
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

    @Test fun aReceiverActionInsideTheXmlChunkIsListed() {
        val action = "com.vendor.action.DISCONNECT_HFP"
        val parsed = BluetoothVendorManifest.filters(xmlWithReceiver(action))
        assertEquals(listOf(action), parsed.receivers)
        assertTrue(parsed.stringCount > 0)
        assertEquals(listOf(action), BluetoothVendorManifest.safeDisconnect(parsed.receivers))
    }

    @Test fun theManifestStringPoolYieldsTheDisconnectAction() {
        val text = "com.vendor.action.DISCONNECT_HFP"
        val found = BluetoothVendorManifest.interesting("com.vendor", pool(text, "layout_main"))
        assertEquals(listOf(text), found)
        assertEquals(listOf(text), BluetoothVendorManifest.safeDisconnect(found))
    }

    private fun xmlWithReceiver(action: String): ByteArray {
        val names = listOf("receiver", "intent-filter", "action", "name", action)
        val pool = pool(*names.toTypedArray())
        val body = ByteBuffer.allocate(pool.size + 256).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(0x0003)
        body.putShort(8)
        body.putInt(0)
        body.put(pool)
        fun start(nameIndex: Int, attrName: Int = -1, attrValue: Int = -1) {
            val count = if (attrName >= 0) 1 else 0
            val size = 16 + 20 + count * 20
            body.putShort(0x0102)
            body.putShort(16)
            body.putInt(size)
            body.putInt(0)
            body.putInt(-1)
            body.putInt(-1)
            body.putInt(nameIndex)
            body.putShort(20)
            body.putShort(20)
            body.putShort(count.toShort())
            body.putShort(0)
            body.putShort(0)
            body.putShort(0)
            if (count == 1) {
                body.putInt(-1)
                body.putInt(attrName)
                body.putInt(attrValue)
                body.putShort(8)
                body.put(0)
                body.put(3)
                body.putInt(attrValue)
            }
        }
        fun end() {
            body.putShort(0x0103)
            body.putShort(16)
            body.putInt(16)
            body.putInt(0)
            body.putInt(-1)
        }
        start(0)
        start(1)
        start(2, attrName = 3, attrValue = 4)
        end(); end(); end()
        val total = body.position()
        body.putInt(4, total)
        val out = ByteArray(total)
        body.rewind()
        body.get(out)
        return out
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
        body.putInt(4, body.position())
        val out = ByteArray(body.position())
        body.rewind()
        body.get(out)
        return out
    }
}
