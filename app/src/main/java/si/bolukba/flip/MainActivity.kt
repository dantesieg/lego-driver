package si.bolukba.flip

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/** UI and foreground input; all driving logic lives in [Driver]. */
class MainActivity : Activity(), Driver.Ui, DriveView.Actions, InputManager.InputDeviceListener {

    private lateinit var driver: Driver
    private lateinit var view: DriveView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = DriveView(this, this)
        setContentView(view)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        driver = Driver.get(this)
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    /** Losing focus (shade, split screen, dialogs) means no more release events: zero everything. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) driver.releaseInputs()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        driver.findPad()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        // A controller that drops mid-press never sends its release.
        if (driver.pad?.id == deviceId) driver.releaseInputs()
        driver.findPad()
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    override fun onStart() {
        super.onStart()
        // Back in front: Android delivers the controller again, so drop the root reader.
        stopService(Intent(this, HubService::class.java))
        driver.ui = this
        driver.findPad()
        onChange()
        if (!hasBtPermissions()) requestPermissions(PERMISSIONS, 1)
        else if (!driver.parked) driver.hub.start()
    }

    override fun onStop() {
        super.onStop()
        if (driver.ui === this) driver.ui = null
        if (isChangingConfigurations) return
        // Held buttons/sliders never see their release once we lose focus.
        driver.releaseInputs()
        if (driver.hub.state == Hub.State.IDLE) return
        if (isFinishing) {
            driver.halt()
            driver.hub.stop()
        } else {
            startForegroundService(Intent(this, HubService::class.java))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (hasBtPermissions()) driver.hub.start() else {
            view.status = "Bluetooth permission needed"
            view.invalidate()
        }
    }

    private fun hasBtPermissions() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onChange() {
        val hub = driver.hub
        view.connected = hub.state == Hub.State.READY
        view.idle = hub.state == Hub.State.IDLE
        view.status = hub.detail
        view.battery = driver.battery
        view.rssi = driver.rssi
        view.padName = driver.pad?.let { padLabel(it) }
        view.modeName = Driver.MODE_NAMES[driver.mode]
        view.speed = driver.speed
        view.flipped = driver.flipped
        view.spinning = driver.spinning
        view.outLeft = driver.outLeft
        view.outRight = driver.outRight
        view.invalidate()
    }

    /** Touch sliders feed the driver whenever the view redraws them. */
    override fun onSliders(left: Float, right: Float) {
        driver.touchLeft = left
        driver.touchRight = right
    }

    override fun onSpin(dir: Int) = driver.spin(dir)

    override fun onHalt() = driver.halt()

    override fun onSpeed(index: Int) = driver.setSpeed(index)

    override fun onMode() = driver.nextMode()

    override fun onScan() {
        driver.parked = false
        if (hasBtPermissions()) driver.hub.forget() else requestPermissions(PERMISSIONS, 1)
    }

    override fun onConnect() {
        driver.parked = false
        if (hasBtPermissions()) driver.hub.start() else requestPermissions(PERMISSIONS, 1)
    }

    /** Brakes and drops the link; stays disconnected until Connect is pressed. */
    override fun onDisconnect() {
        driver.parked = true
        driver.halt()
        driver.hub.stop()
    }

    // ---- Gamepad (foreground) --------------------------------------------------------------

    private fun padLabel(d: InputDevice) = when {
        d.name.contains("DualSense", true) || d.vendorId == 0x054C && d.productId in setOf(0x0CE6, 0x0DF2) -> "DualSense"
        d.vendorId == 0x054C -> "DualShock"
        else -> d.name.take(16)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(e)
        }
        if (driver.pad?.id != e.deviceId) driver.findPad()
        driver.lx = axis(e, MotionEvent.AXIS_X)
        driver.rx = axis(e, MotionEvent.AXIS_Z)
        driver.ly = axis(e, MotionEvent.AXIS_Y)
        driver.ry = axis(e, MotionEvent.AXIS_RZ)
        driver.l2 = max(axis(e, MotionEvent.AXIS_LTRIGGER), axis(e, MotionEvent.AXIS_BRAKE))
        driver.r2 = max(axis(e, MotionEvent.AXIS_RTRIGGER), axis(e, MotionEvent.AXIS_GAS))
        // D-pad on many pads arrives as a hat axis.
        driver.hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        driver.hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        return true
    }

    private fun axis(e: MotionEvent, a: Int): Float {
        val range = e.device?.getMotionRange(a, e.source) ?: return 0f
        val v = e.getAxisValue(a)
        val dead = max(range.flat, DEADZONE)
        if (abs(v) <= dead) return 0f
        // Rescale so output starts at 0 just outside the dead zone.
        return (Math.signum(v) * (abs(v) - dead) / (1f - dead)).coerceIn(-1f, 1f)
    }

    /** Tracks held D-pad keys; returns false for other keys. */
    private fun dpad(keyCode: Int, down: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> driver.dUp = down
            KeyEvent.KEYCODE_DPAD_DOWN -> driver.dDown = down
            KeyEvent.KEYCODE_DPAD_LEFT -> driver.dLeft = down
            KeyEvent.KEYCODE_DPAD_RIGHT -> driver.dRight = down
            else -> return false
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (Driver.isPad(e.device) && dpad(keyCode, false)) return true
        return super.onKeyUp(keyCode, e)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        if (!Driver.isPad(e.device)) return super.onKeyDown(keyCode, e)
        if (driver.pad?.id != e.deviceId) driver.findPad()
        if (dpad(keyCode, true) || e.repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> driver.spin(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> driver.spin(1)
            KeyEvent.KEYCODE_BUTTON_B -> driver.halt() // Circle (alias)
            KeyEvent.KEYCODE_BUTTON_X -> driver.halt() // Square
            KeyEvent.KEYCODE_BUTTON_Y -> driver.setSpeed((driver.speed + 1) % 3) // Triangle
            KeyEvent.KEYCODE_BUTTON_START -> onScan()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    companion object {
        private val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        private const val DEADZONE = 0.08f
    }
}
