package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
 * Hands-free (HFP) and music (A2DP) are disconnected when the profile services answer. On a head
 * unit where those services never connect, the Bluetooth radio is turned off for the rest of the
 * session and turned back on before the next RFCOMM bootstrap. An app-owned RFCOMM socket is not
 * closed here, and the bond is not removed.
 */
object BluetoothAudioGate {
    private const val TAG = "DiPlay-BtAudio"
    private const val PREFS = "diplay_bluetooth_audio"
    private const val JOURNAL = "journal"
    private const val KEY_RADIO = "radio_disabled_by_us"
    private const val KEY_PROFILES = "profiles_unavailable"
    private const val PROXY_TIMEOUT_MILLIS = 2_000L
    private const val RESTORE_WAIT_MILLIS = 8_000L
    private const val MAX_RECONNECTS = 6
    private val CAR_BT_PACKAGES = listOf("android.car.service.bt", "android.car.app.bt")

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-bt-audio").apply { isDaemon = true }
    }
    private val later = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-bt-audio-watch").apply { isDaemon = true }
    }
    private val actuator = BluetoothAudioActuator(::publish)
    private val reconnects = AtomicInteger()
    private val radioRestarts = AtomicInteger()
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
    private var iosDeviceId: String? = null
    private var holding = false
    private var stacksLogged = false
    private var carBtLogged = false
    private var receiverRegistered = false
    private var appContext: Context? = null
    private var reportLog: (String) -> Unit = {}

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON && holding) worker.execute { reclaimRadio() }
                return
            }
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

    /** iOS asked this accessory to drop Bluetooth. The id is kept for the disconnect and never logged. */
    fun noteIosDisableBluetooth(deviceId: String?) {
        worker.execute {
            iosDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() }
            publish(
                "Bluetooth audio: iOS disableBluetooth received target=" +
                    if (iosDeviceId == null) "unknown" else "carplay-phone",
            )
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
            "session=${if (session) "up" else "down"} " +
            "radio=${if (radioOff(context)) "off-by-diplay" else "unchanged"}"
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
        if (prefs(context).getBoolean(KEY_PROFILES, false)) {
            holding = true
            register(context)
            publish("Bluetooth audio: profile services skipped reason=unavailable-last-session")
            silenceRadio(context, profilesUnreadable = true)
            return
        }
        val remember = !holding
        var profilesUnreadable = false
        val saved = withClients(context) { clients ->
            profilesUnreadable = clients.isEmpty() || clients.all { it.unavailable != null }
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
        if (saved.isEmpty() && profilesUnreadable) {
            prefs(context).edit().putBoolean(KEY_PROFILES, true).commit()
            silenceRadio(context, profilesUnreadable = true)
        } else if (saved.isEmpty()) {
            silenceRadio(context, profilesUnreadable = false)
        } else {
            prefs(context).edit().remove(KEY_PROFILES).commit()
        }
    }

    private fun restore() {
        val context = appContext ?: return
        restoreRadio(context)
        val saved = remembered(context)
        if (saved.isEmpty()) {
            if (holding) {
                holding = false
                stacksLogged = false
                carBtLogged = false
                reconnects.set(0)
                radioRestarts.set(0)
                unregister(context)
                publish("Bluetooth audio: factory profiles restored")
            }
            return
        }
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
        stacksLogged = false
        carBtLogged = false
        reconnects.set(0)
        radioRestarts.set(0)
        unregister(context)
        FactoryBluetoothSnapshot.log(context, reportLog)
    }

    /**
     * Profile services did not change any link. Ask the adapter to disconnect the iPhone, and if
     * the link is still up (or the adapter will not say), turn the radio off until CarPlay ends.
     */
    private fun silenceRadio(context: Context, profilesUnreadable: Boolean) {
        logStacks(context)
        logCarBluetooth(context)
        val adapter = adapter(context) ?: run {
            publish("Bluetooth audio: radio disable result=false reason=adapter-missing")
            return
        }
        val before = connectionState(adapter)
        publish(
            "Bluetooth audio: link connectionState=${BluetoothLinkPlan.stateName(before)} " +
                "profiles=${if (profilesUnreadable) "unreadable" else "answered"} " +
                "target rfcomm=${describeAddress(adapter, phone)} ios=${describeAddress(adapter, iosDeviceId)}",
        )
        val device = phoneDevice(adapter)
        val reflection = BluetoothLinkMethods.disconnect(bluetoothService(adapter), device)
        publish("Bluetooth audio: link drop method=reflection result=$reflection")
        val after = connectionState(adapter)
        if (!BluetoothLinkPlan.powerOffRadio(after, profilesUnreadable)) {
            publish("Bluetooth audio: link dropped connectionState=disconnected")
            clearRadio(context)
            return
        }
        if (profilesUnreadable && after == BluetoothLinkPlan.DISCONNECTED) {
            publish("Bluetooth audio: adapter state ignored reason=profiles-unavailable")
        }
        val disabled = runCatching { adapter.disable() }.getOrDefault(false)
        if (disabled) markRadio(context)
        publish(
            "Bluetooth audio: radio disable result=$disabled " +
                "connectionState=${BluetoothLinkPlan.stateName(connectionState(adapter))} " +
                "reason=profiles-unavailable",
        )
        if (disabled) {
            later.schedule({ worker.execute { watchRadio() } }, 3, TimeUnit.SECONDS)
            later.schedule({ worker.execute { watchRadio() } }, 8, TimeUnit.SECONDS)
        }
    }

    private fun reclaimRadio() {
        val context = appContext ?: return
        if (!session || !radioOff(context)) return
        if (radioRestarts.incrementAndGet() > MAX_RECONNECTS) {
            publish("Bluetooth audio: radio came back on; stopped retrying after $MAX_RECONNECTS")
            return
        }
        val adapter = adapter(context) ?: return
        val again = runCatching { adapter.disable() }.getOrDefault(false)
        publish("Bluetooth audio: radio came back on; disable again result=$again")
    }

    private fun watchRadio() {
        val context = appContext ?: return
        if (!session || !radioOff(context)) return
        val adapter = adapter(context) ?: return
        val enabled = runCatching { adapter.isEnabled }.getOrNull()
        if (enabled == true) {
            val again = runCatching { adapter.disable() }.getOrDefault(false)
            publish("Bluetooth audio: radio came back on; disable again result=$again")
        } else {
            publish("Bluetooth audio: radio stayed off")
        }
    }

    private fun restoreRadio(context: Context) {
        if (!radioOff(context)) return
        val adapter = adapter(context) ?: run {
            publish("Bluetooth audio: radio restore result=false reason=adapter-missing")
            return
        }
        val enabled = runCatching { adapter.enable() }.getOrDefault(false)
        if (enabled) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline && runCatching { adapter.isEnabled }.getOrNull() != true) {
                runCatching { Thread.sleep(100) }
            }
        }
        val on = runCatching { adapter.isEnabled }.getOrNull() == true
        if (on) clearRadio(context)
        publish("Bluetooth audio: radio restore result=$enabled enabled=$on")
    }

    private fun adapter(context: Context): BluetoothAdapter? = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }.getOrNull()

    private fun connectionState(adapter: BluetoothAdapter): Int? {
        val method = adapter.javaClass.methods.firstOrNull { it.name == "getConnectionState" && it.parameterTypes.isEmpty() }
            ?: return null
        return runCatching { method.invoke(adapter) as? Int }.getOrNull()
    }

    private fun bluetoothService(adapter: BluetoothAdapter): Any? {
        val method = adapter.javaClass.methods.firstOrNull {
            (it.name == "getBluetoothService" || it.name == "getService") && it.parameterTypes.isEmpty()
        }
        if (method != null) return runCatching { method.invoke(adapter) }.getOrNull()
        val field = adapter.javaClass.declaredFields.firstOrNull { it.name == "mService" } ?: return null
        field.isAccessible = true
        return runCatching { field.get(adapter) }.getOrNull()
    }

    private fun phoneDevice(adapter: BluetoothAdapter): BluetoothDevice? =
        deviceFor(adapter, phone)
            ?: deviceFor(adapter, iosDeviceId)
            ?: runCatching { adapter.bondedDevices }.getOrNull()?.firstOrNull { connected(it) }

    private fun deviceFor(adapter: BluetoothAdapter, address: String?): BluetoothDevice? {
        if (address.isNullOrBlank()) return null
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    private fun describeAddress(adapter: BluetoothAdapter, address: String?): String = when {
        address.isNullOrBlank() -> "absent"
        deviceFor(adapter, address) != null -> "usable"
        else -> "unreadable"
    }

    private fun connected(device: BluetoothDevice): Boolean {
        val method = device.javaClass.methods.firstOrNull { it.name == "isConnected" && it.parameterTypes.isEmpty() }
            ?: return false
        return runCatching { method.invoke(device) as? Boolean }.getOrNull() == true
    }

    private fun logStacks(context: Context) {
        if (stacksLogged) return
        stacksLogged = true
        val names = runCatching {
            context.packageManager.getInstalledApplications(0).map { it.packageName }.filter { name ->
                val normalized = name.lowercase()
                normalized.contains("bluetooth") || normalized.startsWith("com.syu.") ||
                    normalized.contains(".bt") || normalized.contains("nwd")
            }.sorted().take(12)
        }.getOrDefault(emptyList())
        publish("Bluetooth audio: stacks=${if (names.isEmpty()) "none" else names.joinToString(",")}")
    }

    /**
     * Names exported pieces of the head unit's car Bluetooth packages. Binding those services needs
     * a privileged car permission this app does not have, so this only records what is visible.
     */
    private fun logCarBluetooth(context: Context) {
        if (stacksLogged && carBtLogged) return
        carBtLogged = true
        val pm = context.packageManager
        val summary = CAR_BT_PACKAGES.joinToString(" ") { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS) }
                .getOrNull()
            if (info == null) return@joinToString "$pkg=missing"
            val services = info.services?.filter { it.exported }?.map { it.name.substringAfterLast('.') }?.take(4).orEmpty()
            val receivers = info.receivers?.filter { it.exported }?.map { it.name.substringAfterLast('.') }?.take(4).orEmpty()
            "$pkg services=${services.ifEmpty { listOf("none") }.joinToString("|")} " +
                "receivers=${receivers.ifEmpty { listOf("none") }.joinToString("|")}"
        }
        publish("Bluetooth audio: car-bt $summary")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun radioOff(context: Context) = prefs(context).getBoolean(KEY_RADIO, false)

    private fun markRadio(context: Context) {
        prefs(context).edit().putBoolean(KEY_RADIO, true).commit()
    }

    private fun clearRadio(context: Context) {
        prefs(context).edit().remove(KEY_RADIO).commit()
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
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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
