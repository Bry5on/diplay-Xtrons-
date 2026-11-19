package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.network.XtronsIxHeadUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps factory Bluetooth audio off the CarPlay iPhone once wireless startup no longer needs it.
 *
 * Hands-free (HFP) and music (A2DP) are disconnected and, when the platform allows, marked so they
 * do not reconnect until CarPlay ends. The Bluetooth radio stays on. An app-owned RFCOMM socket is
 * not closed here. If a profile API is missing or rejected, this logs that and leaves the link up.
 */
object BluetoothAudioGate {
    private const val TAG = "DiPlay-BtAudio"
    private const val PREFS = "diplay_bluetooth_audio"
    private const val JOURNAL = "journal"
    private const val PROXY_TIMEOUT_MILLIS = 2_000L
    private const val RESTORE_WAIT_MILLIS = 4_000L
    private const val MAX_RECONNECTS = 6

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-bt-audio").apply { isDaemon = true }
    }
    private val actuator = BluetoothAudioActuator(::publish)
    private val reconnects = AtomicInteger()
    private val profiles = listOf(
        BluetoothAudioPolicy.Profile.HFP,
        BluetoothAudioPolicy.Profile.A2DP,
        BluetoothAudioPolicy.Profile.A2DP_SINK,
        BluetoothAudioPolicy.Profile.HFP_CLIENT,
    )

    private var session = false
    private var wireless = false
    private var bootstrapReleased = false
    private var phone: String? = null
    private var holding = false
    private var receiverRegistered = false
    private var appContext: Context? = null
    private var reportLog: (String) -> Unit = {}

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
            if (state != BluetoothProfile.STATE_CONNECTED || !holding) return
            if (reconnects.incrementAndGet() > MAX_RECONNECTS) {
                publish("Bluetooth audio: profile reconnected; stopped retrying after $MAX_RECONNECTS")
                return
            }
            publish("Bluetooth audio: profile reconnected; disconnecting again")
            worker.execute { drop() }
        }
    }

    /** Puts a saved policy back before the next RFCOMM bootstrap. Returns when that attempt finishes. */
    fun beforeBootstrap(context: Context, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        val task = worker.submit {
            appContext = app
            reportLog = log
            bootstrapReleased = false
            restore()
        }
        runCatching { task.get(RESTORE_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
            .onFailure { publish("Bluetooth audio: restore before bootstrap timed out") }
    }

    fun onSessionActive(context: Context, wireless: Boolean, phoneAddress: String?, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            appContext = app
            reportLog = log
            session = true
            this.wireless = wireless
            phone = phoneAddress?.trim()?.takeIf { it.isNotEmpty() }
            if (!wireless) bootstrapReleased = true
            drop()
        }
    }

    /** Wi-Fi now owns iAP2, so the RFCOMM bootstrap socket has been released. */
    fun bootstrapReleased(context: Context, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            appContext = app
            reportLog = log
            bootstrapReleased = true
            drop()
        }
    }

    fun bootstrapHeld() {
        worker.execute { bootstrapReleased = false }
    }

    fun onSessionEnded(context: Context, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            appContext = app
            reportLog = log
            session = false
            restore()
        }
    }

    /** A previous run may have forbidden audio profiles and then been killed. */
    fun recoverAbandoned(context: Context) {
        val app = context.applicationContext
        worker.execute {
            if (session) return@execute
            appContext = app
            restore()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        BluetoothAudioPreference.setEnabled(context, enabled)
        val app = context.applicationContext
        worker.execute {
            appContext = app
            if (enabled) drop() else restore()
        }
    }

    fun report(context: Context): String {
        val on = BluetoothAudioPreference.enabled(context)
        return "Bluetooth audio: setting=${if (on) "on" else "off"} " +
            "choice=${BluetoothAudioPreference.choice(context)} " +
            "xtronsIx=${XtronsIxHeadUnit.detected} holding=$holding " +
            "bootstrap=${if (bootstrapReleased) "released" else "held"} " +
            "session=${if (session) "up" else "down"}"
    }

    private fun drop() {
        val context = appContext ?: return
        val enabled = BluetoothAudioPreference.enabled(context)
        if (!BluetoothAudioPolicy.shouldIsolate(enabled, session, bootstrapReleased)) {
            if (enabled && session && !bootstrapReleased) {
                publish("Bluetooth audio: waiting until the RFCOMM bootstrap is released")
            }
            return
        }
        val remember = !holding
        val saved = withClients(context) { clients ->
            val connected = clients.flatMap { runCatching { it.connected() }.getOrDefault(emptySet()) }.toSet()
            val addresses = BluetoothAudioPolicy.targets(phone, connected)
            clients.flatMap { client ->
                runCatching { actuator.drop(client, addresses, remember) }.getOrElse { error ->
                    publish("Bluetooth audio: profile=${client.label} fail-safe reason=${error.javaClass.simpleName}")
                    emptyList()
                }
            }
        }
        if (remember) remember(context, saved)
        holding = true
        register(context)
        FactoryBluetoothSnapshot.log(context, reportLog)
        publish("Bluetooth audio: drop requested holding=true remembered=${saved.size}")
    }

    private fun restore() {
        val context = appContext ?: return
        val saved = remembered(context)
        if (saved.isEmpty() && !holding) return
        val ok = withClients(context) { clients ->
            if (saved.isEmpty()) true
            else clients.all { client ->
                runCatching { actuator.restore(client, saved) }.getOrDefault(false)
            }
        }
        if (ok) {
            clearJournal(context)
            publish("Bluetooth audio: factory profiles restored")
        } else {
            publish("Bluetooth audio: restore incomplete; journal kept")
        }
        holding = false
        reconnects.set(0)
        unregister(context)
        FactoryBluetoothSnapshot.log(context, reportLog)
    }

    private fun remember(context: Context, entries: List<BluetoothAudioPolicy.SavedPolicy>) {
        if (entries.isEmpty()) return
        val merged = (remembered(context) + entries).distinctBy { it.profile + "|" + it.address }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(JOURNAL, BluetoothAudioPolicy.encode(merged))
            .commit()
    }

    private fun remembered(context: Context): List<BluetoothAudioPolicy.SavedPolicy> =
        BluetoothAudioPolicy.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(JOURNAL, "").orEmpty(),
        )

    private fun clearJournal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(JOURNAL).commit()
    }

    private fun <T> withClients(context: Context, block: (List<AudioProfileClient>) -> T): T {
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()
        if (adapter == null || runCatching { adapter.isEnabled }.getOrNull() != true) {
            publish("Bluetooth audio: adapter unavailable action=none")
            return block(emptyList())
        }
        val opened = open(context, adapter)
        return try {
            block(opened.map { it.client })
        } finally {
            opened.forEach { slot ->
                slot.proxy?.let { proxy -> runCatching { adapter.closeProfileProxy(slot.id, proxy) } }
            }
        }
    }

    private fun open(context: Context, adapter: BluetoothAdapter): List<Slot> {
        val latch = CountDownLatch(profiles.size)
        val slots = ConcurrentHashMap<Int, Slot>()
        for (profile in profiles) {
            val opened = AtomicBoolean(false)
            val accepted = try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                        if (!opened.compareAndSet(false, true)) return
                        slots[profileId] = Slot(profileId, proxy, ProxyClient(profile.label, proxy, null, adapter))
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(profileId: Int) {
                        if (opened.compareAndSet(false, true)) latch.countDown()
                    }
                }, profile.id)
            } catch (error: SecurityException) {
                slots[profile.id] = failed(profile, "security")
                false
            } catch (error: RuntimeException) {
                slots[profile.id] = failed(profile, error.javaClass.simpleName)
                false
            }
            if (!accepted) {
                slots.putIfAbsent(profile.id, failed(profile, "proxy-refused"))
                if (opened.compareAndSet(false, true)) latch.countDown()
            }
        }
        runCatching { latch.await(PROXY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        return profiles.map { profile -> slots[profile.id] ?: failed(profile, "timeout") }
    }

    private fun failed(profile: BluetoothAudioPolicy.Profile, reason: String) = Slot(
        profile.id,
        null,
        ProxyClient(profile.label, null, reason, null),
    )

    private fun register(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
        receiverRegistered = registered.isSuccess
        if (!receiverRegistered) publish("Bluetooth audio: reconnect watch unavailable")
    }

    private fun unregister(context: Context) {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun publish(message: String) {
        Log.i(TAG, message)
        runCatching { reportLog(message) }
    }

    private data class Slot(val id: Int, val proxy: BluetoothProfile?, val client: AudioProfileClient)

    private class ProxyClient(
        override val label: String,
        private val proxy: Any?,
        override val unavailable: String?,
        private val adapter: BluetoothAdapter?,
    ) : AudioProfileClient {
        override fun connected(): Set<String> = devices().mapNotNull { it.address }.toSet()

        override fun policy(address: String): Int? {
            val proxy = proxy ?: return null
            val device = device(address) ?: return null
            return BluetoothProfileMethods.readPolicy(proxy, device)
        }

        override fun write(address: String, policy: Int): ProfileWrite {
            val proxy = proxy ?: return ProfileWrite.ABSENT
            val device = device(address) ?: return ProfileWrite.FAILED
            return BluetoothProfileMethods.writePolicy(proxy, device, policy)
        }

        override fun disconnect(address: String): ProfileDisconnect {
            val proxy = proxy ?: return ProfileDisconnect.ABSENT
            val device = device(address) ?: return ProfileDisconnect.FAILED
            return BluetoothProfileMethods.disconnect(proxy, device)
        }

        private fun device(address: String): BluetoothDevice? =
            devices().firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: adapter?.let { radio -> runCatching { radio.getRemoteDevice(address) }.getOrNull() }

        private fun devices(): List<BluetoothDevice> {
            val proxy = proxy ?: return emptyList()
            val method = proxy.javaClass.methods.firstOrNull {
                it.name == "getConnectedDevices" && it.parameterTypes.isEmpty()
            } ?: return emptyList()
            val list = runCatching { method.invoke(proxy) as? List<*> }.getOrNull() ?: return emptyList()
            return list.mapNotNull { it as? BluetoothDevice }
        }
    }
}
