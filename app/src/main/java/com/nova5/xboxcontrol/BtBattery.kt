package com.nova5.xboxcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Battery level of a Bluetooth controller as Android's Bluetooth settings show it.
 *
 * Many pads don't report their battery through the input system (InputDevice.getBatteryState),
 * but the Bluetooth stack still knows the level. Android exposes it only through a hidden
 * BluetoothDevice.getBatteryLevel() and the BATTERY_LEVEL_CHANGED broadcast, so this reads
 * both. Gives a percentage only - Bluetooth has no charging status.
 */
class BtBattery(private val ctx: Context) {

    private val cached = ConcurrentHashMap<String, Int>() // device address -> level
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val level = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
            if (level in 0..100) cached[dev.address] = level else cached.remove(dev.address)
        }
    }

    /** Android 12+ needs the "Nearby devices" (BLUETOOTH_CONNECT) runtime permission. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (registered || !hasPermission()) return
        val f = IntentFilter(ACTION_BATTERY_LEVEL_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(receiver, f)
            registered = true
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!registered) return
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
    }

    /** Level 0-100 of the paired Bluetooth device named like the controller, or null if unknown. */
    @SuppressLint("MissingPermission")
    fun levelFor(controllerName: String): Int? {
        if (!hasPermission()) return null
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) return null
            val name = controllerName.trim()
            val devices = adapter.bondedDevices.orEmpty()
            val matches = devices.filter { it.name?.trim().equals(name, ignoreCase = true) }
                .ifEmpty { devices.filter { d -> d.name?.let { name.contains(it, true) || it.contains(name, true) } == true } }
            // Several paired pads can share a name; the connected one is the one with a level.
            matches.firstNotNullOfOrNull { d -> hiddenLevel(d) ?: cached[d.address] }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun hiddenLevel(d: BluetoothDevice): Int? = try {
        val v = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int
        v.takeIf { it in 0..100 }
    } catch (_: Exception) {
        null // method blocked or missing on this Android version: rely on the broadcast
    }

    companion object {
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val PERMISSION_REQUEST = 42
    }
}
