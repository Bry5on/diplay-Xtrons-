package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads whether the head unit's Android Bluetooth stack still has the factory hands-free and
 * music profiles up. It never connects, disconnects, or changes a priority. A missing profile
 * service is logged and skipped: some head units keep those profiles on a module Android does
 * not expose.
 *
 * Device names and addresses are not recorded.
 */
object FactoryBluetoothSnapshot {
    private const val TAG = "DiPlay-CallPath"
    private const val PROFILE_A2DP_SINK = 11
    private const val PROFILE_HEADSET_CLIENT = 16
    private const val PROXY_TIMEOUT_MILLIS = 2_000L

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-bt-snapshot").apply { isDaemon = true }
    }

    @Volatile
    var latest: String = "Bluetooth audio: state=not-checked"

    private val profiles = listOf(
        BluetoothProfile.HEADSET to "HFP",
        BluetoothProfile.A2DP to "A2DP",
        PROFILE_A2DP_SINK to "A2DP_SINK",
        PROFILE_HEADSET_CLIENT to "HFP_CLIENT",
    )

    fun log(context: Context, report: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            val line = runCatching { capture(app) }.getOrElse { error ->
                "Bluetooth audio: state=unknown reason=${error.javaClass.simpleName}"
            }
            latest = line
            Log.i(TAG, line)
            runCatching { report(line) }
        }
    }

    internal fun capture(context: Context): String {
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()
        if (adapter == null) return summary(emptyList(), "no-adapter")
        val enabled = runCatching { adapter.isEnabled }.getOrNull()
        if (enabled != true) return summary(emptyList(), "bluetooth-off")
        return summary(probe(context, adapter), null)
    }

    /**
     * `state=none` only when every audio profile answered and none is connected.
     * A missing or privileged profile stays `unknown` so a report cannot claim the air is clear.
     */
    internal fun summary(probes: List<Probe>, reason: String?): String {
        val state = when {
            reason != null -> "unknown"
            probes.isEmpty() -> "unknown"
            probes.any { it.state == "connected" } -> "active"
            probes.all { it.state == "disconnected" } -> "none"
            else -> "unknown"
        }
        val detail = if (probes.isEmpty()) {
            "reason=${reason ?: "none"}"
        } else {
            probes.joinToString(" ") { probe ->
                val count = probe.devices?.let { ":$it" }.orEmpty()
                "${probe.label}=${probe.state}$count"
            }
        }
        return "Bluetooth audio: state=$state $detail"
    }

    private fun probe(context: Context, adapter: BluetoothAdapter): List<Probe> {
        val latch = CountDownLatch(profiles.size)
        val results = ConcurrentHashMap<Int, Probe>()
        for ((id, label) in profiles) {
            val opened = AtomicBoolean(false)
            val accepted = try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                        if (!opened.compareAndSet(false, true)) return
                        try {
                            val count = runCatching { proxy.connectedDevices?.size }.getOrNull()
                            results[profileId] = when (count) {
                                null -> Probe(label, "unreadable", null)
                                0 -> Probe(label, "disconnected", 0)
                                else -> Probe(label, "connected", count)
                            }
                        } catch (error: SecurityException) {
                            results[profileId] = Probe(label, "security", null)
                        } finally {
                            runCatching { adapter.closeProfileProxy(profileId, proxy) }
                            latch.countDown()
                        }
                    }

                    override fun onServiceDisconnected(profileId: Int) {
                        if (opened.compareAndSet(false, true)) latch.countDown()
                    }
                }, id)
            } catch (error: SecurityException) {
                results[id] = Probe(label, "security", null)
                false
            } catch (error: RuntimeException) {
                results[id] = Probe(label, "unavailable", null)
                false
            }
            if (!accepted) {
                results.putIfAbsent(id, Probe(label, "unavailable", null))
                if (opened.compareAndSet(false, true)) latch.countDown()
            }
        }
        runCatching { latch.await(PROXY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }
        return profiles.map { (id, label) -> results[id] ?: Probe(label, "unavailable", null) }
    }

    internal data class Probe(val label: String, val state: String, val devices: Int?)
}
