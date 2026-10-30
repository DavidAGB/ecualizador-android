package com.davidagb.ecualizador

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/** Audífonos Bluetooth conectados y su batería. */
@SuppressLint("MissingPermission")
class Headset(private val ctx: Context, private val onChange: () -> Unit) {

    class Device(
        val name: String,
        val address: String,
        val battery: Int,   // 0..100, o -1 si el audífono no la reporta
        val playing: Boolean,
        val media: Boolean,
    )

    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var a2dp: BluetoothA2dp? = null
    private var hfp: BluetoothHeadset? = null
    private val reported = HashMap<String, Int>()
    private var started = false

    var devices: List<Device> = emptyList(); private set
    val available get() = adapter != null
    val permitted
        get() = Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val enabled get() = permitted && adapter?.isEnabled == true

    private val profiles = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy as BluetoothA2dp
                BluetoothProfile.HEADSET -> hfp = proxy as BluetoothHeadset
            }
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = null
                BluetoothProfile.HEADSET -> hfp = null
            }
            refresh()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    val d = device(i)
                    if (d != null && level in 0..100) reported[d.address] = level
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> connectProfiles()
            }
            refresh()
        }
    }

    fun start() {
        if (started || !permitted || adapter == null) {
            onChange()
            return
        }
        started = true
        ctx.listen(receiver, IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        })
        connectProfiles()
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        ctx.unregisterReceiver(receiver)
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        hfp?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        a2dp = null
        hfp = null
    }

    fun refresh() {
        if (!started || adapter?.isEnabled != true) {
            devices = emptyList()
            onChange()
            return
        }
        val media = a2dp?.connectedDevices.orEmpty()
        val found = LinkedHashMap<String, BluetoothDevice>()
        (media + hfp?.connectedDevices.orEmpty()).forEach { found.putIfAbsent(it.address, it) }
        devices = found.values.map { d ->
            Device(
                name = label(d),
                address = d.address,
                battery = battery(d),
                playing = a2dp?.isA2dpPlaying(d) == true,
                media = d in media,
            )
        }.sortedByDescending { it.playing }
        onChange()
    }

    private fun connectProfiles() {
        if (adapter?.isEnabled != true) return
        if (a2dp == null) adapter.getProfileProxy(ctx, profiles, BluetoothProfile.A2DP)
        if (hfp == null) adapter.getProfileProxy(ctx, profiles, BluetoothProfile.HEADSET)
    }

    private fun label(d: BluetoothDevice): String =
        (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name ?: d.address

    // getBatteryLevel() no es API pública, pero es la misma lectura que usa el sistema
    // (indicador de batería por HFP o por perfil de batería BLE).
    private fun battery(d: BluetoothDevice): Int {
        val level = try {
            d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
        } catch (e: Exception) {
            -1
        }
        return if (level in 0..100) level else reported[d.address] ?: -1
    }

    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
