package si.bolukba.flip

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.view.InputDevice
import kotlin.math.atan2
import kotlin.math.sqrt

/** Controller light bar and motion sensor, through the framework InputDevice APIs (no root). */
class PadExtras {

    private var session: LightsManager.LightsSession? = null
    private var light: Light? = null
    private var lastColor = 0
    private var sensorPad: InputDevice? = null
    private var sensorOn = false

    /** Sideways tilt as a steer value, -1..1 (0 when tilt steering is off or unavailable). */
    var tilt = 0f
        private set

    fun attach(pad: InputDevice?) {
        runCatching { session?.close() }
        session = null
        light = null
        lastColor = 0
        val lm = pad?.lightsManager ?: return
        light = lm.lights.firstOrNull { it.hasRgbControl() }
        if (light != null) session = runCatching { lm.openSession() }.getOrNull()
    }

    /** 0xRRGGBB; 0 turns the bar off. */
    fun setColor(rgb: Int) {
        val s = session ?: return
        val l = light ?: return
        if (rgb == lastColor) return
        lastColor = rgb
        val req = LightsRequest.Builder()
            .addLight(l, LightState.Builder().setColor(if (rgb == 0) 0 else (0xFF000000.toInt() or rgb)).build())
            .build()
        runCatching { s.requestLights(req) }
    }

    fun setTiltEnabled(pad: InputDevice?, on: Boolean) {
        if (sensorOn && (sensorPad !== pad || !on)) {
            runCatching { sensorPad?.sensorManager?.unregisterListener(listener) }
            sensorOn = false
            tilt = 0f
        }
        if (!on || pad == null || sensorOn) return
        val sm = pad.sensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorOn = sm.registerListener(listener, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        sensorPad = pad
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
            // Roll: rotation around the controller's long axis, independent of how far it's pitched.
            val deg = Math.toDegrees(atan2(x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
            val v = deg / TILT_FULL_DEG
            tilt = if (kotlin.math.abs(v) < TILT_DEAD) 0f else v.coerceIn(-1f, 1f)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    companion object {
        private const val TILT_FULL_DEG = 40f
        private const val TILT_DEAD = 0.12f
    }
}
