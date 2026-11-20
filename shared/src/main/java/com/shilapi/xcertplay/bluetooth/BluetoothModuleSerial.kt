package com.shilapi.xcertplay.bluetooth

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the three writable GOC nodes and, only after one of them speaks the command set,
 * can send the hands-free disconnect or reconnect. Termios is never changed.
 */
internal object BluetoothModuleSerial {
    private const val MAX_PER_SECOND = 4
    private val running = AtomicBoolean(false)
    private val commandsAllowed = AtomicBoolean(false)
    private val disconnectWanted = AtomicBoolean(false)
    private var reader: Thread? = null
    private var log: (String) -> Unit = {}

    fun start(disconnect: Boolean, publish: (String) -> Unit) {
        log = publish
        disconnectWanted.set(disconnect)
        if (!running.compareAndSet(false, true)) return
        disconnectSends.set(0)
        restored.set(false)
        command.set(null)
        lastNodeConfirmed = false
        val thread = Thread(::loop, "diplay-goc-serial").apply { isDaemon = true }
        reader = thread
        thread.start()
    }

    fun allowCommands(ready: Boolean) {
        commandsAllowed.set(ready)
    }

    fun setDisconnectWanted(wanted: Boolean) {
        disconnectWanted.set(wanted)
    }

    fun restore(force: Boolean) {
        disconnectWanted.set(false)
        if (!force && disconnectSends.get() == 0) {
            publish("Bluetooth module serial: restore skipped reason=not-disconnected")
            return
        }
        if (running.get()) {
            command.set(GocHandsfree.RECONNECT)
            return
        }
        val node = lastNode
        if (node == null) {
            publish("Bluetooth module serial: restore skipped reason=no-command-channel")
            return
        }
        if (!lastNodeConfirmed) {
            publish("Bluetooth module serial: restore skipped reason=status-only")
            return
        }
        writeCommand(node, GocHandsfree.RECONNECT, "restore")
        writeCommand(node, GocHandsfree.QUERY, "query")
    }

