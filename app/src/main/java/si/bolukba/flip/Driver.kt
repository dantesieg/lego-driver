package si.bolukba.flip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Drive brain, independent of the activity so it keeps running in the background:
 * mixes gamepad and touch inputs, handles flip, turns, ramps and stall protection, and pushes
 * track commands to the hub. Gamepad fields are fed by MainActivity (foreground) or RootPad
 * (background). Main thread only.
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

    // Telemetry.
    private var tempC = Float.NaN
    private var currentMa = -1
    private var voltageMv = -1
    private val pos = IntArray(2) { Int.MIN_VALUE } // by port
    private val lastMoveMs = LongArray(2)
    private val lastPos = IntArray(2)
    private var distDeg = 0L
    private var speedDegS = 0f
    private var speedSampleMs = 0L
    private var speedSampleDeg = 0L

    /** Short status shown over the drive screen (stall, renamed, …) until [noticeUntil]. */
    var notice: String? = null
        private set
    private var noticeUntil = 0L

    private var yaw = 0
    private var spinDir = 0
    private var spinTarget = 180
    private var spinTurned = 0
    private var spinStartMs = 0L
    private val haptics = Haptics()
    private val extras = PadExtras()
    private var bothTriggers = false
    private var lastState = Hub.State.IDLE
    private var lastL = Int.MIN_VALUE
    private var lastR = Int.MIN_VALUE
    private var curL = 0f
    private var curR = 0f
    private var stalled = false
    private var accelEma = 0f
    private var lastBumpMs = 0L
    private var lastHubLed = -1

    init {
        findPad()
        applyOptions()
        main.post(object : Runnable {
            override fun run() {
                tick()
                main.postDelayed(this, 16)
            }
        })
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (notice != null && now > noticeUntil) {
            notice = null
            ui?.onChange()
        }
        if (hub.state != Hub.State.READY) {
            haptics.engine(0, 0)
            return
        }
        updateSpeed(now)

        if (spinDir != 0) {
            // Stop early to absorb coast-on overshoot (the original uses 180 - 50..65).
            val adjust = min(SPIN_ADJUST.toFloat(), spinTarget * 0.3f)
            if (abs(spinTurned) < spinTarget - adjust && now - spinStartMs < 6000) {
                if (hapticOn(H_ENGINE)) haptics.engine(100, -100)
                checkStall(now, 100, 100)
                return
            }
            spinDir = 0
            hub.brake()
            if (hapticOn(H_SPIN)) haptics.pulse(50, 160)
            lastL = Int.MIN_VALUE
            lastR = Int.MIN_VALUE
            curL = 0f
            curR = 0f
        }

        // L2 + R2 fully pressed together cycles the layout (once per press).
        val both = l2 > 0.9f && r2 > 0.9f
        if (both && !bothTriggers) nextMode()
        bothTriggers = both
        if (bothTriggers || (l2 > 0.5f && r2 > 0.5f)) {
            // Don't drive while the combo is being pressed.
            output(0, 0)
            haptics.engine(0, 0)
            return
        }

        var (left, right) = mix()

        // After a stall, stay stopped until the driver lets go of everything.
        if (stalled) {
            if (left == 0f && right == 0f) stalled = false
            else {
                output(0, 0)
                haptics.engine(0, 0)
                return
            }
        }

        // Flipped: tracks swap sides and reverse, so controls stay driver-relative.
        if (flipped) {
            val a = -right
            right = -left
            left = a
        }
        val scale = SPEEDS[speed]
        val ramp = RAMP_MS[option(O_RAMP)]
        val step = if (ramp == 0) 200f else ceil(100f * 16f / ramp)
        curL = slew(curL, left * scale, step)
        curR = slew(curR, right * scale, step)
        val l = curL.roundToInt().coerceIn(-100, 100)
        val r = curR.roundToInt().coerceIn(-100, 100)
        output(l, r)
        if (hapticOn(H_ENGINE)) haptics.engine(l, r) else haptics.engine(0, 0)
        checkStall(now, l, r)
    }

    /** Inputs -> (left, right) track demand, -1..1. */
    private fun mix(): Pair<Float, Float> {
        var left: Float
        var right: Float
        if (mode == MODE_TANK) {
            left = -ly
            right = -ry
        } else {
            var (throttle, steer) = when (mode) {
                MODE_ARCADE -> (if (r2 > 0f || l2 > 0f) r2 - l2 else -ly) to lx
                MODE_LEFT -> -ly to lx
                else -> -ry to rx
            }
            if (flag(F_TILT)) steer = extras.tilt
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
        return left to right
    }

    private fun slew(cur: Float, target: Float, step: Float): Float {
        // Ramps only soften speeding up; slowing down and reversing toward zero stays immediate.
        if (abs(target) < abs(cur) && (target == 0f || Math.signum(target) == Math.signum(cur))) return target
        return if (target > cur) min(target, cur + step) else max(target, cur - step)
    }

    private fun output(l: Int, r: Int) {
        if (l == lastL && r == lastR) return
        hub.setTracks(l, r)
        lastL = l
        lastR = r
        if (l == 0 && r == 0) {
            curL = 0f
            curR = 0f
        }
        show(l, r)
    }

    private fun show(l: Int, r: Int) {
        outLeft = l
        outRight = r
        ui?.onChange()
    }

    /** A track pushed hard whose encoder hasn't moved for a while is stalled: cut power. */
    private fun checkStall(now: Long, l: Int, r: Int) {
        val demand = intArrayOf(abs(r), abs(l)) // index = port: 0 right, 1 left
        for (p in 0..1) {
            if (demand[p] < STALL_MIN_POWER || pos[p] == Int.MIN_VALUE) lastMoveMs[p] = now
        }
        if (!flag(F_STALL) || stalled) return
        if ((0..1).any { now - lastMoveMs[it] > STALL_MS }) {
            stalled = true
            spinDir = 0
            output(0, 0)
            if (hapticOn(H_STALL)) haptics.pulses(3, 120, 80, 255)
            say("Stalled: power cut, release controls")
            lastMoveMs[0] = now
            lastMoveMs[1] = now
        }
    }

    private fun updateSpeed(now: Long) {
        if (now - speedSampleMs < 500) return
        val dt = (now - speedSampleMs) / 1000f
        if (speedSampleMs != 0L) speedDegS = (distDeg - speedSampleDeg) / dt
        speedSampleMs = now
        speedSampleDeg = distDeg
        if (flag(F_TELEMETRY)) ui?.onChange()
    }

    // ---- Actions ---------------------------------------------------------------------------

    /** Turns on the spot by the configured angle using the hub's yaw. */
    fun spin(dir: Int) {
        if (spinDir != 0 || hub.state != Hub.State.READY) return
        spinDir = dir
        spinTarget = TURN_ANGLES[option(O_TURN)]
        spinTurned = 0
        spinStartMs = SystemClock.uptimeMillis()
        // Same raw sign on both ports = pivot turn, as the original app does.
        hub.setRaw(dir * 100, dir * 100)
        lastL = Int.MIN_VALUE
        show(dir * 100, -dir * 100)
        if (hapticOn(H_SPIN)) haptics.pulse(60, 220)
    }

    fun halt() {
        spinDir = 0
        hub.brake()
        lastL = 0
        lastR = 0
        curL = 0f
        curR = 0f
        show(0, 0)
        if (hapticOn(H_BRAKE)) haptics.pulse(120, 255)
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
        if (hapticOn(H_SPEED)) haptics.pulses(speed + 1) // 1-3 ticks = Eco/Normal/Turbo
        updateLights()
        ui?.onChange()
    }

    fun nextMode() = setMode((mode + 1) % MODE_NAMES.size)

    fun setMode(m: Int) {
        mode = m.coerceIn(0, MODE_NAMES.size - 1)
        prefs.edit().putInt("mode", mode).apply()
        // 1-4 ticks tell which layout is now active without looking.
        if (hapticOn(H_LAYOUT)) haptics.pulses(mode + 1, 35, 60, 150)
        updateLights()
        ui?.onChange()
    }

    fun powerOffHub() {
        halt()
        parked = true
        hub.powerOff()
    }

    fun renameHub(name: String) {
        hub.rename(name)
        say("Hub renamed to $name (shows after reconnect)")
    }

    private fun say(text: String) {
        notice = text
        noticeUntil = SystemClock.uptimeMillis() + 2500
        ui?.onChange()
    }

    // ---- Options ---------------------------------------------------------------------------

    fun hapticOn(key: String) = prefs.getBoolean(key, true)

    fun setHaptic(key: String, on: Boolean) {
        prefs.edit().putBoolean(key, on).apply()
        applyOptions()
    }

    fun option(c: Choice) = prefs.getInt(c.key, c.default).coerceIn(0, c.options.size - 1)

    fun setOption(c: Choice, v: Int) {
        prefs.edit().putInt(c.key, v).apply()
        applyOptions()
    }

    fun flag(t: Toggle) = prefs.getBoolean(t.key, t.default)

    fun setFlag(t: Toggle, on: Boolean) {
        prefs.edit().putBoolean(t.key, on).apply()
        applyOptions()
    }

    private fun applyOptions() {
        hub.speedControl = flag(F_SPEED_CONTROL)
        hub.wantAccel = hapticOn(H_BUMP)
        extras.setTiltEnabled(pad, flag(F_TILT))
        lastL = Int.MIN_VALUE
        lastHubLed = -1
        updateLights()
        ui?.onChange()
    }

    /** One-line telemetry for the drive screen, or null when hidden. */
    fun telemetry(): String? {
        if (!flag(F_TELEMETRY) || hub.state != Hub.State.READY) return null
        val parts = ArrayList<String>()
        if (!tempC.isNaN()) parts += "%.1f°C".format(tempC)
        if (currentMa >= 0) parts += "${currentMa}mA"
        if (voltageMv > 0) parts += "%.2fV".format(voltageMv / 1000f)
        if (pos[0] != Int.MIN_VALUE) {
            val cmPerDeg = TRACK_CM_PER_REV / 360f
            parts += "≈%.1f km/h".format(speedDegS * cmPerDeg * 0.036f)
            parts += "≈%.1f m".format(distDeg * cmPerDeg / 100f)
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    // ---- Lights ----------------------------------------------------------------------------

    private fun stateColor(choice: Int): Int = when (choice) {
        LIGHT_BATTERY -> when {
            battery < 0 -> 0xFFFFFF
            battery > 50 -> 0x00C853
            battery > 20 -> 0xFFAB00
            else -> 0xFF1744
        }
        LIGHT_SPEED -> intArrayOf(0x00C853, 0xFFC93C, 0xFF1744)[speed]
        LIGHT_ORIENTATION -> if (flipped) 0xFF6D00 else 0x2979FF
        LIGHT_LAYOUT -> intArrayOf(0x2979FF, 0xAA00FF, 0x00C853, 0x00B8D4)[mode]
        else -> 0
    }

    private fun updateLights() {
        if (hub.state == Hub.State.READY) {
            val c = stateColor(option(O_HUB_LIGHT))
            if (c != lastHubLed) {
                hub.setLed(c)
                lastHubLed = c
            }
        }
        extras.setColor(stateColor(option(O_PAD_LIGHT)))
    }

    // ---- Gamepad ---------------------------------------------------------------------------

    fun findPad(): InputDevice? {
        val p = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.firstOrNull { isPad(it) && !it!!.isVirtual }
        if (p?.id != pad?.id || p == null) {
            pad = p
            haptics.attach(p)
            extras.attach(p)
            extras.setTiltEnabled(p, flag(F_TILT))
            updateLights()
        }
        ui?.onChange()
        return pad
    }

    // ---- Hub callbacks ---------------------------------------------------------------------

    override fun onState(state: Hub.State, detail: String) {
        lastL = Int.MIN_VALUE
        lastR = Int.MIN_VALUE
        if (state != Hub.State.READY) {
            spinDir = 0
            pos.fill(Int.MIN_VALUE)
            tempC = Float.NaN
            currentMa = -1
            voltageMv = -1
        }
        when {
            state == Hub.State.READY && lastState != Hub.State.READY -> if (hapticOn(H_CONNECT)) haptics.pulses(2, 60, 60, 200)
            state != Hub.State.READY && lastState == Hub.State.READY -> if (hapticOn(H_LOST)) haptics.pulse(400, 255)
        }
        lastState = state
        if (state == Hub.State.READY) applyOptions()
        ui?.onChange()
    }

    override fun onBattery(percent: Int) {
        battery = percent
        updateLights()
        ui?.onChange()
    }

    override fun onRssi(rssi: Int) {
        this.rssi = rssi
        ui?.onChange()
    }

    override fun onOrientation(yaw: Int, pitch: Int, roll: Int) {
        if (spinDir != 0) spinTurned += angleDiff(yaw, this.yaw)
        this.yaw = yaw
        // App flips at |roll| > 90 with no hysteresis; add a band so it does not chatter at 90.
        val now = if (flipped) abs(roll) > 80 else abs(roll) > 100
        if (now != flipped) {
            flipped = now
            lastL = Int.MIN_VALUE
            if (hapticOn(H_FLIP)) haptics.pulses(2, 90, 80, 255)
            updateLights()
            ui?.onChange()
        }
    }

    override fun onButton(pressed: Boolean) {
        if (!pressed) return
        when (option(O_HUB_BUTTON)) {
            BUTTON_BRAKE -> halt()
            BUTTON_SPEED -> setSpeed((speed + 1) % 3)
            BUTTON_LAYOUT -> nextMode()
        }
    }

    override fun onMotorPosition(port: Int, degrees: Int) {
        if (port !in 0..1) return
        if (pos[port] != Int.MIN_VALUE) {
            val d = abs(degrees - pos[port])
            // Odometer: average of both tracks.
            distDeg += d / 2
            if (abs(degrees - lastPos[port]) >= STALL_MOVE_DEG) {
                lastMoveMs[port] = SystemClock.uptimeMillis()
                lastPos[port] = degrees
            }
        } else {
            lastPos[port] = degrees
            lastMoveMs[port] = SystemClock.uptimeMillis()
        }
        pos[port] = degrees
    }

    override fun onGesture(code: Int) {
        // Low sensitivity relies on the accelerometer only.
        if (code > 0 && option(O_BUMP) > 0) bump(if (code >= 2) 255 else 180)
    }

    override fun onAccel(x: Int, y: Int, z: Int) {
        val mag = sqrt((x * x + y * y + z * z).toFloat())
        if (accelEma == 0f) accelEma = mag
        val dev = abs(mag - accelEma) / max(accelEma, 1f)
        accelEma += (mag - accelEma) * 0.05f
        if (dev > BUMP_THRESHOLD[option(O_BUMP)]) bump((120 + dev * 150).toInt().coerceAtMost(255))
    }

    private fun bump(amp: Int) {
        val now = SystemClock.uptimeMillis()
        if (!hapticOn(H_BUMP) || now - lastBumpMs < 350) return
        lastBumpMs = now
        haptics.pulse(90, amp)
    }

    override fun onLedReady() {
        lastHubLed = -1
        updateLights()
    }

    override fun onTemperature(celsius: Float) {
        tempC = celsius
    }

    override fun onCurrent(milliamps: Int) {
        currentMa = milliamps
    }

    override fun onVoltage(millivolts: Int) {
        voltageMv = millivolts
    }

    /** A settings choice: one of [options], stored by index. */
    class Choice(val key: String, val title: String, val options: List<String>, val default: Int)

    class Toggle(val key: String, val title: String, val hint: String, val default: Boolean)

    companion object {
        const val MODE_TANK = 0
        const val MODE_ARCADE = 1
        const val MODE_LEFT = 2
        const val MODE_RIGHT = 3
        val MODE_NAMES = arrayOf("tank", "arcade", "left", "right")
        val MODE_HINTS = arrayOf(
            "Tank: left stick = left track, right stick = right track",
            "Arcade: R2 forward, L2 reverse, left stick steers",
            "Left stick only",
            "Right stick only",
        )

        private const val LIGHT_BATTERY = 1
        private const val LIGHT_SPEED = 2
        private const val LIGHT_ORIENTATION = 3
        private const val LIGHT_LAYOUT = 4
        private val LIGHT_OPTIONS = listOf("Off", "Hub battery", "Speed mode", "Upright / flipped", "Stick layout")
        private const val BUTTON_BRAKE = 1
        private const val BUTTON_SPEED = 2
        private const val BUTTON_LAYOUT = 3

        private val TURN_ANGLES = intArrayOf(45, 90, 180, 360)
        private val RAMP_MS = intArrayOf(0, 250, 500, 900)
        private val BUMP_THRESHOLD = floatArrayOf(1.2f, 0.8f, 0.5f)

        val O_TURN = Choice("o_turn", "Turn buttons (L1 / R1, Spin)", listOf("45°", "90°", "180°", "360°"), 2)
        val O_HUB_LIGHT = Choice("o_hub_light", "Hub light shows", LIGHT_OPTIONS, LIGHT_BATTERY)
        val O_PAD_LIGHT = Choice("o_pad_light", "Controller light bar shows", LIGHT_OPTIONS, LIGHT_BATTERY)
        val O_HUB_BUTTON = Choice("o_hub_button", "Hub button press", listOf("Nothing", "Brake", "Cycle speed", "Cycle layout"), BUTTON_BRAKE)
        val O_RAMP = Choice("o_ramp", "Acceleration ramp", listOf("Off", "Soft", "Medium", "Strong"), 0)
        val O_BUMP = Choice("o_bump", "Bump sensitivity", listOf("Low", "Medium", "High"), 1)
        val CHOICES = listOf(O_TURN, O_RAMP, O_HUB_LIGHT, O_PAD_LIGHT, O_HUB_BUTTON, O_BUMP)

        val F_SPEED_CONTROL = Toggle("f_speed", "Constant speed", "Motors hold speed regardless of battery and load", false)
        val F_STALL = Toggle("f_stall", "Stall protection", "Cut power when a track is blocked", true)
        val F_TELEMETRY = Toggle("f_telemetry", "Show telemetry", "Temperature, current, voltage, speed, distance", true)
        val F_TILT = Toggle("f_tilt", "Tilt to steer", "Tilt the controller to steer (not in tank layout)", false)
        val TOGGLES = listOf(F_SPEED_CONTROL, F_STALL, F_TELEMETRY, F_TILT)

        const val H_ENGINE = "h_engine"
        const val H_CONNECT = "h_connect"
        const val H_LOST = "h_lost"
        const val H_FLIP = "h_flip"
        const val H_SPIN = "h_spin"
        const val H_BRAKE = "h_brake"
        const val H_SPEED = "h_speed"
        const val H_LAYOUT = "h_layout"
        const val H_BUMP = "h_bump"
        const val H_STALL = "h_stall"
        val HAPTICS = listOf(
            H_ENGINE to "Engine rumble while driving",
            H_BUMP to "Crash and bump jolt",
            H_CONNECT to "Hub connected",
            H_LOST to "Hub connection lost",
            H_FLIP to "Vehicle flipped",
            H_SPIN to "Turn start and end",
            H_BRAKE to "Brake",
            H_STALL to "Stall warning",
            H_SPEED to "Speed change (1–3 ticks)",
            H_LAYOUT to "Layout change (1–4 ticks)",
        )
        private val SPEEDS = intArrayOf(40, 70, 100)
        private const val SPIN_ADJUST = 55
        private const val TURN_INNER = 0.3f
        private const val STALL_MIN_POWER = 30
        private const val STALL_MS = 1200L
        private const val STALL_MOVE_DEG = 10
        /** Track travel per motor revolution. Uncalibrated estimate; distances are shown with ≈. */
        private const val TRACK_CM_PER_REV = 10f

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
