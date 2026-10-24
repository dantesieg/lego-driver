package si.bolukba.flip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drive brain, independent of the activity so it keeps running in the background:
 * mixes gamepad and touch inputs, handles flip and spin, and pushes track powers to the hub.
 * Gamepad fields are fed by MainActivity (foreground) or RootPad (background). Main thread only.
 */
class Driver(ctx: Context) : Hub.Listener {

    interface Ui {
        fun onChange()
    }

    val hub = Hub(ctx.applicationContext, this)
    var ui: Ui? = null
    private val prefs = ctx.applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // Gamepad inputs, -1..1 (triggers 0..1).
    var lx = 0f
    var rx = 0f
    var ly = 0f
    var ry = 0f
    var l2 = 0f
    var r2 = 0f
    var hatX = 0f
    var hatY = 0f
    var dUp = false
    var dDown = false
    var dLeft = false
    var dRight = false

    // Touch sliders, -1..1.
    var touchLeft = 0f
    var touchRight = 0f

    /** Set by a manual disconnect: don't auto-reconnect when the app comes back. */
    var parked = false

    /** Stick layout, one of MODE_*. */
    var mode = prefs.getInt("mode", if (prefs.getBoolean("arcade", false)) MODE_ARCADE else MODE_TANK)
        private set
    var speed = prefs.getInt("speed", 1)
        private set
    var flipped = false
        private set
    var outLeft = 0
        private set
    var outRight = 0
        private set
    var battery = -1
        private set
    var rssi = 0
        private set
    val spinning get() = spinDir != 0

    var pad: InputDevice? = null
        private set

    private var yaw = 0
    private var spinDir = 0
    private val haptics = Haptics()
    private var lastState = Hub.State.IDLE
    private var spinStartYaw = 0
    private var spinStartMs = 0L
    private var lastL = Int.MIN_VALUE
    private var lastR = Int.MIN_VALUE

    init {
        findPad()
        main.post(object : Runnable {
            override fun run() {
                tick()
                main.postDelayed(this, 16)
            }
        })
    }

    private fun tick() {
        if (hub.state != Hub.State.READY) {
            haptics.engine(0, 0)
            return
        }

        if (spinDir != 0) {
            val turned = abs(angleDiff(yaw, spinStartYaw))
            // Stop early to absorb coast-on overshoot (app uses 180 - 50..65).
            if (turned < 180 - SPIN_ADJUST && SystemClock.uptimeMillis() - spinStartMs < 6000) {
                haptics.engine(100, -100)
                return
            }
            spinDir = 0
            hub.brake()
            haptics.pulse(50, 160)
            lastL = Int.MIN_VALUE
            lastR = Int.MIN_VALUE
        }

        var left: Float
        var right: Float
        if (mode == MODE_TANK) {
            left = -ly
            right = -ry
        } else {
            val (throttle, steer) = when (mode) {
                MODE_ARCADE -> (if (r2 > 0f || l2 > 0f) r2 - l2 else -ly) to lx
                MODE_LEFT -> -ly to lx
                else -> -ry to rx
            }
            left = throttle + steer
            right = throttle - steer
            val m = max(1f, max(abs(left), abs(right)))
            left /= m
            right /= m
        }
        // D-pad buttons override the sticks: up/down drive, left/right turn.
        val t = (if (dUp || hatY < -0.5f) 1f else 0f) - (if (dDown || hatY > 0.5f) 1f else 0f)
        val st = (if (dRight || hatX > 0.5f) 1f else 0f) - (if (dLeft || hatX < -0.5f) 1f else 0f)
        if (t != 0f || st != 0f) {
            if (t == 0f) {
                // Turn on the spot.
                left = st
                right = -st
            } else {
                // Curve: the inner track slows down.
                left = if (st < 0f) t * TURN_INNER else t
                right = if (st > 0f) t * TURN_INNER else t
            }
        }
        if (left == 0f && right == 0f) {
            left = touchLeft
            right = touchRight
        }
        // Flipped: tracks swap sides and reverse, so controls stay driver-relative.
        if (flipped) {
            val a = -right
            right = -left
            left = a
        }
        val scale = SPEEDS[speed]
        val l = (left * scale).roundToInt().coerceIn(-100, 100)
        val r = (right * scale).roundToInt().coerceIn(-100, 100)
        if (l != lastL || r != lastR) {
            hub.setTracks(l, r)
            lastL = l
            lastR = r
            show(l, r)
        }
        haptics.engine(l, r)
    }

