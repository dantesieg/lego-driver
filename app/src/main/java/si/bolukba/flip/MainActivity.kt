package si.bolukba.flip

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationEffect
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity(), Hub.Listener, DriveView.Actions, Choreographer.FrameCallback {

    private lateinit var hub: Hub
    private lateinit var view: DriveView

    // Gamepad state, -1..1.
    private var lx = 0f
    private var ly = 0f
    private var ry = 0f
    private var l2 = 0f
    private var r2 = 0f
    private var pad: InputDevice? = null
    private var arcade = false

    private var speed = 1
    private var yaw = 0
    private var flipped = false

    private var spinDir = 0
    private var spinStartYaw = 0
    private var spinStartMs = 0L

    private var lastL = Int.MIN_VALUE
    private var lastR = Int.MIN_VALUE
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = DriveView(this, this)
        setContentView(view)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hub = Hub(this, this)
        arcade = getPreferences(MODE_PRIVATE).getBoolean("arcade", false)
        speed = getPreferences(MODE_PRIVATE).getInt("speed", 1)
        view.arcade = arcade
        view.speed = speed
        findPad()
    }

    override fun onStart() {
        super.onStart()
        if (hasBtPermissions()) hub.start()
        else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onStop() {
        super.onStop()
        running = false
        hub.stop()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (hasBtPermissions()) hub.start() else onState(Hub.State.IDLE, "Bluetooth permission needed")
    }

    private fun hasBtPermissions() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---- Drive loop, once per display frame -------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        if (hub.state != Hub.State.READY) return

        if (spinDir != 0) {
            val turned = abs(angleDiff(yaw, spinStartYaw))
            // Stop early to absorb coast-on overshoot (app uses 180 - 50..65).
            if (turned < 180 - SPIN_ADJUST && SystemClock.uptimeMillis() - spinStartMs < 6000) {
                return
            }
            spinDir = 0
            hub.brake()
            lastL = Int.MIN_VALUE
            lastR = Int.MIN_VALUE
        }

        // Gamepad wins whenever any of its inputs is active; otherwise the touch sliders.
        var left: Float
        var right: Float
        if (arcade) {
            val throttle = if (r2 > 0f || l2 > 0f) r2 - l2 else -ly
            val steer = lx
            left = throttle + steer
            right = throttle - steer
            val m = max(1f, max(abs(left), abs(right)))
            left /= m
            right /= m
        } else {
            left = -ly
            right = -ry
        }
        if (left == 0f && right == 0f) {
            left = view.touchLeft
            right = view.touchRight
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
    }

    private fun show(l: Int, r: Int) {
        if (view.outLeft == l && view.outRight == r && view.spinning == (spinDir != 0)) return
        view.outLeft = l
        view.outRight = r
        view.spinning = spinDir != 0
        view.invalidate()
    }

    // ---- UI actions ------------------------------------------------------------------------

    override fun onSpin(dir: Int) {
        if (spinDir != 0 || hub.state != Hub.State.READY) return
        spinDir = dir
        spinStartYaw = yaw
        spinStartMs = SystemClock.uptimeMillis()
        // Same raw sign on both ports = pivot turn, as the original app does.
        hub.setRaw(dir * 100, dir * 100)
        show(dir * 100, -dir * 100)
    }

    override fun onHalt() {
        spinDir = 0
        hub.brake()
        lastL = 0
        lastR = 0
        show(0, 0)
    }

    override fun onSpeed(index: Int) {
        speed = index.coerceIn(0, 2)
        getPreferences(MODE_PRIVATE).edit().putInt("speed", speed).apply()
        lastL = Int.MIN_VALUE
        view.speed = speed
        view.invalidate()
    }

    override fun onScan() {
        if (hasBtPermissions()) hub.forget()
        else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    // ---- Hub callbacks ---------------------------------------------------------------------

    override fun onState(state: Hub.State, detail: String) {
        view.connected = state == Hub.State.READY
        view.status = detail
        lastL = Int.MIN_VALUE
        lastR = Int.MIN_VALUE
        if (state != Hub.State.READY) spinDir = 0
        if (state == Hub.State.READY) rumble(80)
        view.invalidate()
    }

    override fun onBattery(percent: Int) {
        view.battery = percent
        view.invalidate()
    }

    override fun onRssi(rssi: Int) {
        view.rssi = rssi
        view.invalidate()
    }

    override fun onOrientation(yaw: Int, pitch: Int, roll: Int) {
        this.yaw = yaw
        // App flips at |roll| > 90 with no hysteresis; add a band so it does not chatter at 90.
        val now = if (flipped) abs(roll) > 80 else abs(roll) > 100
        if (now != flipped) {
            flipped = now
            lastL = Int.MIN_VALUE
            view.flipped = now
            view.invalidate()
            rumble(150)
        }
    }

    // ---- Gamepad ---------------------------------------------------------------------------

    private fun isPad(dev: InputDevice?) = dev != null &&
        (dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    private fun findPad() {
        pad = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.firstOrNull { isPad(it) && !it!!.isVirtual }
        view.padName = pad?.let { padLabel(it) }
        view.invalidate()
    }

    private fun padLabel(d: InputDevice) = when {
        d.name.contains("DualSense", true) || d.vendorId == 0x054C && d.productId in setOf(0x0CE6, 0x0DF2) -> "DualSense"
        d.vendorId == 0x054C -> "DualShock"
        else -> d.name.take(16)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(e)
        }
        if (pad?.id != e.deviceId) findPad()
        lx = axis(e, MotionEvent.AXIS_X)
        ly = axis(e, MotionEvent.AXIS_Y)
        ry = axis(e, MotionEvent.AXIS_RZ)
        l2 = max(axis(e, MotionEvent.AXIS_LTRIGGER), axis(e, MotionEvent.AXIS_BRAKE))
        r2 = max(axis(e, MotionEvent.AXIS_RTRIGGER), axis(e, MotionEvent.AXIS_GAS))
        // D-pad on many pads arrives as a hat axis.
        val hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatY != lastHatY) {
            if (hatY < -0.5f) onSpeed(speed + 1) else if (hatY > 0.5f) onSpeed(speed - 1)
            lastHatY = hatY
        }
        return true
    }

    private var lastHatY = 0f

    private fun axis(e: MotionEvent, a: Int): Float {
        val range = e.device?.getMotionRange(a, e.source) ?: return 0f
        val v = e.getAxisValue(a)
        val dead = max(range.flat, DEADZONE)
        if (abs(v) <= dead) return 0f
        // Rescale so output starts at 0 just outside the dead zone.
        return (Math.signum(v) * (abs(v) - dead) / (1f - dead)).coerceIn(-1f, 1f)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        if (!isPad(e.device)) return super.onKeyDown(keyCode, e)
        if (pad?.id != e.deviceId) findPad()
        if (e.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> onSpin(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> onSpin(1)
            KeyEvent.KEYCODE_BUTTON_B -> onHalt() // Circle
            KeyEvent.KEYCODE_BUTTON_X -> { // Square: tank <-> arcade
                arcade = !arcade
                getPreferences(MODE_PRIVATE).edit().putBoolean("arcade", arcade).apply()
                view.arcade = arcade
                view.invalidate()
                rumble(40)
            }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DPAD_UP -> onSpeed(if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) (speed + 1) % 3 else speed + 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> onSpeed(speed - 1)
            KeyEvent.KEYCODE_BUTTON_START -> onScan()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    private fun rumble(ms: Long) {
        val vm = pad?.vibratorManager ?: return
        if (vm.vibratorIds.isEmpty()) return
        runCatching { vm.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))) }
    }

    companion object {
        private val SPEEDS = intArrayOf(40, 70, 100)
        private const val SPIN_ADJUST = 55
        private const val DEADZONE = 0.08f

        private fun angleDiff(a: Int, b: Int): Int {
            var x = (a - b) % 360
            if (x > 180) x -= 360
            if (x < -180) x += 360
            return x
        }
    }
}
