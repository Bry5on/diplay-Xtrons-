package com.shilapi.xcertplay.bluetooth

import android.content.Context
import com.shilapi.xcertplay.network.XtronsIxHeadUnit

/**
 * When to take the head unit's hands-free and music profiles off the CarPlay iPhone.
 *
 * Wireless CarPlay still needs Bluetooth until the Wi-Fi tunnel owns iAP2. Audio profiles stay
 * up through that bootstrap and drop only after it is released. The radio itself stays on.
 */
internal object BluetoothAudioPolicy {
    const val OFF = 0
    const val ON = 100
    const val AUTO_CONNECT = 1000

    fun shouldIsolate(enabled: Boolean, sessionUp: Boolean, bootstrapReleased: Boolean): Boolean =
        enabled && sessionUp && bootstrapReleased

    enum class Profile(val id: Int, val label: String) {
        HFP(1, "HFP"),
        A2DP(2, "A2DP"),
        A2DP_SINK(11, "A2DP_SINK"),
        HFP_CLIENT(16, "HFP_CLIENT"),
    }

    data class SavedPolicy(val profile: String, val address: String, val policy: Int)

    fun encode(entries: List<SavedPolicy>): String =
        entries.joinToString(";") { "${it.profile}|${it.address}|${it.policy}" }

    fun decode(raw: String): List<SavedPolicy> =
        raw.split(';').mapNotNull { piece ->
            val parts = piece.split('|')
            if (parts.size != 3) return@mapNotNull null
            val policy = parts[2].toIntOrNull() ?: return@mapNotNull null
            if (parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
            SavedPolicy(parts[0], parts[1], policy)
        }

    /** The selected phone, plus every device that already has one of these profiles up. */
    fun targets(phoneAddress: String?, connected: Set<String>): Set<String> = buildSet {
        phoneAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        addAll(connected)
    }
}

/** Unset follows the head unit. A saved on or off always wins. */
object BluetoothAudioPreference {
    private const val PREFS = "diplay_bluetooth_audio"
    private const val KEY = "keep_in_carplay"
    private const val KEY_ADDRESS = "head_unit_bluetooth_address"
    private const val KEY_HOLD = "hold_radio_during_calls"

    fun enabled(stored: Boolean?, xtronsIx: Boolean): Boolean = stored ?: xtronsIx

    fun enabled(context: Context): Boolean {
        val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = if (settings.contains(KEY)) settings.getBoolean(KEY, false) else null
        return enabled(stored, XtronsIxHeadUnit.detected)
    }

    fun choice(context: Context): String {
        val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!settings.contains(KEY)) return "default"
        return if (settings.getBoolean(KEY, false)) "on" else "off"
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit()
    }

    fun manualAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null)
            ?.takeIf { BluetoothIdentity.usable(it) }

    /** Empty text clears the saved address. Anything else must be AA:BB:CC:DD:EE:FF. */
    fun normalizeManualAddress(raw: String): String? {
        val text = raw.trim().uppercase()
        if (text.isEmpty()) return ""
        return text.takeIf { BluetoothIdentity.usable(it) }
    }

    fun holdRadioDuringCalls(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOLD, false)

    fun setHoldRadioDuringCalls(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOLD, enabled).commit()
    }

    fun setManualAddress(context: Context, raw: String?) {
        val normalized = raw?.let { normalizeManualAddress(it) } ?: return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (normalized.isEmpty()) edit.remove(KEY_ADDRESS) else edit.putString(KEY_ADDRESS, normalized)
        edit.commit()
    }
}
