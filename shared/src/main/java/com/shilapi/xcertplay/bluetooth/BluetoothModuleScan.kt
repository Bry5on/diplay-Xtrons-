package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File

/**
 * Looks at serial nodes, binder services, car Bluetooth packages, properties, and settings.
 * It never opens a serial port, writes a setting, or sends a broadcast.
 */
internal object BluetoothModuleScan {
    private val carPackages = listOf("android.car.service.bt", "android.car.app.bt")
    private val settingScopes = listOf("system", "secure", "global")

    fun lines(context: Context): List<String> {
        val lines = mutableListOf<String>()
        lines += runCatching { uart() }.getOrElse { listOf("Bluetooth module: uart error=${it.javaClass.simpleName}") }
        lines += runCatching { drivers() }.getOrElse { listOf("Bluetooth module: tty-drivers error=${it.javaClass.simpleName}") }
        lines += runCatching { binders() }.getOrElse { listOf("Bluetooth module: binder error=${it.javaClass.simpleName}") }
        lines += runCatching { packages(context) }.getOrElse { listOf("Bluetooth module: packages error=${it.javaClass.simpleName}") }
        lines += runCatching { properties() }.getOrElse { listOf("Bluetooth module: properties error=${it.javaClass.simpleName}") }
        lines += runCatching { settings(context) }.getOrElse { listOf("Bluetooth module: settings error=${it.javaClass.simpleName}") }
        return lines
    }

    private fun uart(): List<String> {
        val dev = File("/dev")
        return BluetoothModuleProbe.uartLines(BluetoothModuleProbe.listNodes(dev), dev.listFiles() != null)
    }

    private fun drivers(): List<String> {
        val file = File("/proc/tty/drivers")
        if (!file.canRead()) return BluetoothModuleProbe.driverLines(null)
        return BluetoothModuleProbe.driverLines(file.readText())
    }

    private fun binders(): List<String> {
        val listed = runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            manager.getMethod("listServices").invoke(null) as? Array<*>
        }.getOrNull() ?: return listOf("Bluetooth module: binder list=unavailable")
        val names = listed.mapNotNull { it as? String }
        val matched = names.filter { BluetoothModuleProbe.looksLikeControl(it) || BluetoothModuleProbe.vendorPackage(it) }
            .distinct().sorted().take(24)
        val lines = mutableListOf("Bluetooth module: binder count=${names.size} matched=${matched.size}")
        matched.forEach { lines += "Bluetooth module: binder service=$it" }
        return lines
    }

    private fun packages(context: Context): List<String> {
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledApplications(0).map { it.packageName } }.getOrDefault(emptyList())
        val selected = (carPackages + installed.filter { BluetoothModuleProbe.vendorPackage(it) }).distinct().take(16)
        if (selected.isEmpty()) return listOf("Bluetooth module: packages=none")
        val lines = mutableListOf<String>()
        for (pkg in selected) {
            val info = runCatching {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
                )
            }.getOrNull()
            if (info == null) {
                lines += "Bluetooth module: pkg=$pkg installed=no"
                continue
            }
            val manifest = manifestBytes(info.applicationInfo?.sourceDir)
            val filters = manifest?.let { BluetoothVendorManifest.filters(it) }
            val receiverActions = filters?.receivers.orEmpty()
            val serviceActions = filters?.services.orEmpty()
            val services = info.services?.filter { it.exported }.orEmpty()
            val receivers = info.receivers?.filter { it.exported }.orEmpty()
            val providers = info.providers?.filter { it.exported }.orEmpty()
            val control = services.count { BluetoothModuleProbe.looksLikeControl(it.name) } +
                receivers.count { BluetoothModuleProbe.looksLikeControl(it.name) } +
                providers.count { BluetoothModuleProbe.looksLikeControl(it.name) || BluetoothModuleProbe.looksLikeControl(it.authority.orEmpty()) } +
                receiverActions.count { BluetoothModuleProbe.looksLikeControl(it) } +
                serviceActions.count { BluetoothModuleProbe.looksLikeControl(it) }
            lines += "Bluetooth module: pkg=$pkg installed=yes services=${services.size} receivers=${receivers.size} " +
                "providers=${providers.size} control=$control"
            val car = pkg in carPackages
            services.take(if (car) 12 else 6).filter { car || BluetoothModuleProbe.looksLikeControl(it.name) }.forEach { service ->
                lines += BluetoothModuleProbe.componentLine(pkg, "service", service.name, service.exported, emptyList())
            }
            receivers.take(if (car) 12 else 6).filter { car || BluetoothModuleProbe.looksLikeControl(it.name) }.forEach { receiver ->
                lines += BluetoothModuleProbe.componentLine(pkg, "receiver", receiver.name, receiver.exported, emptyList())
            }
            providers.take(8).forEach { provider ->
                val authority = provider.authority ?: "none"
                if (car || BluetoothModuleProbe.looksLikeControl(provider.name) || BluetoothModuleProbe.looksLikeControl(authority)) {
                    lines += "Bluetooth module: component pkg=$pkg kind=provider class=${provider.name.substringAfterLast('.')} " +
                        "exported=${provider.exported} authority=$authority"
                }
            }
            val actions = (receiverActions + serviceActions).distinct().filter { BluetoothModuleProbe.looksLikeControl(it) }.take(8)
            if (actions.isNotEmpty()) {
                lines += "Bluetooth module: actions pkg=$pkg ${actions.joinToString(",")}"
            }
        }
        return lines
    }

    private fun properties(): List<String> {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        return BluetoothModuleProbe.propertyLines(text)
    }

    private fun settings(context: Context): List<String> {
        val lines = mutableListOf<String>()
        for (scope in settingScopes) {
            val rows = runCatching { readSettings(context, scope) }.getOrElse {
                listOf("Bluetooth module: setting scope=$scope unavailable reason=${it.javaClass.simpleName}")
            }
            if (rows.isEmpty()) lines += "Bluetooth module: setting scope=$scope matches=0"
            else lines += rows
        }
        return lines
    }

    private fun readSettings(context: Context, scope: String): List<String> {
        val cursor = context.contentResolver.query(Uri.parse("content://settings/$scope"), arrayOf("name", "value"), null, null, null)
            ?: return listOf("Bluetooth module: setting scope=$scope unavailable reason=no-cursor")
        cursor.use {
            val nameAt = it.getColumnIndex("name")
            val valueAt = it.getColumnIndex("value")
            if (nameAt < 0) return listOf("Bluetooth module: setting scope=$scope unavailable reason=no-name")
            val hits = mutableListOf<String>()
            while (it.moveToNext() && hits.size < 12) {
                val key = it.getString(nameAt) ?: continue
                if (!BluetoothModuleProbe.propertyInterest(key)) continue
                val value = if (valueAt >= 0) it.getString(valueAt) else null
                hits += "Bluetooth module: setting scope=$scope key=$key value=${BluetoothModuleProbe.displayValue(key, value)}"
            }
            return hits
        }
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
}
