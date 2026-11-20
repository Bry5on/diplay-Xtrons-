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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private const val LINK_CHECK_GRACE_SECONDS = 5L
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

    @Volatile private var session = false
    private var wireless = false
    @Volatile private var bootstrapReleased = false
    private var phone: String? = null
    private var iosDeviceId: String? = null
    private var holding = false
    private var stacksLogged = false
    private var carBtLogged = false
    private var vendorLogged = false
    private var moduleLogged = false
    private var radioCycled = false
    @Volatile private var cycleWaiting = false
    @Volatile private var speechDuringCycle = false
    private var lastReturnCycleAt = -1L
    private var watchMediaUntil = 0L
    private var returnTask: ScheduledFuture<*>? = null
    private val modes = CarPlayModeTracker()
    private var lastModesLine = ""
    @Volatile private var callHold = false
    private val callHoldRetries = AtomicInteger()
    private val linkCheckQuietUntilNanos = AtomicLong()
    private var receiverRegistered = false
    private var appContext: Context? = null
    private var reportLog: (String) -> Unit = {}

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON && holding) worker.execute { onRadioReturned() }
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
            BluetoothModuleSerial.start(
                BluetoothAudioPreference.disconnectHandsfree(app),
                ::publish,
            )
            drop()
            refreshSerialWindow()
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
            refreshSerialWindow()
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
            BluetoothModuleSerial.noteSessionEnded()
            BluetoothModuleSerial.stop()
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

    /**
     * True while DiPlay has turned the radio off, and for a few seconds after it comes back.
     * A connect in that window must not be reported as Bluetooth that can never work.
     */
    /** True while a radio cycle, a speech hold, or a disabled adapter should delay AT#CD. */
    fun serialBlockedByRadio(): Boolean =
        cycleWaiting || speechDuringCycle || callHold || radioAdapterDisabled()

    private fun refreshSerialWindow() {
        val ready = session && bootstrapReleased && !serialBlockedByRadio()
        BluetoothModuleSerial.allowCommands(ready)
    }

    fun bluetoothLinkCheckSuspended(): Boolean {
        if (cycleWaiting || callHold) return true
        val until = linkCheckQuietUntilNanos.get()
        return until > 0L && System.nanoTime() < until
    }

    /** iOS reported phone-call or Siri state. A call that ends may cycle the radio once. */
    fun onModesChanged(params: Map<String, Any?>) {
        worker.execute {
            val event = modes.apply(CarPlayModes.parse(params))
            val line = "Bluetooth audio: modes phoneCall=${modes.phoneLabel()} " +
                "speech=${modes.speechLabel()} busy=${modes.busy} event=$event"
            if (line != lastModesLine) {
                lastModesLine = line
                publish(line)
            }
            when (event) {
                "started" -> {
                    returnTask?.cancel(false)
                    if (holdDuringCalls()) holdRadioForCall()
                    else if (cycleWaiting || radioAdapterDisabled()) holdThroughSpeech()
                }
                "ended" -> {
                    val heldForSpeech = speechDuringCycle
                    speechDuringCycle = false
                    callHold = false
                    if (heldForSpeech) publish("Bluetooth audio: radio hold released reason=speech-ended")
                    refreshSerialWindow()
                    returnTask?.cancel(false)
                    returnTask = later.schedule(
                        { worker.execute { returnMusicAfterCall() } },
                        CarPlayModes.RETURN_DEBOUNCE_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
        }
    }

    /** A CarPlay audio stream opened. Used to see whether music came back after a return cycle. */
    fun noteCarPlayAudioStream(type: Int) {
        if (type != 102) return
        worker.execute {
            if (System.nanoTime() < watchMediaUntil) {
                publish("Bluetooth audio: media returned type=102 after hfp-return")
            }
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

    fun setHandsfreeDisconnect(context: Context, enabled: Boolean) {
        BluetoothAudioPreference.setDisconnectHandsfree(context, enabled)
        BluetoothModuleSerial.setDisconnectWanted(enabled)
        if (!enabled) BluetoothModuleSerial.restore(force = false)
    }

    fun restoreHandsfree() {
        BluetoothModuleSerial.restore(force = true)
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
            logModule(context)
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
        logModule(context)
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
                vendorLogged = false
                moduleLogged = false
                radioCycled = false
                cycleWaiting = false
                lastReturnCycleAt = -1L
                watchMediaUntil = 0L
                returnTask?.cancel(false)
                modes.reset()
                callHold = false
                speechDuringCycle = false
                lastModesLine = ""
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
        vendorLogged = false
        moduleLogged = false
        radioCycled = false
        cycleWaiting = false
        lastReturnCycleAt = -1L
        watchMediaUntil = 0L
        returnTask?.cancel(false)
        modes.reset()
        callHold = false
        speechDuringCycle = false
        lastModesLine = ""
        reconnects.set(0)
        radioRestarts.set(0)
        unregister(context)
        FactoryBluetoothSnapshot.log(context, reportLog)
    }

    /**
     * Profile services did not change any link. Ask the adapter to disconnect the iPhone.
     * When those services never answer, turn the radio off once and let the head unit bring it
     * back. A second off is not sent: that fight did not hold the hands-free link down.
     */
    private fun silenceRadio(context: Context, profilesUnreadable: Boolean) {
        logStacks(context)
        logCarBluetooth(context)
        logIdentity(context)
        val adapter = adapter(context) ?: run {
            publish("Bluetooth audio: radio disable skipped reason=adapter-missing")
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
        if (profilesUnreadable) {
            publish("Bluetooth audio: radio fight skipped reason=profiles-unavailable")
            if (radioOff(context)) clearRadio(context)
            logVendorDisconnect(context, device)
            oneShotRadioCycle(adapter)
            return
        }
        val after = connectionState(adapter)
        if (!BluetoothLinkPlan.powerOffRadio(after, profilesUnreadable = false)) {
            publish("Bluetooth audio: link dropped connectionState=disconnected")
            clearRadio(context)
            return
        }
        val disabled = runCatching { adapter.disable() }.getOrDefault(false)
        if (disabled) markRadio(context)
        publish(
            "Bluetooth audio: radio disable result=$disabled " +
                "connectionState=${BluetoothLinkPlan.stateName(connectionState(adapter))} " +
                "reason=link-still-up",
        )
        if (disabled) {
            later.schedule({ worker.execute { watchRadio() } }, 3, TimeUnit.SECONDS)
            later.schedule({ worker.execute { watchRadio() } }, 8, TimeUnit.SECONDS)
        }
    }

    private fun oneShotRadioCycle(adapter: BluetoothAdapter) {
        if (!BluetoothLinkPlan.oneShotRadioCycle(profilesUnreadable = true)) return
        if (radioCycled) {
            publish("Bluetooth audio: radio cycle skipped reason=already-done")
            return
        }
        radioCycled = true
        performCycle(adapter, reason = "session-start", signal = "session-start")
    }

    private fun returnMusicAfterCall() {
        val context = appContext
        if (context == null || !returnCycleAllowed(context)) {
            publish("Bluetooth audio: hfp-return skipped reason=not-this-head-unit")
            return
        }
        val since = if (lastReturnCycleAt < 0) Long.MAX_VALUE
        else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastReturnCycleAt)
        val skip = CarPlayModes.skipReturn(modes.busy, since, cycleWaiting)
        if (skip == "recent") {
            val wait = CarPlayModes.deferReturnMillis(since) ?: return
            publish("Bluetooth audio: hfp-return deferred reason=recent waitMs=$wait")
            returnTask = later.schedule(
                { worker.execute { returnMusicAfterCall() } },
                wait,
                TimeUnit.MILLISECONDS,
            )
            return
        }
        if (skip != null) {
            publish("Bluetooth audio: hfp-return skipped reason=$skip")
            return
        }
        val adapter = adapter(context) ?: run {
            publish("Bluetooth audio: hfp-return skipped reason=adapter-missing")
            return
        }
        lastReturnCycleAt = System.nanoTime()
        watchMediaUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        publish("Bluetooth audio: hfp-return signal=modesChanged")
        performCycle(adapter, reason = "call-ended", signal = "modesChanged")
    }

    private fun returnCycleAllowed(context: Context): Boolean {
        if (!session || !BluetoothAudioPreference.enabled(context)) return false
        return prefs(context).getBoolean(KEY_PROFILES, false) || XtronsIxHeadUnit.detected
    }

    private fun performCycle(adapter: BluetoothAdapter, reason: String, signal: String) {
        if (cycleWaiting) {
            publish("Bluetooth audio: radio cycle skipped reason=in-progress trigger=$reason")
            return
        }
        val enabledNow = runCatching { adapter.isEnabled }.getOrNull() == true
        val disabled = if (enabledNow) runCatching { adapter.disable() }.getOrDefault(false) else false
        publish(
            "Bluetooth audio: radio cycle off result=${if (enabledNow) disabled.toString() else "already-off"} " +
                "reason=$reason signal=$signal",
        )
        if (!enabledNow || disabled) {
            cycleWaiting = true
            refreshSerialWindow()
            later.schedule({ worker.execute { finishRadioCycle() } }, 8, TimeUnit.SECONDS)
        }
    }

    private fun radioAdapterDisabled(): Boolean {
        val context = appContext ?: return false
        val adapter = adapter(context) ?: return false
        return runCatching { adapter.isEnabled }.getOrNull() == false
    }

    /** Speech started while the radio was already off. Leave it off until speech ends. */
    private fun holdThroughSpeech() {
        if (speechDuringCycle) return
        speechDuringCycle = true
        publish("Bluetooth audio: radio held reason=speech-during-cycle")
        refreshSerialWindow()
    }

    private fun holdDuringCalls(): Boolean {
        val context = appContext ?: return false
        return BluetoothAudioPreference.holdRadioDuringCalls(context) && returnCycleAllowed(context)
    }

    private fun holdRadioForCall() {
        val context = appContext ?: return
        val adapter = adapter(context) ?: return
        callHold = true
        cycleWaiting = false
        callHoldRetries.set(0)
        val disabled = runCatching { adapter.disable() }.getOrDefault(false)
        publish("Bluetooth audio: call-hold off result=$disabled signal=modesChanged")
        refreshSerialWindow()
    }

    private fun quietLinkCheck() {
        linkCheckQuietUntilNanos.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(LINK_CHECK_GRACE_SECONDS))
    }

    private fun onRadioReturned() {
        if ((callHold || speechDuringCycle) && modes.busy) {
            val context = appContext ?: return
            val adapter = adapter(context) ?: return
            if (callHoldRetries.incrementAndGet() > MAX_RECONNECTS) {
                callHold = false
                speechDuringCycle = false
                quietLinkCheck()
                refreshSerialWindow()
                publish("Bluetooth audio: radio held stopped reason=head-unit-keeps-enabling")
                return
            }
            val again = runCatching { adapter.disable() }.getOrDefault(false)
            val why = if (callHold) "call-hold" else "speech-during-cycle"
            publish("Bluetooth audio: $why disable again result=$again")
            return
        }
        if (cycleWaiting) {
            cycleWaiting = false
            quietLinkCheck()
            refreshSerialWindow()
            publish("Bluetooth audio: radio cycle back on")
            publish("Bluetooth audio: radio cycle complete hfp-hold=unavailable")
            return
        }
        quietLinkCheck()
        reclaimRadio()
    }

    private fun finishRadioCycle() {
        if (!cycleWaiting) return
        if ((callHold || speechDuringCycle) && modes.busy) {
            cycleWaiting = false
            val why = if (callHold) "call-hold" else "speech-during-cycle"
            publish("Bluetooth audio: radio cycle paused reason=$why")
            refreshSerialWindow()
            return
        }
        cycleWaiting = false
        quietLinkCheck()
        val context = appContext
        val adapter = context?.let { adapter(it) }
        val on = adapter?.let { runCatching { it.isEnabled }.getOrNull() } == true
        if (on) {
            publish("Bluetooth audio: radio cycle back on")
        } else {
            val enabled = adapter?.let { runCatching { it.enable() }.getOrDefault(false) } ?: false
            publish("Bluetooth audio: radio cycle restore result=$enabled")
        }
        publish("Bluetooth audio: radio cycle complete hfp-hold=unavailable")
        refreshSerialWindow()
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

    /**
     * Read-only look at the hands-free module. Device nodes are only stat'd. Nothing is opened
     * for reading or writing, and no broadcast or setting is sent.
     */
    private fun logModule(context: Context) {
        if (moduleLogged) return
        moduleLogged = true
        BluetoothModuleScan.lines(context).forEach { publish(it) }
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

    private fun logIdentity(context: Context) {
        val radio = adapter(context)
        val adapterAddress = radio?.let { runCatching { it.address }.getOrNull() }
        val settings = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        val found = BluetoothControllerAddress.read(context, adapterAddress, synthetic = "")
        val advertised = found.address.takeIf { it.isNotBlank() }
        publish(
            BluetoothIdentity.line(
                advertised, adapterAddress, settings, BluetoothIdentity.wifiAddress(),
                role = "visible", chosen = found.chosen, localBit = found.localBit,
            ),
        )
        publish("Bluetooth identity: probes ${found.probes} chosen=${found.chosen} localBit=${found.localBit}")
    }

    private fun logVendorDisconnect(context: Context, device: android.bluetooth.BluetoothDevice?) {
        if (vendorLogged) return
        vendorLogged = true
        val enabled = BluetoothAudioPreference.enabled(context)
        for (pkg in CAR_BT_PACKAGES) {
            val info = runCatching {
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                        PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS,
                )
            }.getOrNull()
            if (info == null) {
                publish("Bluetooth audio: vendor pkg=$pkg manifest=missing")
                continue
            }
            val providers = info.providers?.filter { it.exported }?.map { it.authority ?: it.name.substringAfterLast('.') }
                ?.take(4).orEmpty()
            val permissions = info.requestedPermissions?.filter { permission ->
                permission.contains("BLUETOOTH") || permission.contains("CAR")
            }?.map { it.substringAfterLast('.') }?.take(6).orEmpty()
            val manifest = manifestBytes(info.applicationInfo?.sourceDir)
            val parsed = manifest?.let { BluetoothVendorManifest.filters(it) }
                ?: BluetoothVendorManifest.Filters(0, emptyList(), emptyList())
            val actions = (parsed.receivers + parsed.services).distinct()
            val safe = BluetoothVendorManifest.safeDisconnect(actions)
            publish(
                "Bluetooth audio: vendor pkg=$pkg manifest=${if (manifest == null) "unreadable" else "read"} " +
                    "strings=${parsed.stringCount} " +
                    "providers=${providers.ifEmpty { listOf("none") }.joinToString("|")} " +
                    "permissions=${permissions.ifEmpty { listOf("none") }.joinToString("|")}",
            )
            publishActions(pkg, "receivers", parsed.receivers)
            publishActions(pkg, "services", parsed.services)
            publish(
                "Bluetooth audio: vendor disconnect candidates=${safe.size} " +
                    (safe.take(4).joinToString(",") .ifEmpty { "none" }),
            )
            when {
                safe.size != 1 -> if (safe.size > 1) {
                    publish("Bluetooth audio: vendor disconnect skipped reason=ambiguous")
                }
                !enabled -> publish("Bluetooth audio: vendor disconnect skipped reason=setting-off")
                else -> {
                    val result = sendVendorDisconnect(context, pkg, safe.single(), device)
                    publish(
                        "Bluetooth audio: vendor disconnect fired pkg=$pkg " +
                            "action=${safe.single().substringAfterLast('.')} result=$result",
                    )
                }
            }
        }
    }

    private fun publishActions(pkg: String, kind: String, actions: List<String>) {
        if (actions.isEmpty()) {
            publish("Bluetooth audio: vendor actions pkg=$pkg $kind=none")
            return
        }
        actions.chunked(3).forEach { chunk ->
            publish("Bluetooth audio: vendor actions pkg=$pkg $kind=${chunk.joinToString(",")}")
        }
    }

    private fun sendVendorDisconnect(
        context: Context,
        pkg: String,
        action: String,
        device: android.bluetooth.BluetoothDevice?,
    ): String = try {
        context.sendBroadcast(
            android.content.Intent(action).setPackage(pkg).apply {
                if (device != null) putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, device)
            },
        )
        "sent"
    } catch (error: SecurityException) {
        "security"
    } catch (error: RuntimeException) {
        error.javaClass.simpleName
    }

    private fun manifestBytes(sourceDir: String?): ByteArray? {
        if (sourceDir.isNullOrBlank()) return null
        return runCatching {
            java.util.zip.ZipFile(sourceDir).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
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
