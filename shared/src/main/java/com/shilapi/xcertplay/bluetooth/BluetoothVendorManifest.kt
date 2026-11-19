package com.shilapi.xcertplay.bluetooth

/**
 * Reads action-like strings from another app's binary AndroidManifest and picks at most the
 * disconnect actions whose names say hands-free or music. Anything else is only logged.
 */
internal object BluetoothVendorManifest {
    fun interesting(packageName: String, manifest: ByteArray): List<String> =
        strings(manifest).map { it.trim() }.filter { action ->
            action.length in 8..180 && ' ' !in action && '\u0000' !in action && (
                ".action." in action ||
                    action.startsWith("android.bluetooth.") ||
                    action.startsWith("$packageName.") && (
                        action.contains("hfp", ignoreCase = true) ||
                            action.contains("a2dp", ignoreCase = true) ||
                            action.contains("disconnect", ignoreCase = true) ||
                            action.contains("bluetooth", ignoreCase = true)
                        )
                )
        }.distinct().take(30)

    /** A single clearly named profile disconnect. Unpair, reset, and ambiguous lists are refused. */
    fun safeDisconnect(actions: List<String>): List<String> =
        actions.map { it.trim() }.distinct().filter { action ->
            action.matches(Regex("^[A-Za-z0-9_.]+$")) &&
                !DENY.containsMatchIn(action) &&
                action.contains("disconnect", ignoreCase = true) &&
                PROFILE.containsMatchIn(action)
        }

    fun strings(manifest: ByteArray): List<String> {
        if (manifest.size < 28 || u16(manifest, 0) != 0x0001) return emptyList()
        val headerSize = u16(manifest, 2)
        if (headerSize < 28 || headerSize > manifest.size) return emptyList()
        val stringCount = u32(manifest, 8)
        if (stringCount !in 0..20_000) return emptyList()
        val utf8 = u32(manifest, 16) and 1 != 0
        val stringsStart = u32(manifest, 20)
        if (stringsStart !in 0 until manifest.size) return emptyList()
        return (0 until stringCount).mapNotNull { index ->
            val table = headerSize + index * 4
            if (table + 4 > manifest.size) return emptyList()
            val at = stringsStart + u32(manifest, table)
            if (at !in 0 until manifest.size) return@mapNotNull null
            if (utf8) utf8(manifest, at) else utf16(manifest, at)
        }
    }

    private val DENY = Regex("(?i)unpair|remove|delete|reset|factory|bond|wipe|format")
    private val PROFILE = Regex("(?i)hfp|headset|handsfree|hands-free|a2dp|mediaaudio|phoneaudio")

    private fun utf16(bytes: ByteArray, start: Int): String? {
        if (start + 2 > bytes.size) return null
        var length = u16(bytes, start)
        var pos = start + 2
        if (length and 0x8000 != 0) {
            if (pos + 2 > bytes.size) return null
            length = ((length and 0x7fff) shl 16) or u16(bytes, pos)
            pos += 2
        }
        if (length !in 0..4_000 || pos + length * 2 > bytes.size) return null
        return String(CharArray(length) { index ->
            val low = bytes[pos + index * 2].toInt() and 0xff
            val high = bytes[pos + index * 2 + 1].toInt() and 0xff
            (high shl 8 or low).toChar()
        })
    }

    private fun utf8(bytes: ByteArray, start: Int): String? {
        var pos = start
        val chars = utf8Len(bytes, pos) ?: return null
        pos += chars.second
        val size = utf8Len(bytes, pos) ?: return null
        pos += size.second
        if (size.first !in 0..4_000 || pos + size.first > bytes.size) return null
        return String(bytes, pos, size.first, Charsets.UTF_8)
    }

    private fun utf8Len(bytes: ByteArray, pos: Int): Pair<Int, Int>? {
        if (pos >= bytes.size) return null
        val first = bytes[pos].toInt() and 0xff
        if (first and 0x80 == 0) return first to 1
        if (pos + 1 >= bytes.size) return null
        return ((first and 0x7f) shl 8 or (bytes[pos + 1].toInt() and 0xff)) to 2
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}
