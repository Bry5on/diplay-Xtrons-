package com.shilapi.xcertplay.bluetooth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothModuleProbeTest {
    @Test fun serialNodesAreNamedAndOrdinaryFilesAreNot() {
        assertTrue(BluetoothModuleProbe.isSerialNode("ttyS0"))
        assertTrue(BluetoothModuleProbe.isSerialNode("ttyMT1"))
        assertTrue(BluetoothModuleProbe.isSerialNode("ttyHS0"))
        assertTrue(BluetoothModuleProbe.isSerialNode("ttyUSB0"))
        assertTrue(BluetoothModuleProbe.isSerialNode("bt"))
        assertFalse(BluetoothModuleProbe.isSerialNode("binder"))
        assertFalse(BluetoothModuleProbe.isSerialNode("null"))
    }

    @Test fun aDirectoryListingReportsAccessWithoutOpeningTheNode() {
        val root = File.createTempFile("diplay-dev", "").apply { delete(); mkdirs() }
        try {
            File(root, "ttyS0").writeText("not-a-device")
            File(root, "null").writeText("skip")
            val nodes = BluetoothModuleProbe.listNodes(root)
            assertEquals(listOf("ttyS0"), nodes?.map { it.node })
            val lines = BluetoothModuleProbe.uartLines(nodes, listed = true)
            assertTrue(lines[0].startsWith("Bluetooth module: uart count=1"))
            assertTrue(lines.any { "node=ttyS0" in it && "exists=true" in it && "writable=" in it })
            assertFalse(lines.any { "not-a-device" in it })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun anUnreadableDevDirectoryIsNamed() {
        assertEquals(
            listOf("Bluetooth module: uart dir=/dev unreadable"),
            BluetoothModuleProbe.uartLines(null, listed = false),
        )
    }

    @Test fun propertiesAndDriversKeepControlsAndHideAddresses() {
        val properties = BluetoothModuleProbe.propertyLines(
            "[persist.bluetooth.hfp]: [off]\n[persist.service.bdroid.bdaddr]: [AA:BB:CC:DD:EE:FF]\n[wifi.interface]: [wlan0]\n",
        )
        assertTrue(properties.any { it.contains("key=persist.bluetooth.hfp value=off") })
        assertTrue(properties.any { it.contains("key=persist.service.bdroid.bdaddr value=present") })
        assertFalse(properties.any { it.contains("AA:BB") || it.contains("wlan0") })
        val drivers = BluetoothModuleProbe.driverLines("serial /dev/ttyS 4 64\npty /dev/pts 136 0\n")
        assertEquals(listOf("Bluetooth module: tty-driver serial /dev/ttyS 4 64"), drivers)
    }

    @Test fun carBluetoothPackagesAndControlComponentsAreSelected() {
        assertTrue(BluetoothModuleProbe.vendorPackage("android.car.service.bt"))
        assertTrue(BluetoothModuleProbe.vendorPackage("com.xtrons.bt"))
        assertFalse(BluetoothModuleProbe.vendorPackage("com.android.settings"))
        val line = BluetoothModuleProbe.componentLine(
            "android.car.service.bt",
            "service",
            "com.vendor.HandsfreeService",
            exported = true,
            actions = listOf("com.vendor.action.HFP_DISCONNECT"),
        )
        assertTrue(line.contains("class=HandsfreeService"))
        assertTrue(line.contains("exported=true"))
        assertTrue(line.contains("control=true"))
        assertTrue(line.contains("actions=com.vendor.action.HFP_DISCONNECT"))
        assertFalse(line.contains("name="))
    }
}
