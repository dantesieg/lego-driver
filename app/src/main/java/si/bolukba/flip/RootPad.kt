package si.bolukba.flip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Background gamepad input via root: runs the bundled evgrab helper with su, which grabs the
 * controller's /dev/input node exclusively (so other apps see nothing) and streams full state
 * snapshots. Fail-safe: inputs are zeroed whenever snapshots stop arriving, and the reader
 * re-attaches by itself when the controller comes back.
 */
class RootPad(private val ctx: Context, private val driver: Driver) {

    private val main = Handler(Looper.getMainLooper())
    private var proc: Process? = null
    private var active = false
    private var lastLine = 0L
    private var lastAttempt = 0L
    private val axisOrder = ArrayList<Int>()
    private val ranges = HashMap<Int, IntArray>() // code -> min, max, flat
    private var held = emptySet<Int>()

    /** Starts reading (and keeps re-attaching); returns why it can't run right now, or null. */
    fun start(): String? {
        active = true
        main.removeCallbacks(watchdog)
        main.post(watchdog)
        lastAttempt = SystemClock.uptimeMillis()
        return attach()
    }

    fun stop() {
        active = false
        main.removeCallbacks(watchdog)
        kill()
    }

    private fun attach(): String? {
        if (proc != null) return null
        val name = driver.findPad()?.name ?: return "No controller"
        val node = findNode(name) ?: return "Controller node not found"
        val helper = File(ctx.applicationInfo.nativeLibraryDir, "libevgrab.so").path
        // pkill clears helpers left over from an earlier run: they would hold the grab.
        val p = runCatching { ProcessBuilder("su", "-c", "pkill -x libevgrab.so; exec $helper $node").start() }.getOrNull()
            ?: return "Root not available"
        proc = p
        axisOrder.clear()
        ranges.clear()
        held = emptySet()
        lastLine = SystemClock.uptimeMillis()
        Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    main.post { if (proc === p) handle(line) }
                }
            }
            val err = runCatching { p.errorStream.bufferedReader().readText().trim() }.getOrDefault("")
            main.post {
                if (proc === p) {
                    Log.w(TAG, "helper exited: $err")
                    proc = null
                    held = emptySet()
                    driver.releaseInputs()
                }
            }
        }.apply { isDaemon = true }.start()
        return null
    }

    private fun kill() {
        val p = proc ?: return
        proc = null
        // Closing stdin makes the helper release its grab and exit; it also dies with su.
        runCatching { p.outputStream.close() }
        p.destroy()
        held = emptySet()
        driver.releaseInputs()
    }

    /** Every 250 ms: zero inputs if the stream went silent, re-attach if the helper is gone. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.uptimeMillis()
            if (proc != null && now - lastLine > STALE_MS) {
                Log.w(TAG, "no snapshot for ${STALE_MS}ms, releasing")
                kill()
            }
            if (proc == null && now - lastAttempt > RETRY_MS) {
                lastAttempt = now
                attach()
            }
            main.postDelayed(this, 250)
        }
    }

    private fun handle(line: String) {
        lastLine = SystemClock.uptimeMillis()
        val f = line.split(' ')
        when (f[0]) {
            "R" -> if (f.size == 5) {
                val code = f[1].toInt()
                axisOrder.add(code)
                ranges[code] = intArrayOf(f[2].toInt(), f[3].toInt(), f[4].toInt())
            }
            "S" -> snapshot(f)
        }
    }

    private fun snapshot(f: List<String>) {
        val bar = f.indexOf("|")
        if (bar < 0 || bar - 1 != axisOrder.size) return
        for (i in axisOrder.indices) axis(axisOrder[i], f[i + 1].toIntOrNull() ?: return)
        val now = f.subList(bar + 1, f.size).mapNotNull { it.toIntOrNull() }.toSet()
        driver.dUp = BTN_DPAD_UP in now
        driver.dDown = BTN_DPAD_DOWN in now
        driver.dLeft = BTN_DPAD_LEFT in now
        driver.dRight = BTN_DPAD_RIGHT in now
        // Actions fire on press edges.
        for (code in now - held) press(code)
        held = now
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

    private fun press(code: Int) {
        when (code) {
            BTN_TL -> driver.spin(-1)
            BTN_TR -> driver.spin(1)
            BTN_WEST, BTN_EAST -> driver.halt() // Square, Circle
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
            // Exact match picks the main device, not its motion-sensor or touchpad siblings.
            if (n != name) continue
            val ev = Regex("""H: Handlers=.*?(event\d+)""").find(block)?.groupValues?.get(1) ?: continue
            return "/dev/input/$ev"
        }
        return null
    }

    companion object {
        private const val TAG = "RootPad"
        private const val STALE_MS = 500L
        private const val RETRY_MS = 2000L
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
