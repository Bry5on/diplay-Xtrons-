package com.shilapi.xcertplay.bluetooth

import java.lang.reflect.InvocationTargetException

internal enum class ProfileWrite { OK, REJECTED, ABSENT, SECURITY, FAILED }

internal enum class ProfileDisconnect { OK, REFUSED, ABSENT, SECURITY, FAILED }

/**
 * Hidden headset and A2DP proxy methods. API 29 has setPriority; API 30 adds setConnectionPolicy.
 * Either can throw [SecurityException] without BLUETOOTH_PRIVILEGED. A missing method is reported
 * and skipped.
 */
internal object BluetoothProfileMethods {
    fun readPolicy(proxy: Any, device: Any): Int? {
        invoke(proxy, "getConnectionPolicy", device).value?.let { return it as? Int }
        return invoke(proxy, "getPriority", device).value as? Int
    }

    fun writePolicy(proxy: Any, device: Any, policy: Int): ProfileWrite {
        val connection = invoke(proxy, "setConnectionPolicy", device, policy)
        if (connection.found) return asWrite(connection)
        val priority = invoke(proxy, "setPriority", device, policy)
        if (priority.found) return asWrite(priority)
        return ProfileWrite.ABSENT
    }

    fun disconnect(proxy: Any, device: Any): ProfileDisconnect {
        val result = invoke(proxy, "disconnect", device)
        if (!result.found) return ProfileDisconnect.ABSENT
        return when (result.failure) {
            Failure.SECURITY -> ProfileDisconnect.SECURITY
            Failure.ERROR -> ProfileDisconnect.FAILED
            null -> if (result.value == false) ProfileDisconnect.REFUSED else ProfileDisconnect.OK
        }
    }

    private fun asWrite(result: Call): ProfileWrite = when (result.failure) {
        Failure.SECURITY -> ProfileWrite.SECURITY
        Failure.ERROR -> ProfileWrite.FAILED
        null -> if (result.value == false) ProfileWrite.REJECTED else ProfileWrite.OK
    }

    private enum class Failure { SECURITY, ERROR }

    private class Call(val found: Boolean, val value: Any?, val failure: Failure?)

    private fun accepts(type: Class<*>, arg: Any): Boolean {
        if (type.isAssignableFrom(arg.javaClass)) return true
        return when (type) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Long.TYPE -> arg is Long
            else -> false
        }
    }

    private fun invoke(proxy: Any, name: String, vararg args: Any): Call {
        val method = proxy.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(args).all { (type, arg) -> accepts(type, arg) }
        } ?: return Call(found = false, value = null, failure = null)
        return try {
            Call(found = true, value = method.invoke(proxy, *args), failure = null)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            Call(
                found = true,
                value = null,
                failure = if (cause is SecurityException) Failure.SECURITY else Failure.ERROR,
            )
        } catch (error: SecurityException) {
            Call(found = true, value = null, failure = Failure.SECURITY)
        } catch (error: RuntimeException) {
            Call(found = true, value = null, failure = Failure.ERROR)
        }
    }
}
