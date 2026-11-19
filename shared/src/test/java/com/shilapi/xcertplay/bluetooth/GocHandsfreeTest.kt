package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GocHandsfreeTest {
    @Test fun onlyAGocStatusOrCommandMarksTheNode() {
        assertTrue(GocHandsfree.isCommandChannel("AT#CY\r\n"))
        assertTrue(GocHandsfree.isCommandChannel("\r\nIA\r\n"))
        assertTrue(GocHandsfree.isCommandChannel("MG3\n"))
        assertFalse(GocHandsfree.isCommandChannel("random binary ok data"))
        assertTrue(GocHandsfree.hasAtCommand("at#cd"))
        assertEquals("IV", GocHandsfree.matchedWord("IV\r"))
        assertEquals("AT#CD", GocHandsfree.matchedWord("AT#CD\r\n"))
        assertEquals("status-only", GocHandsfree.disconnectSkip(identified = true, atConfirmed = false))
        assertEquals(null, GocHandsfree.disconnectSkip(identified = true, atConfirmed = true))
    }

    @Test fun disconnectAndReconnectStayOnHandsFree() {
        assertEquals("AT#CD\r\n", GocHandsfree.DISCONNECT)
        assertEquals("AT#CC\r\n", GocHandsfree.RECONNECT)
        assertEquals("AT#CY\r\n", GocHandsfree.QUERY)
        assertTrue(GocHandsfree.hfpDown("\r\nIA\r\n"))
        assertTrue(GocHandsfree.hfpDown("MG1"))
        assertTrue(GocHandsfree.hfpUp("IV"))
        assertTrue(GocHandsfree.hfpUp("MG6"))
        assertFalse(GocHandsfree.hfpUp("MG1"))
        assertFalse(GocHandsfree.DISCONNECT.contains("AT#CZ"))
    }

    @Test fun aSecondHandsFreeReturnStops() {
        val cycle = GocHandsfree.Cycle()
        assertEquals(null, cycle.note("IV"))
        cycle.sent()
        assertEquals(null, cycle.note("IV"))
        assertEquals("again", cycle.note("IA\r\nIV"))
        cycle.sent()
        assertEquals("stop", cycle.note("IA\r\nMG4"))
        assertTrue(cycle.stopped)
        assertEquals(null, cycle.note("IA\r\nIV"))
    }

    @Test fun secretsAndTheLogLineStayBounded() {
        val masked = GocHandsfree.mask("PIN 123456 aabbccddeeff00112233445566778899 number 13800138000")
        assertFalse(masked.contains("123456"))
        assertFalse(masked.contains("aabbccdd"))
        assertFalse(masked.contains("13800138000"))
        assertTrue(masked.contains("[secret]"))
        assertTrue(masked.contains("[link]"))
        assertTrue(masked.contains("[number]"))
        val line = GocHandsfree.serialLine("ttyBT0", "AT#CD\r\n".toByteArray())
        assertTrue(line.startsWith("Bluetooth module serial: node=ttyBT0 text="))
        assertTrue(line.contains("bytes=41542343.440d0a"))
        assertFalse(line.contains("hex="))
        assertFalse(line.contains("name="))
    }
}