    /** CarPlay ended. Says so when that was within 10 seconds of AT#CD. */
    fun noteSessionEnded() {
        val at = lastDisconnectAt.get()
        if (at <= 0L) return
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at)
        if (elapsed in 0..10_000) {
            publish("Bluetooth module serial: session dropped after disconnect elapsedMs=$elapsed")
        }
    }

    fun stop() {
        running.set(false)
        reader?.join(2_500)
        reader = null
        commandsAllowed.set(false)
        command.set(null)
    }

    private val command = AtomicReference<String?>(null)
    private val disconnectSends = AtomicInteger()
    private val lastDisconnectAt = AtomicLong()
    private val restored = AtomicBoolean(false)
    @Volatile private var lastNode: String? = null
    @Volatile private var lastNodeConfirmed = false

    private fun loop() {
        val opens = GocHandsfree.NODES.map { path -> openRead(path) }
        val openFds = opens.mapNotNull { it.second }
        if (openFds.isEmpty()) {
            running.set(false)
            return
        }
        val buffers = openFds.associateWith { ArrayDeque<Byte>() }
        val windows = mutableMapOf<String, Long>()
        val counts = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, Int>()
        var identified: String? = null
        var writeAllowed = false
        val cycle = GocHandsfree.Cycle()
        var disconnectSentAt = 0L
        var replyLogged = false
        var writeFailures = 0
        var commandSkipLogged = false
        var statusOnlyLogged = false
        var deferLogged = false
        try {
            while (running.get()) {
                val polls = openFds.map { fd ->
                    StructPollfd().apply {
                        this.fd = fd.fd
                        events = OsConstants.POLLIN.toShort()
                    }
                }.toTypedArray()
                runCatching { Os.poll(polls, 200) }
                openFds.forEachIndexed { index, opened ->
                    if (polls[index].revents.toInt() and OsConstants.POLLIN == 0) return@forEachIndexed
                    val bytes = readSome(opened.fd) ?: return@forEachIndexed
                    val queue = buffers.getValue(opened)
                    bytes.forEach { queue.add(it) }
                    while (queue.size >= 48 || queue.any { it == 10.toByte() || it == 13.toByte() }) {
                        val chunk = takeChunk(queue)
                        if (chunk.isEmpty()) break
                        val sample = String(chunk, Charsets.ISO_8859_1)
                        val match = GocHandsfree.channelMatch(sample)
                        if (match != null) {
                            val upgrade = match.allowsWrite && !writeAllowed && cycle.sends == 0
                            if (identified == null || upgrade) {
                                identified = opened.node
                                lastNode = opened.node
                                writeAllowed = match.allowsWrite
                                if (match.allowsWrite) lastNodeConfirmed = true
                                publish(
                                    "Bluetooth module serial: command-node=${opened.node} " +
                                        "reason=${match.reason} token=${match.token}",
                                )
                            }
                        }
                        if (opened.node == identified) {
                            if (cycle.sends > 0 && !replyLogged && GocHandsfree.hfpDown(sample)) {
                                publish("Bluetooth module serial: hfp-disconnect reply=down")
                                replyLogged = true
                            }
                            when (cycle.note(sample)) {
                                "again" -> command.compareAndSet(null, GocHandsfree.DISCONNECT)
                                "stop" -> publish("Bluetooth module serial: hfp-disconnect stopped reason=reconnected")
                            }
                        }
                        logChunk(opened.node, chunk, windows, counts, skipped)
                    }
                }
                if (disconnectWanted.get() && commandsAllowed.get() && identified != null && !writeAllowed &&
                    cycle.sends == 0 && !statusOnlyLogged
                ) {
                    publish("Bluetooth module serial: hfp-disconnect skipped reason=status-only")
                    statusOnlyLogged = true
                }
                if (disconnectWanted.get() && writeAllowed && cycle.sends == 0 && !commandsAllowed.get() && !deferLogged) {
                    val why = if (BluetoothAudioGate.serialBlockedByRadio()) "radio-cycle" else "session"
                    publish("Bluetooth module serial: hfp-disconnect deferred reason=$why")
                    deferLogged = true
                }
                maybeSend(identified, writeAllowed, cycle, writeFailures, commandSkipLogged) { sentAt, failed, skipped ->
                    if (sentAt > 0L) {
                        disconnectSentAt = sentAt
                        replyLogged = false
                    }
                    writeFailures = failed
                    commandSkipLogged = skipped
                }
                if (cycle.sends > 0 && !replyLogged && disconnectSentAt > 0L &&
                    System.nanoTime() - disconnectSentAt > 2_000_000_000L
                ) {
                    publish("Bluetooth module serial: hfp-disconnect reply=unconfirmed")
                    replyLogged = true
                }
            }
            val node = identified
            if (node != null && writeAllowed && cycle.sends > 0 && !restored.get()) {
                writeCommand(node, GocHandsfree.RECONNECT, "restore")
                writeCommand(node, GocHandsfree.QUERY, "query")
            } else if (cycle.sends == 0 && disconnectWanted.get() && commandsAllowed.get() && !statusOnlyLogged) {
                val reason = GocHandsfree.disconnectSkip(identified != null, writeAllowed)
                publish("Bluetooth module serial: hfp-disconnect skipped reason=$reason")
            }
        } finally {
            openFds.forEach { runCatching { Os.close(it.fd) } }
        }
    }

    private fun maybeSend(
        identified: String?,
        atConfirmed: Boolean,
        cycle: GocHandsfree.Cycle,
        writeFailures: Int,
        skipLogged: Boolean,
        update: (sentAt: Long, failures: Int, skipLogged: Boolean) -> Unit,
    ) {
        val requested = command.get()
        val node = identified
        if (requested == GocHandsfree.RECONNECT && node != null && !atConfirmed) {
            command.compareAndSet(requested, null)
            publish("Bluetooth module serial: restore skipped reason=status-only")
            return
        }
        val restoreNow = requested == GocHandsfree.RECONNECT && node != null && atConfirmed
        if (requested != null && !restoreNow && (!commandsAllowed.get() || node == null)) {
            if (!skipLogged) {
                publish("Bluetooth module serial: write skipped reason=no-command-channel command=${requested.trim()}")
            }
            update(0L, writeFailures, true)
            return
        }
        val next = when {
            requested == GocHandsfree.RECONNECT -> GocHandsfree.RECONNECT
            requested == GocHandsfree.DISCONNECT && atConfirmed && commandsAllowed.get() && !cycle.stopped -> GocHandsfree.DISCONNECT
            disconnectWanted.get() && commandsAllowed.get() && atConfirmed && !cycle.stopped &&
                cycle.sends == 0 && writeFailures < 2 -> GocHandsfree.DISCONNECT
            else -> return
        }
        if (node == null) return
        if (requested != null) command.compareAndSet(requested, null)
        val kind = if (next == GocHandsfree.RECONNECT) "restore" else "disconnect"
        if (!writeCommand(node, next, kind)) {
            update(0L, writeFailures + 1, skipLogged)
            if (writeFailures + 1 >= 2) publish("Bluetooth module serial: hfp-disconnect stopped reason=write-failed")
            return
        }
        if (next == GocHandsfree.DISCONNECT) {
            cycle.sent()
            disconnectSends.incrementAndGet()
            lastDisconnectAt.set(System.nanoTime())
            restored.set(false)
            update(System.nanoTime(), 0, skipLogged)
            writeCommand(node, GocHandsfree.QUERY, "query")
        } else {
            restored.set(true)
            update(0L, writeFailures, skipLogged)
        }
    }

    private fun writeCommand(node: String, command: String, kind: String): Boolean {
        val path = GocHandsfree.NODES.first { GocHandsfree.nodeName(it) == node }
        val fd = try {
            Os.open(path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOCTTY, 0)
        } catch (error: ErrnoException) {
            publish("Bluetooth module serial: write node=$node kind=$kind result=${errnoName(error.errno)} termios=unchanged")
            return false
        }
        return try {
            val bytes = command.toByteArray(Charsets.US_ASCII)
            Os.write(fd, bytes, 0, bytes.size)
            publish(
                "Bluetooth module serial: wrote node=$node kind=$kind command=${command.trim()} " +
                    "bytes=${bytes.size} termios=unchanged",
            )
            true
        } catch (error: ErrnoException) {
            publish("Bluetooth module serial: write node=$node kind=$kind result=${errnoName(error.errno)} termios=unchanged")
            false
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun logChunk(
        node: String,
        chunk: ByteArray,
        windows: MutableMap<String, Long>,
        counts: MutableMap<String, Int>,
        skipped: MutableMap<String, Int>,
    ) {
        val now = System.nanoTime() / 1_000_000L
        val start = windows[node] ?: now
        if (now - start >= 1_000L) {
            windows[node] = now
            counts[node] = 0
        }
        val count = counts.getOrDefault(node, 0)
        if (count >= MAX_PER_SECOND) {
            skipped[node] = skipped.getOrDefault(node, 0) + 1
            return
        }
        counts[node] = count + 1
        val extra = skipped.put(node, 0) ?: 0
        val line = GocHandsfree.serialLine(node, chunk) + if (extra > 0) " skipped=$extra" else ""
        publish(line)
    }

    private fun takeChunk(queue: ArrayDeque<Byte>): ByteArray {
        val out = ArrayList<Byte>(48)
        while (queue.isNotEmpty() && out.size < 48) {
            val value = queue.remove()
            out += value
            if (value == 10.toByte() || value == 13.toByte()) break
        }
        return out.toByteArray()
    }

    private fun readSome(fd: FileDescriptor): ByteArray? {
        val buffer = ByteArray(128)
        val count = try {
            Os.read(fd, buffer, 0, buffer.size)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EAGAIN) return null
            return null
        }
        if (count <= 0) return null
        return buffer.copyOf(count)
    }

    private fun openRead(path: String): Pair<String, Opened?> {
        val node = GocHandsfree.nodeName(path)
        return try {
            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOCTTY, 0)
            publish("Bluetooth module serial: open node=$node mode=read-only nonblock noctty termios=unchanged result=ok")
            node to Opened(node, fd)
        } catch (error: ErrnoException) {
            publish("Bluetooth module serial: open node=$node mode=read-only nonblock noctty termios=unchanged result=${errnoName(error.errno)}")
            node to null
        }
    }

    private fun errnoName(errno: Int): String = when (errno) {
        OsConstants.EACCES -> "access"
        OsConstants.EBUSY -> "busy"
        OsConstants.EPERM -> "perm"
        OsConstants.ENOENT -> "missing"
        else -> "errno-$errno"
    }

    private fun publish(line: String) {
        runCatching { log(line) }
    }

    private data class Opened(val node: String, val fd: FileDescriptor)
}
