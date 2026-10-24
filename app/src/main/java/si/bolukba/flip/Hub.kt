package si.bolukba.flip

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID

/**
 * LWP3 link to the Technic Hub of 42140. Protocol per re/SPEC-42140.md:
 * left track = port 1, right track = port 0 (inverted), orientation sensor type 0x3B.
 * All callbacks are delivered on the main thread.
 */
@SuppressLint("MissingPermission") // callers request BLUETOOTH_SCAN/CONNECT first
class Hub(private val ctx: Context, var listener: Listener) {

    enum class State { IDLE, SCANNING, CONNECTING, READY }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onBattery(percent: Int)
        fun onOrientation(yaw: Int, pitch: Int, roll: Int)
        fun onRssi(rssi: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var orientationPort = -1

    var state = State.IDLE
        private set
    var detail = ""
        private set

    fun start() {
        if (state != State.IDLE) return
        val saved = prefs.getString("addr", null)
        if (saved != null) connect(adapter.getRemoteDevice(saved), autoConnect = true) else scan()
    }

    fun scan() {
        stop()
        val scanner = adapter?.bluetoothLeScanner ?: return setState(State.IDLE, "Bluetooth is off")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        setState(State.SCANNING, "Press the green button on the hub")
    }

    fun forget() {
        prefs.edit().remove("addr").apply()
        scan()
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        gatt?.let {
            if (state == State.READY) {
                // Brake both tracks before dropping the link.
                writeNow(it, motor(PORT_LEFT, BRAKE))
                writeNow(it, motor(PORT_RIGHT, BRAKE))
            }
            it.disconnect()
            it.close()
        }
        gatt = null
        chr = null
        queue.clear()
        writing = false
        setState(State.IDLE, "Disconnected")
    }

    /** Track powers in -100..100 as the driver sees them; right-motor inversion is applied here. */
    fun setTracks(left: Int, right: Int) {
        send(motor(PORT_LEFT, left))
        send(motor(PORT_RIGHT, -right))
    }

    /** Raw port powers (used by the spin, which sends the same sign to both ports). */
    fun setRaw(port0: Int, port1: Int) {
        send(motor(PORT_LEFT, port1))
        send(motor(PORT_RIGHT, port0))
    }

    fun brake() {
        send(motor(PORT_LEFT, BRAKE))
        send(motor(PORT_RIGHT, BRAKE))
    }

    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        setState(State.CONNECTING, if (autoConnect) "Waiting for hub… press its button" else "Connecting…")
        gatt = device.connectGatt(ctx, autoConnect, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (state != State.SCANNING) return
            // Technic Hub: manufacturer data (LEGO 0x0397) byte 1 = system type 0x80.
            val m = result.scanRecord?.getManufacturerSpecificData(0x0397)
            if (m != null && m.size >= 2 && m[1] != 0x80.toByte()) return
            adapter.bluetoothLeScanner?.stopScan(this)
            connect(result.device, autoConnect = false)
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { setState(State.IDLE, "Scan failed ($errorCode)") }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else {
                    chr = null
                    queue.clear()
                    writing = false
                    // Keep the GATT object so Android reconnects when the hub comes back.
                    if (prefs.getString("addr", null) != null) {
                        g.close()
                        connect(g.device, autoConnect = true)
                    } else {
                        stop()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val c = g.getService(SERVICE)?.getCharacteristic(CHAR)
                if (c == null) {
                    setState(State.IDLE, "Not a LEGO hub")
                    stop()
                    return@post
                }
                chr = c
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CCCD)
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                prefs.edit().putString("addr", g.device.address).apply()
                send(byteArrayOf(0x05, 0x00, 0x01, 0x06, 0x02)) // battery updates
                send(byteArrayOf(0x05, 0x00, 0x01, 0x05, 0x02)) // RSSI updates
                setState(State.READY, "Connected")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                pump()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { parse(value) }
        }
    }

    private fun parse(v: ByteArray) {
        if (v.size < 3) return
        when (v[2].toInt() and 0xFF) {
            0x01 -> if (v.size >= 6 && v[4].toInt() == 0x06) { // hub property update
                when (v[3].toInt()) {
                    0x06 -> listener.onBattery(v[5].toInt() and 0xFF)
                    0x05 -> listener.onRssi(v[5].toInt())
                }
            }
            0x04 -> if (v.size >= 7 && v[4].toInt() == 0x01) { // attached I/O
                val port = v[3].toInt() and 0xFF
                val type = (v[5].toInt() and 0xFF) or ((v[6].toInt() and 0xFF) shl 8)
                if (type == TYPE_ORIENTATION) {
                    orientationPort = port
                    // Mode 0, delta 1, notifications on.
                    send(byteArrayOf(0x0A, 0x00, 0x41, port.toByte(), 0x00, 0x01, 0x00, 0x00, 0x00, 0x01))
                }
            }
            0x45 -> if (v.size >= 10 && (v[3].toInt() and 0xFF) == orientationPort) {
                val yaw = s16(v, 4)
                // Hub is mounted reversed: pitch and roll are negated.
                listener.onOrientation(yaw, -s16(v, 6), -s16(v, 8))
            }
        }
    }

    private fun send(msg: ByteArray) {
        if (chr == null) return
        // Drop a stale command for the same port so the queue never lags behind the sticks.
        if (msg.size >= 4 && msg[2] == 0x81.toByte()) queue.removeIf { it.size >= 4 && it[2] == 0x81.toByte() && it[3] == msg[3] }
        queue.add(msg)
        pump()
    }

    private fun pump() {
        val g = gatt ?: return
        if (writing || queue.isEmpty()) return
        writing = writeNow(g, queue.poll()!!)
    }

    private fun writeNow(g: BluetoothGatt, msg: ByteArray): Boolean {
        val c = chr ?: return false
        return g.writeCharacteristic(c, msg, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
    }

    private fun setState(s: State, detail: String) {
        state = s
        this.detail = detail
        listener.onState(s, detail)
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("00001623-1212-efde-1623-785feabcd123")
        val CHAR: UUID = UUID.fromString("00001624-1212-efde-1623-785feabcd123")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PORT_RIGHT = 0
        const val PORT_LEFT = 1
        const val TYPE_ORIENTATION = 0x3B
        const val BRAKE = 127

        /** Port output, StartPower via WriteDirectModeData mode 0, execute immediately + feedback. */
        fun motor(port: Int, power: Int): ByteArray {
            val p = if (power == BRAKE) BRAKE else power.coerceIn(-100, 100)
            return byteArrayOf(0x08, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x51, 0x00, p.toByte())
        }

        private fun s16(v: ByteArray, i: Int) = ((v[i].toInt() and 0xFF) or (v[i + 1].toInt() shl 8)).toShort().toInt()
    }
}