    private fun show(l: Int, r: Int) {
        outLeft = l
        outRight = r
        ui?.onChange()
    }

    // ---- Actions ---------------------------------------------------------------------------

    fun spin(dir: Int) {
        if (spinDir != 0 || hub.state != Hub.State.READY) return
        spinDir = dir
        spinStartYaw = yaw
        spinStartMs = SystemClock.uptimeMillis()
        // Same raw sign on both ports = pivot turn, as the original app does.
        hub.setRaw(dir * 100, dir * 100)
        show(dir * 100, -dir * 100)
        haptics.pulse(60, 220)
    }

    fun halt() {
        spinDir = 0
        hub.brake()
        lastL = 0
        lastR = 0
        show(0, 0)
        haptics.pulse(120, 255)
    }

    /** Zeroes every input so nothing keeps driving after an input source goes away. */
    fun releaseInputs() {
        lx = 0f; rx = 0f; ly = 0f; ry = 0f; l2 = 0f; r2 = 0f; hatX = 0f; hatY = 0f
        dUp = false; dDown = false; dLeft = false; dRight = false
        touchLeft = 0f; touchRight = 0f
    }

    fun setSpeed(index: Int) {
        speed = index.coerceIn(0, 2)
        prefs.edit().putInt("speed", speed).apply()
        lastL = Int.MIN_VALUE
        haptics.pulses(speed + 1) // 1-3 ticks = Eco/Normal/Turbo
        ui?.onChange()
    }

    fun nextMode() {
        mode = (mode + 1) % MODE_NAMES.size
        prefs.edit().putInt("mode", mode).apply()
        haptics.pulse(40, 150)
        ui?.onChange()
    }

    // ---- Gamepad ---------------------------------------------------------------------------

    fun findPad(): InputDevice? {
        pad = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.firstOrNull { isPad(it) && !it!!.isVirtual }
        haptics.attach(pad)
        ui?.onChange()
        return pad
    }


    // ---- Hub callbacks ---------------------------------------------------------------------

    override fun onState(state: Hub.State, detail: String) {
        lastL = Int.MIN_VALUE
        lastR = Int.MIN_VALUE
        if (state != Hub.State.READY) spinDir = 0
        when {
            state == Hub.State.READY && lastState != Hub.State.READY -> haptics.pulses(2, 60, 60, 200)
            state != Hub.State.READY && lastState == Hub.State.READY -> haptics.pulse(400, 255)
        }
        lastState = state
        ui?.onChange()
    }

    override fun onBattery(percent: Int) {
        battery = percent
        ui?.onChange()
    }

    override fun onRssi(rssi: Int) {
        this.rssi = rssi
        ui?.onChange()
    }

    override fun onOrientation(yaw: Int, pitch: Int, roll: Int) {
        this.yaw = yaw
        // App flips at |roll| > 90 with no hysteresis; add a band so it does not chatter at 90.
        val now = if (flipped) abs(roll) > 80 else abs(roll) > 100
        if (now != flipped) {
            flipped = now
            lastL = Int.MIN_VALUE
            haptics.pulses(2, 90, 80, 255)
            ui?.onChange()
        }
    }

    companion object {
        const val MODE_TANK = 0
        const val MODE_ARCADE = 1
        const val MODE_LEFT = 2
        const val MODE_RIGHT = 3
        val MODE_NAMES = arrayOf("tank", "arcade", "left stick", "right stick")
        private val SPEEDS = intArrayOf(40, 70, 100)
        private const val SPIN_ADJUST = 55
        private const val TURN_INNER = 0.3f

        @Volatile private var instance: Driver? = null

        /** Process-wide driver; outlives the activity so the service can keep it running. */
        fun get(ctx: Context): Driver = instance ?: Driver(ctx).also { instance = it }

        fun isPad(dev: InputDevice?) = dev != null &&
            (dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

        private fun angleDiff(a: Int, b: Int): Int {
            var x = (a - b) % 360
            if (x > 180) x -= 360
            if (x < -180) x += 360
            return x
        }
    }
}
