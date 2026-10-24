package si.bolukba.flip

import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.InputDevice
import kotlin.math.abs

/**
 * Controller rumble. Event cues are short pulses on all motors; the engine feel maps each track's
 * power to one motor (DualSense: left = heavy, right = light), refreshed while driving.
 */
class Haptics {

    private var vm: VibratorManager? = null
    private var ids = IntArray(0)
    private var engineOn = false
    private var lastEngine = 0L
    private var lastL = 0
    private var lastR = 0
    private var cueUntil = 0L

    fun attach(pad: InputDevice?) {
        vm = pad?.vibratorManager
        ids = vm?.vibratorIds ?: IntArray(0)
    }

    /** One pulse on every motor; amp 1..255. */
    fun pulse(ms: Long, amp: Int = 200) = play(VibrationEffect.createOneShot(ms, amp), ms)

    /** Alternating on/off pulses, e.g. pulses(3) = three short ticks. */
    fun pulses(count: Int, onMs: Long = 40, offMs: Long = 70, amp: Int = 180) {
        val timings = LongArray(count * 2) { i -> if (i % 2 == 1) onMs else if (i == 0) 0 else offMs }
        val amps = IntArray(count * 2) { if (it % 2 == 0) 0 else amp }
        play(VibrationEffect.createWaveform(timings, amps, -1), timings.sum())
    }

    /** Called every drive tick with track powers -100..100. */
    fun engine(left: Int, right: Int) {
        val now = SystemClock.uptimeMillis()
        if (now < cueUntil) return // let an event cue finish
        if (left == 0 && right == 0) {
            if (engineOn) {
                engineOn = false
                runCatching { vm?.cancel() }
            }
            return
        }
        // Re-issue overlapping 160 ms bursts every 120 ms, sooner when power changes a lot.
        val changed = abs(left - lastL) > 15 || abs(right - lastR) > 15
        if (engineOn && !changed && now - lastEngine < 120) return
        engineOn = true
        lastEngine = now
        lastL = left
        lastR = right
        val m = vm ?: return
        if (ids.isEmpty()) return
        val c = if (ids.size >= 2) {
            CombinedVibration.startParallel()
                .addVibrator(ids[0], burst(left))
                .addVibrator(ids[1], burst(right))
                .combine()
        } else {
            CombinedVibration.createParallel(burst(maxOf(abs(left), abs(right))))
        }
        runCatching { m.vibrate(c) }
    }

    private fun burst(power: Int): VibrationEffect {
        val p = abs(power)
        // Subtle: idle-ish hum at low power, firmer near full.
        val amp = if (p == 0) 1 else (25 + p * 1.1f).toInt().coerceIn(1, 140)
        return VibrationEffect.createOneShot(160, amp)
    }

    private fun play(e: VibrationEffect, ms: Long) {
        val m = vm ?: return
        if (ids.isEmpty()) return
        engineOn = false
        cueUntil = SystemClock.uptimeMillis() + ms
        runCatching { m.vibrate(CombinedVibration.createParallel(e)) }
    }
}
