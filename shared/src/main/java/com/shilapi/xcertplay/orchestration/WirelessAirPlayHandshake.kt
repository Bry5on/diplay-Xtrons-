package com.shilapi.xcertplay.orchestration

/**
 * Per-generation markers for the AirPlay handshake that can finish before [activeSession].
 *
 * An RFCOMM EOF in that window keeps the Wi-Fi tunnel after this generation has sent
 * CarPlayStartSession (0x4301) and accepted the phone's control TCP connection. SETUP often
 * arrives a few milliseconds after that EOF. TCP before StartSession, or SETUP before that
 * TCP accept, does not count.
 */
internal class WirelessAirPlayHandshake {
    private var generation = -1
    private var startSessionSent = false
    private var tcpAccepted = false
    private var sessionSetup = false

    @Synchronized fun begin(generation: Int) {
        this.generation = generation
        startSessionSent = false
        tcpAccepted = false
        sessionSetup = false
    }

    @Synchronized fun noteStartSession(generation: Int) {
        if (this.generation == generation) startSessionSent = true
    }

    @Synchronized fun noteTcpAccepted(generation: Int) {
        if (this.generation == generation) tcpAccepted = true
    }

    /** The session-level SETUP, not a later streams SETUP. Ignored until this generation's phone TCP. */
    @Synchronized fun noteSessionSetup(generation: Int) {
        if (this.generation == generation && tcpAccepted) sessionSetup = true
    }

    @Synchronized fun reachedSessionSetup(generation: Int): Boolean =
        this.generation == generation && startSessionSent && tcpAccepted && sessionSetup

    /** StartSession was sent and the phone's TCP connection was accepted. SETUP may still be in flight. */
    @Synchronized fun reachedPhoneTcp(generation: Int): Boolean =
        this.generation == generation && startSessionSent && tcpAccepted

    @Synchronized fun clear() {
        generation = -1
        startSessionSent = false
        tcpAccepted = false
        sessionSetup = false
    }
}

/**
 * One generation's "kept the tunnel after SETUP, still waiting for a session" watch.
 * [onDeadline] and [onControlLost] each run the failure path at most once, and a later generation
 * cannot trip a watch armed for an earlier one.
 */
internal class SetupKeepAliveWatch(val generation: Int) {
    private var pending = false

    @Synchronized fun arm() {
        pending = true
    }

    @Synchronized fun cancel(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }

    /** True when the deadline expired with no session and no rendered frame for this generation. */
    @Synchronized fun onDeadline(currentGeneration: Int, sessionActive: Boolean, frameRendered: Boolean): Boolean {
        if (!pending || currentGeneration != generation) return false
        pending = false
        return !sessionActive && !frameRendered
    }

    /** True when the AirPlay control connection closed or failed before a session existed. */
    @Synchronized fun onControlLost(currentGeneration: Int, sessionActive: Boolean): Boolean {
        if (!pending || currentGeneration != generation) return false
        pending = false
        return !sessionActive
    }
}
