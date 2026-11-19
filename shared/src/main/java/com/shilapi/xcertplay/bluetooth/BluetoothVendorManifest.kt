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

    data class Filters(val stringCount: Int, val receivers: List<String>, val services: List<String>)

    /** Receiver and service intent-filter actions. An APK manifest starts with an XML chunk, not the string pool. */
    fun filters(manifest: ByteArray): Filters {
        val poolAt = findPool(manifest) ?: return Filters(0, emptyList(), emptyList())
        val pool = readPool(manifest, poolAt)
        if (manifest.size < 8 || u16(manifest, 0) != XML) return Filters(pool.size, emptyList(), emptyList())
        val fileEnd = minOf(u32(manifest, 4).takeIf { it > 0 } ?: manifest.size, manifest.size)
        var pos = u16(manifest, 2)
        val stack = ArrayDeque<String>()
        val receivers = mutableListOf<String>()
        val services = mutableListOf<String>()
        while (pos + 8 <= fileEnd) {
            val type = u16(manifest, pos)
            val headerSize = u16(manifest, pos + 2)
            val size = u32(manifest, pos + 4)
            if (size < 8 || pos + size > manifest.size) break
            if (type == START) {
                val name = elementName(manifest, pos, headerSize, pool)
                stack.addLast(name)
                if (name == "action") {
                    val value = attribute(manifest, pos, headerSize, pool, "name")
                    if (!value.isNullOrBlank()) {
                        if (stack.contains("receiver")) receivers += value
                        if (stack.contains("service")) services += value
                    }
                }
            } else if (type == END && stack.isNotEmpty()) {
                stack.removeLast()
            }
            pos += size
        }
        return Filters(pool.size, receivers.distinct(), services.distinct())
    }

    /** A single clearly named profile disconnect. Unpair, reset, and ambiguous lists are refused. */
    fun safeDisconnect(actions: List<String>): List<String> =
        actions.map { it.trim() }.distinct().filter { action ->
            action.matches(Regex("^[A-Za-z0-9_.]+$")) &&
                !DENY.containsMatchIn(action) &&
                action.contains("disconnect", ignoreCase = true) &&
                PROFILE.containsMatchIn(action)
        }

    fun strings(manifest: ByteArray): List<String> {
        val poolAt = findPool(manifest) ?: return emptyList()
        return readPool(manifest, poolAt)
    }

    private const val POOL = 0x0001
    private const val XML = 0x0003
    private const val START = 0x0102
    private const val END = 0x0103

    private fun findPool(manifest: ByteArray): Int? {
        if (manifest.size >= 28 && u16(manifest, 0) == POOL) return 0
        if (manifest.size < 8 || u16(manifest, 0) != XML) return null
        val header = u16(manifest, 2)
        if (header + 8 <= manifest.size && u16(manifest, header) == POOL) return header
        return null
    }

    private fun readPool(bytes: ByteArray, base: Int): List<String> {
        if (base + 28 > bytes.size || u16(bytes, base) != POOL) return emptyList()
        val headerSize = u16(bytes, base + 2)
        if (headerSize < 28 || base + headerSize > bytes.size) return emptyList()
        val stringCount = u32(bytes, base + 8)
        if (stringCount !in 0..20_000) return emptyList()
        val utf8 = u32(bytes, base + 16) and 1 != 0
        val stringsStart = u32(bytes, base + 20)
        if (stringsStart < 0) return emptyList()
        return (0 until stringCount).mapNotNull { index ->
            val table = base + headerSize + index * 4
            if (table + 4 > bytes.size) return emptyList()
            val at = base + stringsStart + u32(bytes, table)
            if (at !in 0 until bytes.size) return@mapNotNull null
            if (utf8) utf8(bytes, at) else utf16(bytes, at)
        }
    }

    private fun elementName(bytes: ByteArray, chunk: Int, headerSize: Int, pool: List<String>): String {
        val ext = chunk + headerSize.coerceAtLeast(16)
        if (ext + 8 > bytes.size) return ""
        return pool.getOrNull(u32(bytes, ext + 4)).orEmpty()
    }

    private fun attribute(bytes: ByteArray, chunk: Int, headerSize: Int, pool: List<String>, want: String): String? {
        val ext = chunk + headerSize.coerceAtLeast(16)
        if (ext + 20 > bytes.size) return null
        val attrStart = u16(bytes, ext + 8)
        val attrSize = u16(bytes, ext + 10)
        val attrCount = u16(bytes, ext + 12)
        if (attrSize < 20 || attrCount !in 0..32) return null
        var at = ext + attrStart
        repeat(attrCount) {
            if (at + 20 > bytes.size) return null
            val name = pool.getOrNull(u32(bytes, at + 4)).orEmpty()
            if (name == want || name.endsWith(":$want")) {
                val raw = u32(bytes, at + 8)
                val dataType = bytes[at + 15].toInt() and 0xff
                val data = u32(bytes, at + 16)
                val index = when {
                    raw >= 0 -> raw
                    dataType == 3 && data >= 0 -> data
                    else -> -1
                }
                return pool.getOrNull(index)
            }
            at += attrSize
        }
        return null
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
