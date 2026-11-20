package com.shilapi.xcertplay.network

import android.os.Build

/**
 * The XTRONS IX head unit (Unisoc T618, board/product `XTRONS_IX`, fingerprint
 * `Vastking/KingPad_SA10_EEA`). Firmware can say Android 12 while [Build.VERSION.SDK_INT] stays 29.
 *
 * Used only to choose a default. A saved setting always wins, and other head units stay on the
 * stock default.
 */
object XtronsIxHeadUnit {
    data class Identity(
        val product: String = "",
        val device: String = "",
        val board: String = "",
        val model: String = "",
        val hardware: String = "",
        val fingerprint: String = "",
    )

    val detected: Boolean
        get() = matches(Identity(
            product = Build.PRODUCT.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            board = Build.BOARD.orEmpty(),
            model = Build.MODEL.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
        ))

    fun matches(identity: Identity): Boolean {
        val fields = listOf(
            identity.product,
            identity.device,
            identity.board,
            identity.model,
            identity.hardware,
            identity.fingerprint,
        ).map(::normalize)
        if (fields.any { it.contains("xtrons_ix") || it.contains("xtronsix") }) return true
        return normalize(identity.fingerprint).contains("kingpad_sa10")
    }

    private fun normalize(value: String): String =
        value.lowercase().replace('-', '_').replace(' ', '_')
}
