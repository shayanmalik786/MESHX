package com.meshx.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.meshx.app.mesh.LinkListener
import com.meshx.app.mesh.NodeLink
import com.meshx.app.model.BleDevice
import com.meshx.app.protocol.LineBuffer
import java.util.ArrayDeque
import java.util.UUID

/** The Nordic UART Service: the usual way to send text lines over Bluetooth LE. */
object Nus {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val WRITE: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")     // phone -> node
    val NOTIFY: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")    // node -> phone
    val NOTIFY_SWITCH: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    const val NAME_PREFIX = "MeshX"
}

fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/** Finds nodes nearby. A node is any Bluetooth LE device whose name starts with "MeshX". */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null

    fun start(onFound: (BleDevice) -> Unit, onError: (String) -> Unit) {
        stop()
        val scanner = bluetoothAdapter(context)?.bluetoothLeScanner
        if (scanner == null) {
            onError("Bluetooth is off")
            return
        }
        val created = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: try {
                    result.device.name
                } catch (e: SecurityException) {
                    null
                }
                if (name != null && name.startsWith(Nus.NAME_PREFIX)) {
                    val found = BleDevice(result.device.address, name, result.rssi)
                    main.post { if (callback === this) onFound(found) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post { if (callback === this) onError("Bluetooth scan failed (code $errorCode). Turn Bluetooth off and on, then try again.") }
            }
        }
        callback = created
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), created)
        } catch (e: SecurityException) {
            callback = null
            onError("The Bluetooth permission is missing")
        }
    }

    fun stop() {
        val old = callback ?: return
        callback = null
        try {
            bluetoothAdapter(context)?.bluetoothLeScanner?.stopScan(old)
        } catch (e: RuntimeException) {
            // Bluetooth was switched off in the meantime
        }
    }
}

/**
 * Bluetooth LE link to one node. Lines are written to the node's WRITE characteristic and
 * arrive as notifications on its NOTIFY characteristic. Long lines are cut into pieces that
 * fit the connection (MTU), the newline tells the other side where a line ends.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // the old write calls work on every Android version from 7 to today
class BleLink(private val context: Context, private val address: String) : NodeLink {
    private val main = Handler(Looper.getMainLooper())
    private val lines = LineBuffer()
    private val outbox = ArrayDeque<ByteArray>()
    private var listener: LinkListener? = null
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var chunkSize = 20
    private var writing = false
    private var up = false
    private var closed = false

    private val giveUp = Runnable { fail("The node did not answer over Bluetooth. Is it switched on and close by?") }

    override fun open(listener: LinkListener) {
        this.listener = listener
        val adapter = bluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            main.post { fail("Bluetooth is off") }
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.postDelayed(giveUp, CONNECT_TIMEOUT_MS)
        } catch (e: IllegalArgumentException) {
            main.post { fail("This is not a valid Bluetooth address") }
        } catch (e: SecurityException) {
            main.post { fail("The Bluetooth permission is missing") }
        }
    }

    // Bluetooth calls back on its own thread, so everything is passed to the main thread first.
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (closed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(WANTED_MTU)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail(if (up) "The Bluetooth connection was lost" else "Could not connect over Bluetooth (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (closed) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) chunkSize = (mtu - 3).coerceIn(20, 244)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (closed) return@post
                val service = g.getService(Nus.SERVICE)
                val write = service?.getCharacteristic(Nus.WRITE)
                val notify = service?.getCharacteristic(Nus.NOTIFY)
                val switch = notify?.getDescriptor(Nus.NOTIFY_SWITCH)
                if (write == null || notify == null || switch == null) {
                    fail("This device does not offer the MeshX service")
                    return@post
                }
                writer = write
                g.setCharacteristicNotification(notify, true)
                switch.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(switch)) fail("Could not switch on notifications")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (closed || up) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Could not switch on notifications (status $status)")
                    return@post
                }
                up = true
                main.removeCallbacks(giveUp)
                listener?.onLinkUp()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != Nus.NOTIFY) return
            val data = characteristic.value?.copyOf() ?: return
            main.post {
                if (!closed) lines.feed(data) { line -> listener?.onLinkLine(line) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                if (!closed) writeNext()
            }
        }
    }

    override fun send(line: String) {
        if (closed || !up) return
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            outbox.add(bytes.copyOfRange(offset, end))
            offset = end
        }
        writeNext()
    }

    /** Bluetooth takes one write at a time. The next piece goes out when the last one is confirmed. */
    private fun writeNext() {
        if (writing) return
        val g = gatt ?: return
        val target = writer ?: return
        val piece = outbox.poll() ?: return
        target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        target.value = piece
        writing = true
        if (!g.writeCharacteristic(target)) {
            writing = false
            outbox.addFirst(piece)
            main.postDelayed({ if (!closed) writeNext() }, 30)
        }
    }

    private fun fail(reason: String) {
        if (closed) return
        val target = listener
        close()
        target?.onLinkDown(reason)
    }

    override fun close() {
        if (closed) return
        closed = true
        up = false
        main.removeCallbacks(giveUp)
        outbox.clear()
        listener = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: RuntimeException) {
            // the connection is already gone
        }
        gatt = null
    }

    private companion object {
        const val WANTED_MTU = 247
        const val CONNECT_TIMEOUT_MS = 12_000L
    }
}
