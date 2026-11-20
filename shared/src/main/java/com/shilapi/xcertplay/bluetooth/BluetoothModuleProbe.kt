package com.shilapi.xcertplay.bluetooth

import java.io.File

/**
 * Read-only names for the head unit's hands-free module.
 *
 * Callers may stat a device node and read text files. They must not open a serial port or send
 * a command. Addresses and long values are recorded as present or absent.
 */
internal object BluetoothModuleProbe {
    private val prefixes = listOf(
        "ttys", "ttymt", "ttyhs", "ttyusb", "ttyacm", "ttygs", "ttyxr", "ttyama",
        "ttywch", "ttyhsl", "ttybt", "rfcomm", "hci",
    )
    private val controlWords = listOf(
        "hfp", "handsfree", "hands-free", "a2dp", "sco", "uart", "rf210", "wq",
        "disconnect", "profile", "phone", "call", "audio", "module", "serial",
    )
    private val mac = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val property = Regex("""\[([^\[\]]+)]: \[([^\[\]]*)]""")

    data class NodeAccess(
        val node: String,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean,
    )

    fun isSerialNode(name: String): Boolean {
        val normalized = name.lowercase()
        if (normalized == "bt" || normalized.startsWith("bt")) return true
        return prefixes.any { normalized.startsWith(it) }
    }

    fun looksLikeControl(text: String): Boolean {
        val normalized = text.lowercase()
        return controlWords.any { it in normalized }
    }

    fun vendorPackage(packageName: String): Boolean {
        val name = packageName.lowercase()
        return name == "android.car.service.bt" ||
            name == "android.car.app.bt" ||
            "bluetooth" in name ||
            ".bt" in name ||
            name.endsWith(".bt") ||
            name.startsWith("bt.") ||
            "xtrons" in name && "bt" in name
    }

    fun listNodes(dev: File): List<NodeAccess>? {
        val files = dev.listFiles() ?: return null
        return files.map { it.name }.filter { isSerialNode(it) }.sorted().map { name ->
            val file = File(dev, name)
            NodeAccess(
                node = name,
                exists = file.exists(),
                readable = runCatching { file.canRead() }.getOrDefault(false),
                writable = runCatching { file.canWrite() }.getOrDefault(false),
            )
        }
    }

    /** Writable nodes are always included. The rest are capped. */
    fun uartLines(nodes: List<NodeAccess>?, listed: Boolean): List<String> {
        if (!listed || nodes == null) return listOf("Bluetooth module: uart dir=/dev unreadable")
        val writable = nodes.filter { it.writable }
        val shown = (writable + nodes.filter { !it.writable }).distinctBy { it.node }.take(24)
        val lines = mutableListOf(
            "Bluetooth module: uart count=${nodes.size} writable=${writable.size} readable=${nodes.count { it.readable }}",
        )
        shown.forEach { node ->
            lines += "Bluetooth module: uart node=${node.node} exists=${node.exists} readable=${node.readable} writable=${node.writable}"
        }
        val hidden = nodes.size - shown.size
        if (hidden > 0) lines += "Bluetooth module: uart more=$hidden"
        return lines
    }

    fun driverLines(text: String?): List<String> {
        if (text.isNullOrBlank()) return listOf("Bluetooth module: tty-drivers=absent")
        val hits = text.lineSequence().map { it.trim() }.filter { line ->
            val normalized = line.lowercase()
            "serial" in normalized || "uart" in normalized || "bluetooth" in normalized ||
                "hci" in normalized || "rfcomm" in normalized
        }.take(8).toList()
        if (hits.isEmpty()) return listOf("Bluetooth module: tty-drivers=none")
        return hits.map { "Bluetooth module: tty-driver ${it.take(180)}" }
    }

    fun propertyInterest(key: String): Boolean {
        val normalized = key.lowercase()
        return "bluetooth" in normalized || ".bt" in normalized || normalized.startsWith("bt.") ||
            "hfp" in normalized || "a2dp" in normalized || "handsfree" in normalized ||
            "rf210" in normalized || "uart" in normalized || "sco" in normalized ||
            "bdaddr" in normalized || "address" in normalized
    }

    fun propertyLines(text: String?): List<String> {
        if (text.isNullOrBlank()) return listOf("Bluetooth module: properties=unreadable")
        val found = property.findAll(text).map { it.groupValues[1] to it.groupValues[2] }
            .filter { (key, _) -> propertyInterest(key) }
            .sortedBy { (key, _) -> if (looksLikeControl(key)) 0 else 1 }
            .take(40)
            .toList()
        if (found.isEmpty()) return listOf("Bluetooth module: properties=none")
        return found.map { (key, value) -> "Bluetooth module: property key=$key value=${displayValue(key, value)}" }
    }

    fun displayValue(key: String, value: String?): String {
        if (value.isNullOrBlank()) return "absent"
        val normalized = key.lowercase()
        if ("addr" in normalized || "address" in normalized || "password" in normalized || "secret" in normalized) {
            return "present"
        }
        if (value.length > 48 || '\n' in value || '\r' in value || mac.containsMatchIn(value)) return "present"
        return value.trim().replace(Regex("\\s+"), " ")
    }

    fun componentLine(pkg: String, kind: String, className: String, exported: Boolean, actions: List<String>): String {
        val control = looksLikeControl(className) || actions.any { looksLikeControl(it) }
        val actionText = actions.take(4).joinToString("|").ifEmpty { "none" }
        return "Bluetooth module: component pkg=$pkg kind=$kind class=${className.substringAfterLast('.')} " +
            "exported=$exported control=$control actions=$actionText"
    }
}
