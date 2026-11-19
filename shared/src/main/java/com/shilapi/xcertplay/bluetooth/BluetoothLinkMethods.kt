package com.shilapi.xcertplay.bluetooth

/**
 * Disconnects one device without a profile proxy. The profile services on some head units never
 * answer [android.bluetooth.BluetoothAdapter.getProfileProxy]; the adapter service still does.
 * A missing method is reported and skipped.
 */
internal object BluetoothLinkMethods {
    private val NAMES = listOf("disconnectAllEnabledProfiles", "disconnectDevice", "disconnect")

    fun disconnect(service: Any?, device: Any?): String {
        if (device == null) return "no-device"
        var sawSecurity = false
        for (target in listOfNotNull(service, device)) {
            for (name in NAMES) {
                val call = invoke(target, name, device)
                if (!call.found) continue
                if (call.security) {
                    sawSecurity = true
                    continue
                }
                if (call.value != false) return "$name=ok"
            }
        }
        return if (sawSecurity) "security" else "absent"
    }

    private class Call(val found: Boolean, val value: Any?, val security: Boolean)

    private fun invoke(target: Any, name: String, device: Any): Call {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == 1 &&
                accepts(candidate.parameterTypes[0], device)
        } ?: target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.isEmpty()
        } ?: return Call(found = false, value = null, security = false)
        return try {
            val value = if (method.parameterTypes.isEmpty()) method.invoke(target) else method.invoke(target, device)
            Call(found = true, value = value, security = false)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            Call(found = true, value = null, security = error.targetException is SecurityException)
        } catch (error: SecurityException) {
            Call(found = true, value = null, security = true)
        } catch (_: RuntimeException) {
            Call(found = true, value = null, security = false)
        }
    }

    private fun accepts(parameter: Class<*>, device: Any): Boolean =
        parameter.isAssignableFrom(device.javaClass) || parameter == Any::class.java
}
