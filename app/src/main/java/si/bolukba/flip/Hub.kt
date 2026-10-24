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
        fun onButton(pressed: Boolean) {}
        /** Track motor absolute position in degrees, as mounted (right motor not inverted). */
        fun onMotorPosition(port: Int, degrees: Int) {}
        /** Gesture sensor event (tap/bump/shake code > 0) or accelerometer sample (x, y, z). */
        fun onGesture(code: Int) {}
        fun onAccel(x: Int, y: Int, z: Int) {}
        fun onTemperature(celsius: Float) {}
        fun onCurrent(milliamps: Int) {}
        fun onVoltage(millivolts: Int) {}
        /** The status light became addressable (reported after connect). */
        fun onLedReady() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var orientationPort = -1
    /** Port -> attached device type, from Attached I/O reports. */
    private val portTypes = HashMap<Int, Int>()
    private var ledPort = -1
    /** Constant-speed mode: tracks use the motors' speed regulation instead of raw power. */
    var speedControl = false
    /** Accelerometer stream is chatty; only subscribed while bump detection wants it. */
    var wantAccel = false
        set(v) {
            field = v
            portOf(TYPE_ACCEL)?.let { subscribe(it, 0, 40, v) }
        }

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
        portTypes.clear()
        ledPort = -1
        setState(State.IDLE, "Disconnected")
    }

    /** Track powers in -100..100 as the driver sees them; right-motor inversion is applied here. */
    fun setTracks(left: Int, right: Int) {
        send(track(PORT_LEFT, left))
        send(track(PORT_RIGHT, -right))
    }

    /** Zero always coasts (power 0), so stopping feels the same in both modes. */
    private fun track(port: Int, value: Int) =
        if (speedControl && value != 0) speed(port, value) else motor(port, value)

    /** Hub status light, if the hub reported one. */
    fun setLed(color: Int) {
        if (ledPort < 0) return
        send(byteArrayOf(0x0A, 0x00, 0x81.toByte(), ledPort.toByte(), 0x11, 0x51, 0x01,
            (color shr 16).toByte(), (color shr 8).toByte(), color.toByte()))
    }

    /** Hub action: switch off. The link drops right after. */
    fun powerOff() = send(byteArrayOf(0x04, 0x00, 0x02, 0x01))

    /** Sets the advertised name (ASCII, max 14 bytes). */
    fun rename(name: String) {
        val b = name.toByteArray(Charsets.US_ASCII).take(14).toByteArray()
        if (b.isEmpty()) return
        send(byteArrayOf((5 + b.size).toByte(), 0x00, 0x01, 0x01, 0x01) + b)
    }

    private fun portOf(type: Int) = portTypes.entries.firstOrNull { it.value == type }?.key

    /** Port Input Format Setup (single): mode, delta, notifications. */
    private fun subscribe(port: Int, mode: Int, delta: Int, on: Boolean) {
        send(byteArrayOf(0x0A, 0x00, 0x41, port.toByte(), mode.toByte(),
            delta.toByte(), (delta shr 8).toByte(), 0x00, 0x00, if (on) 0x01 else 0x00))
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
                send(byteArrayOf(0x05, 0x00, 0x01, 0x02, 0x02)) // button updates
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
                    0x02 -> listener.onButton(v[5].toInt() != 0)
                }
            }
            0x04 -> if (v.size >= 7 && v[4].toInt() == 0x01) { // attached I/O
                val port = v[3].toInt() and 0xFF
                val type = (v[5].toInt() and 0xFF) or ((v[6].toInt() and 0xFF) shl 8)
                portTypes[port] = type
                when (type) {
                    TYPE_ORIENTATION -> {
                        orientationPort = port
                        subscribe(port, 0, 1, true)
                    }
                    TYPE_L_MOTOR -> subscribe(port, 2, 5, true) // absolute position, 5° delta
                    TYPE_GESTURE -> subscribe(port, 0, 1, true)
                    TYPE_ACCEL -> if (wantAccel) subscribe(port, 0, 40, true)
                    TYPE_TEMPERATURE -> if (portOf(TYPE_TEMPERATURE) == port) subscribe(port, 0, 5, true)
                    TYPE_CURRENT -> subscribe(port, 0, 10, true)
                    TYPE_VOLTAGE -> subscribe(port, 0, 20, true)
                    TYPE_LED -> {
                        ledPort = port
                        subscribe(port, 1, 1, false) // RGB mode
                        listener.onLedReady()
                    }
                }
            }
            0x45 -> if (v.size >= 5) {
                val port = v[3].toInt() and 0xFF
                when (portTypes[port]) {
                    // Hub is mounted reversed: pitch and roll are negated.
                    TYPE_ORIENTATION -> if (v.size >= 10) listener.onOrientation(s16(v, 4), -s16(v, 6), -s16(v, 8))
                    TYPE_L_MOTOR -> if (v.size >= 8) listener.onMotorPosition(port, s32(v, 4))
                    TYPE_GESTURE -> listener.onGesture(v[4].toInt() and 0xFF)
                    TYPE_ACCEL -> if (v.size >= 10) listener.onAccel(s16(v, 4), s16(v, 6), s16(v, 8))
                    TYPE_TEMPERATURE -> if (v.size >= 6) listener.onTemperature(s16(v, 4) / 10f)
                    TYPE_CURRENT -> if (v.size >= 6) listener.onCurrent(s16(v, 4))
                    TYPE_VOLTAGE -> if (v.size >= 6) listener.onVoltage(s16(v, 4))
                }
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
        const val TYPE_L_MOTOR = 0x2E
        const val TYPE_GESTURE = 0x36
        const val TYPE_ACCEL = 0x39
        const val TYPE_TEMPERATURE = 0x3C
        const val TYPE_CURRENT = 0x15
        const val TYPE_VOLTAGE = 0x14
        const val TYPE_LED = 0x17
        const val BRAKE = 127

        /** Port output, StartPower via WriteDirectModeData mode 0, execute immediately + feedback. */
        fun motor(port: Int, power: Int): ByteArray {
            val p = if (power == BRAKE) BRAKE else power.coerceIn(-100, 100)
            return byteArrayOf(0x08, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x51, 0x00, p.toByte())
        }

        /** StartSpeed: regulated speed %, max power 100, no hub-side profile (ramps are done in software). */
        fun speed(port: Int, value: Int): ByteArray =
            byteArrayOf(0x09, 0x00, 0x81.toByte(), port.toByte(), 0x11, 0x07, value.coerceIn(-100, 100).toByte(), 100, 0x00)

        private fun s32(v: ByteArray, i: Int) =
            (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8) or ((v[i + 2].toInt() and 0xFF) shl 16) or (v[i + 3].toInt() shl 24)

        private fun s16(v: ByteArray, i: Int) = ((v[i].toInt() and 0xFF) or (v[i + 1].toInt() shl 8)).toShort().toInt()
    }
}
