package com.shilapi.xcertplay.bluetooth

/**
 * GOC / OVC3860 hands-free commands.
 *
 * Public command notes (OVC3860 AT Command Application Notes, and the matching GOC command list):
 * AT#CD disconnects HFP only and the module answers IA when the link drops, II when it was already
 * down, or ERROR. AT#CC connects HFP to the last handset and answers IV. AT#CY asks for HFP state
 * and answers MG1 ready through MG6 ongoing call.
 *
 * AT#CZ resets the whole module and drops every link, including SPP, so it is never sent.
 * A module reply (MW plus a version, or IA, IV, II, MG1–MG6) names the node that may receive AT#CD.
 * Other status words stay listen-only. The caller still waits until CarPlay is up and the radio is quiet.
 */
internal object GocHandsfree {
    const val DISCONNECT = "AT#CD\r\n"
    const val RECONNECT = "AT#CC\r\n"
    const val QUERY = "AT#CY\r\n"
    val NODES = listOf("/dev/BT_serial", "/dev/ttyBT0", "/dev/ttyS1")

    private val atCommand = Regex("""(?i)AT#[A-Za-z0-9]*""")
    private val replyWord = Regex(
        """(?i)(?:^|[\r\n])\s*(MW[0-9A-Z./]{4,}|IA|IV|II|MG[1-6])\s*(?:$|[\r\n])""",
    )
    private val statusWord = Regex(
        """(?i)(?:^|[\r\n])\s*(IA|IV|II|MG[1-6]|MU[1-5]|SC|SD|IS|MF)\s*(?:$|[\r\n])""",
    )

    data class ChannelMatch(val token: String, val reason: String, val allowsWrite: Boolean)
    private val hfpDown = Regex("""(?i)(?:^|[\r\n\s])(IA|II|MG1)(?=$|[\r\n\s])""")
    private val hfpUp = Regex("""(?i)(?:^|[\r\n\s])(IV|MG[3-6])(?=$|[\r\n\s])""")
    private val labeledSecret = Regex(
        """(?i)(pin|passkey|pair(?:ing)?(?:\s*code)?|link\s*key)\s*[:=]?\s*[0-9A-Za-z]{4,64}""",
    )
    private val linkKey = Regex("""(?i)\b[0-9a-f]{32,}\b""")
    private val longNumber = Regex("""(?<![0-9])[0-9]{7,}(?![0-9])""")
    private val shortCode = Regex("""(?<![0-9A-Za-z])[0-9]{6}(?![0-9])""")

    fun nodeName(path: String): String = path.substringAfterLast('/')

    /** A GOC reply, an AT# command, or a listen-only status word. */
    fun channelMatch(sample: String): ChannelMatch? {
        atCommand.find(sample)?.let {
            return ChannelMatch(it.value.uppercase(), "at-command", allowsWrite = true)
        }
        replyWord.find(sample)?.let { match ->
            val raw = match.groupValues[1].uppercase()
            val token = if (raw.startsWith("MW")) "MW" else raw
            return ChannelMatch(token, "reply", allowsWrite = true)
        }
        statusWord.find(sample)?.let {
            return ChannelMatch(it.groupValues[1].uppercase(), "status", allowsWrite = false)
        }
        return null
    }

    fun isCommandChannel(sample: String): Boolean = channelMatch(sample) != null

    fun hasAtCommand(sample: String): Boolean = atCommand.containsMatchIn(sample)

    /** Null when a disconnect write is allowed. */
    fun disconnectSkip(identified: Boolean, writeAllowed: Boolean): String? = when {
        writeAllowed -> null
        identified -> "status-only"
        else -> "no-command-channel"
    }

    fun hfpDown(sample: String): Boolean = hfpDown.containsMatchIn(sample)

    fun hfpUp(sample: String): Boolean = hfpUp.containsMatchIn(sample)

    fun mask(text: String): String =
        text.replace(labeledSecret) { "${it.groupValues[1]}=[secret]" }
            .replace(linkKey, "[link]")
            .replace(longNumber, "[number]")
            .replace(shortCode, "[code]")

    fun serialLine(node: String, bytes: ByteArray): String {
        val shown = bytes.take(48).toByteArray()
        val text = mask(String(shown, Charsets.ISO_8859_1).replace(Regex("[^\\p{Print}]"), "."))
        val hex = shown.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val grouped = hex.chunked(8).joinToString(".")
        return "Bluetooth module serial: node=$node text=$text bytes=$grouped"
    }

    /**
     * After a disconnect has been sent, one later hands-free return may be disconnected again.
     * A second return stops instead of sending.
     */
    class Cycle {
        var sends: Int = 0
            private set
        private var sawDown = false
        var stopped: Boolean = false
            private set

        fun sent() {
            sends += 1
            sawDown = false
        }

        /** "again" sends one more disconnect. "stop" means the retry was already used. */
        fun note(sample: String): String? {
            if (stopped || sends == 0) return null
            if (hfpDown(sample)) sawDown = true
            if (!sawDown || !hfpUp(sample)) return null
            return if (sends >= 2) {
                stopped = true
                "stop"
            } else {
                "again"
            }
        }
    }
}
