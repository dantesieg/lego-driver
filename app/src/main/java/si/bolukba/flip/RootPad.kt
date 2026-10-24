package si.bolukba.flip

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Background gamepad input via root: runs the bundled evgrab helper with su, which grabs the
 * controller's /dev/input node exclusively (so other apps see nothing) and streams its events.
 */
class RootPad(private val ctx: Context, private val driver: Driver) {

    private val main = Handler(Looper.getMainLooper())
    private var proc: Process? = null
    private val ranges = HashMap<Int, IntArray>() // code -> min, max, flat

    /** Starts the reader; returns an error message, or null when running. */
    fun start(): String? {
        if (proc != null) return null
        val name = driver.pad?.name ?: driver.findPad()?.name ?: return "No controller"
        val node = findNode(name) ?: return "Controller node not found"
        val helper = File(ctx.applicationInfo.nativeLibraryDir, "libevgrab.so").path
        val p = runCatching { ProcessBuilder("su", "-c", "$helper $node").start() }.getOrNull()
            ?: return "Root not available"
        proc = p
        Thread {
            var ok = false
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line == "OK") ok = true
                    main.post { if (proc === p) handle(line) }
                }
            }
            main.post {
                if (proc === p) {
                    proc = null
                    driver.releaseInputs()
                }
            }
            if (!ok) android.util.Log.w("RootPad", "helper exited: " + p.errorStream.bufferedReader().readText())
        }.apply { isDaemon = true }.start()
        return null
    }

    fun stop() {
        val p = proc ?: return
        proc = null
        // Closing stdin makes the helper release its grab and exit.
        runCatching { p.outputStream.close() }
        p.destroy()
        driver.releaseInputs()
    }

    private fun handle(line: String) {
        val f = line.split(' ')
        when (f[0]) {
            "R" -> if (f.size == 5) ranges[f[1].toInt()] = intArrayOf(f[2].toInt(), f[3].toInt(), f[4].toInt())
            "A" -> if (f.size == 3) axis(f[1].toInt(), f[2].toInt())
            "K" -> if (f.size == 3) key(f[1].toInt(), f[2].toInt() != 0)
        }
    }

    private fun axis(code: Int, v: Int) {
        when (code) {
            ABS_X -> driver.lx = stick(code, v)
            ABS_RX -> driver.rx = stick(code, v)
            ABS_Y -> driver.ly = stick(code, v)
            ABS_RY -> driver.ry = stick(code, v)
            ABS_Z, ABS_BRAKE -> driver.l2 = trigger(code, v)
            ABS_RZ, ABS_GAS -> driver.r2 = trigger(code, v)
            ABS_HAT0X -> driver.hatX = v.toFloat().coerceIn(-1f, 1f)
            ABS_HAT0Y -> driver.hatY = v.toFloat().coerceIn(-1f, 1f)
        }
    }

    private fun stick(code: Int, v: Int): Float {
        val r = ranges[code] ?: return 0f
        val half = (r[1] - r[0]) / 2f
        val x = (v - (r[0] + half)) / half
        val dead = max(r[2] / half, DEADZONE)
        if (abs(x) <= dead) return 0f
        return (Math.signum(x) * (abs(x) - dead) / (1f - dead)).coerceIn(-1f, 1f)
    }

    private fun trigger(code: Int, v: Int): Float {
        val r = ranges[code] ?: return 0f
        val x = (v - r[0]).toFloat() / (r[1] - r[0])
        return if (x < DEADZONE) 0f else x.coerceAtMost(1f)
    }

    private fun key(code: Int, down: Boolean) {
        when (code) {
            BTN_DPAD_UP -> driver.dUp = down
            BTN_DPAD_DOWN -> driver.dDown = down
            BTN_DPAD_LEFT -> driver.dLeft = down
            BTN_DPAD_RIGHT -> driver.dRight = down
        }
        if (!down) return
        when (code) {
            BTN_TL -> driver.spin(-1)
            BTN_TR -> driver.spin(1)
            BTN_EAST -> driver.halt()
            BTN_WEST -> driver.halt()
            BTN_NORTH -> driver.setSpeed((driver.speed + 1) % 3)
        }
    }

    /** Maps the controller's name to its /dev/input/eventN via /proc/bus/input/devices. */
    private fun findNode(name: String): String? {
        val text = runCatching { File("/proc/bus/input/devices").readText() }.getOrNull()
            ?: runCatching {
                ProcessBuilder("su", "-c", "cat /proc/bus/input/devices").start().inputStream.bufferedReader().readText()
            }.getOrNull() ?: return null
        for (block in text.split("\n\n")) {
            val n = Regex("""N: Name="(.*)"""").find(block)?.groupValues?.get(1) ?: continue
            // Pick the main device, not its motion-sensor or touchpad siblings.
            if (n != name) continue
            val ev = Regex("""H: Handlers=.*?(event\d+)""").find(block)?.groupValues?.get(1) ?: continue
            return "/dev/input/$ev"
        }
        return null
    }

    companion object {
        private const val DEADZONE = 0.08f
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RY = 0x04
        private const val ABS_RZ = 0x05
        private const val ABS_GAS = 0x09
        private const val ABS_BRAKE = 0x0a
        private const val ABS_HAT0X = 0x10
        private const val ABS_HAT0Y = 0x11
        private const val BTN_EAST = 0x131 // Circle
        private const val BTN_NORTH = 0x133 // Triangle
        private const val BTN_WEST = 0x134 // Square
        private const val BTN_TL = 0x136
        private const val BTN_TR = 0x137
        private const val BTN_DPAD_UP = 0x220
        private const val BTN_DPAD_DOWN = 0x221
        private const val BTN_DPAD_LEFT = 0x222
        private const val BTN_DPAD_RIGHT = 0x223
    }
}
