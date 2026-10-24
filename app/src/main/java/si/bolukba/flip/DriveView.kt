package si.bolukba.flip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Whole UI drawn on one canvas: connect overlay, two track sliders, status and action buttons. */
class DriveView(ctx: Context, private val actions: Actions) : View(ctx) {

    interface Actions {
        fun onSpin(dir: Int)
        fun onHalt()
        fun onSpeed(index: Int)
        fun onScan()
    }

    // Model, written by MainActivity before invalidate().
    var connected = false
    var status = ""
    var battery = -1
    var rssi = 0
    var padName: String? = null
    var arcade = false
    var flipped = false
    var spinning = false
    var speed = 1
    var outLeft = 0
    var outRight = 0

    /** Touch slider values, -1..1, read by the drive loop. */
    var touchLeft = 0f
        private set
    var touchRight = 0f
        private set

    private val d = resources.displayMetrics.density
    private val bg = 0xFF15171A.toInt()
    private val surface = 0xFF1E2125.toInt()
    private val raised = 0xFF22262B.toInt()
    private val line = 0xFF2E333A.toInt()
    private val text = 0xFFE9ECEF.toInt()
    private val muted = 0xFFA3AAB3.toInt()
    private val accent = 0xFFFFC93C.toInt()
    private val good = 0xFF4ADE80.toInt()
    private val danger = 0xFFF87171.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    private val sliderL = RectF()
    private val sliderR = RectF()
    private val spinL = RectF()
    private val spinR = RectF()
    private val stopBtn = RectF()
    private val scanBtn = RectF()
    private val speedBtns = Array(3) { RectF() }
    private val pointerSlider = HashMap<Int, Int>() // pointer id -> 0 left, 1 right

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = 24 * d
        val sw = 96 * d
        val cx = w / 2f
        // Speed segment row sits below the status visuals (bars end ~190dp) in both layouts.
        val segW = 80 * d
        if (h > w) {
            // Portrait: status and speed on top, sliders in the bottom half with the action buttons between them.
            val top = h * 0.5f
            sliderL.set(pad, top, pad + sw, h - pad)
            sliderR.set(w - pad - sw, top, w - pad, h - pad)
            val bl = sliderL.right + 12 * d
            val br = sliderR.left - 12 * d
            val bh = 56 * d
            spinL.set(bl, top, br, top + bh)
            spinR.set(bl, spinL.bottom + 12 * d, br, spinL.bottom + 12 * d + bh)
            stopBtn.set(bl, h - pad - bh, br, h - pad)
            val segTop = 220 * d
            for (i in 0..2) speedBtns[i].set(cx - segW * 1.5f + segW * i, segTop, cx - segW * 1.5f + segW * (i + 1), segTop + 44 * d)
            scanBtn.set(cx - 90 * d, h * 0.58f, cx + 90 * d, h * 0.58f + 52 * d)
            return
        }
        val top = 64 * d
        sliderL.set(pad, top, pad + sw, h - pad)
        sliderR.set(w - pad - sw, top, w - pad, h - pad)
        val bw = 120 * d
        val bh = 56 * d
        spinL.set(cx - bw - 8 * d, h - pad - bh * 2 - 12 * d, cx - 8 * d, h - pad - bh - 12 * d)
        spinR.set(cx + 8 * d, spinL.top, cx + bw + 8 * d, spinL.bottom)
        stopBtn.set(spinL.left, h - pad - bh, spinR.right, h - pad)
        val segTop = spinL.top - 60 * d
        for (i in 0..2) speedBtns[i].set(cx - segW * 1.5f + segW * i, segTop, cx - segW * 1.5f + segW * (i + 1), segTop + 44 * d)
        scanBtn.set(cx - 90 * d, h * 0.62f, cx + 90 * d, h * 0.62f + 52 * d)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(bg)
        drawTopBar(c)
        if (!connected) {
            drawConnect(c)
            return
        }
        drawSlider(c, sliderL, touchLeft, "LEFT")
        drawSlider(c, sliderR, touchRight, "RIGHT")
        drawCenter(c)
    }

    private fun drawTopBar(c: Canvas) {
        val y = 36 * d
        fill.color = if (connected) good else accent
        c.drawCircle(30 * d, y, 5 * d, fill)
        label.color = text
        label.textSize = 15 * d
        label.textAlign = Paint.Align.LEFT
        c.drawText(if (connected) "Technic Hub" else "Flip Driver", 44 * d, y + 5 * d, label)
        var x = width - 24 * d
        x = chip(c, x, y, if (battery >= 0) "$battery%" else "—", text)
        x = chip(c, x, y, padName?.let { if (arcade) "$it · arcade" else "$it · tank" } ?: "No controller", if (padName != null) accent else muted)
        if (connected && rssi != 0 && width > height) chip(c, x, y, "$rssi dBm", muted)
    }

    /** Draws a pill ending at [right]; returns the x where the next pill should end. */
    private fun chip(c: Canvas, right: Float, cy: Float, s: String, color: Int): Float {
        mono.textSize = 13 * d
        mono.textAlign = Paint.Align.CENTER
        val w = mono.measureText(s) + 24 * d
        val r = RectF(right - w, cy - 15 * d, right, cy + 15 * d)
        fill.color = raised
        c.drawRoundRect(r, 15 * d, 15 * d, fill)
        mono.color = color
        c.drawText(s, r.centerX(), cy + 5 * d, mono)
        return r.left - 8 * d
    }

    private fun drawConnect(c: Canvas) {
        label.textAlign = Paint.Align.CENTER
        label.color = muted
        label.textSize = 13 * d
        c.drawText("42140 · TRANSFORMATION VEHICLE", width / 2f, height * 0.34f, label)
        label.color = text
        label.textSize = 26 * d
        val maxW = width - 48 * d
        val tw = label.measureText(status)
        if (tw > maxW) label.textSize *= maxW / tw
        c.drawText(status, width / 2f, height * 0.46f, label)
        fill.color = accent
        c.drawRoundRect(scanBtn, 14 * d, 14 * d, fill)
        label.color = bg
        label.textSize = 16 * d
        c.drawText("Scan for hub", scanBtn.centerX(), scanBtn.centerY() + 6 * d, label)
    }

    private fun drawSlider(c: Canvas, r: RectF, v: Float, name: String) {
        fill.color = surface
        c.drawRoundRect(r, 24 * d, 24 * d, fill)
        stroke.color = line
        stroke.strokeWidth = 2 * d
        c.drawLine(r.left + 16 * d, r.centerY(), r.right - 16 * d, r.centerY(), stroke)
        val ky = r.centerY() - v * (r.height() / 2 - 36 * d)
        fill.color = accent
        c.drawCircle(r.centerX(), ky, 32 * d, fill)
        label.textAlign = Paint.Align.CENTER
        label.color = muted
        label.textSize = 11 * d
        c.drawText(name, r.centerX(), r.bottom + 0f - 10 * d, label)
    }

    private fun drawCenter(c: Canvas) {
        val cx = width / 2f
        val top = 70 * d
        // Output bars.
        val bh = 96 * d
        bar(c, cx - 80 * d, top, bh, outLeft, "L")
        bar(c, cx + 46 * d, top, bh, outRight, "R")
        // Orientation.
        fill.color = raised
        val o = RectF(cx - 30 * d, top + 10 * d, cx + 30 * d, top + 70 * d)
        c.drawRoundRect(o, 14 * d, 14 * d, fill)
        stroke.color = text
        stroke.strokeWidth = 2 * d
        val bodyTop = if (flipped) o.top + 30 * d else o.top + 16 * d
        c.drawRoundRect(RectF(o.left + 12 * d, bodyTop, o.right - 12 * d, bodyTop + 14 * d), 4 * d, 4 * d, stroke)
        val wy = if (flipped) o.top + 18 * d else o.top + 42 * d
        c.drawCircle(o.left + 18 * d, wy, 5 * d, stroke)
        c.drawCircle(o.right - 18 * d, wy, 5 * d, stroke)
        label.textAlign = Paint.Align.CENTER
        label.textSize = 12 * d
        label.color = if (flipped) accent else muted
        c.drawText(if (spinning) "Spinning" else if (flipped) "Flipped" else "Upright", cx, o.bottom + 18 * d, label)
        // Speed modes.
        val names = arrayOf("Eco", "Normal", "Turbo")
        for (i in 0..2) {
            val r = speedBtns[i]
            fill.color = if (i == speed) accent else surface
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            label.color = if (i == speed) bg else text
            label.textSize = 13 * d
            c.drawText(names[i], r.centerX(), r.centerY() + 5 * d, label)
        }
        button(c, spinL, "↺ Spin", raised, text)
        button(c, spinR, "Spin ↻", raised, text)
        fill.color = bg
        c.drawRoundRect(stopBtn, 14 * d, 14 * d, fill)
        stroke.color = danger
        c.drawRoundRect(stopBtn, 14 * d, 14 * d, stroke)
        label.color = danger
        label.textSize = 15 * d
        c.drawText("Stop", stopBtn.centerX(), stopBtn.centerY() + 5 * d, label)
    }

    private fun bar(c: Canvas, x: Float, top: Float, h: Float, v: Int, name: String) {
        val r = RectF(x, top, x + 34 * d, top + h)
        fill.color = surface
        c.drawRoundRect(r, 10 * d, 10 * d, fill)
        fill.color = accent
        val mid = r.centerY()
        val ext = (h / 2) * (v / 100f)
        c.drawRect(r.left + 4 * d, minOf(mid, mid - ext), r.right - 4 * d, maxOf(mid, mid - ext), fill)
        mono.textAlign = Paint.Align.CENTER
        mono.color = text
        mono.textSize = 12 * d
        c.drawText("$name $v", r.centerX(), r.bottom + 16 * d, mono)
    }

    private fun button(c: Canvas, r: RectF, s: String, bgColor: Int, fg: Int) {
        fill.color = bgColor
        c.drawRoundRect(r, 14 * d, 14 * d, fill)
        label.color = fg
        label.textSize = 15 * d
        label.textAlign = Paint.Align.CENTER
        c.drawText(s, r.centerX(), r.centerY() + 5 * d, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i)
                val y = e.getY(i)
                if (!connected) {
                    if (scanBtn.contains(x, y)) actions.onScan()
                    return true
                }
                when {
                    sliderL.contains(x, y) -> pointerSlider[id] = 0
                    sliderR.contains(x, y) -> pointerSlider[id] = 1
                    spinL.contains(x, y) -> actions.onSpin(-1)
                    spinR.contains(x, y) -> actions.onSpin(1)
                    stopBtn.contains(x, y) -> actions.onHalt()
                    else -> speedBtns.indexOfFirst { it.contains(x, y) }.let { if (it >= 0) actions.onSpeed(it) }
                }
                updateSliders(e)
            }
            MotionEvent.ACTION_MOVE -> updateSliders(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // Sliders spring back to zero on release.
                when (pointerSlider.remove(id)) {
                    0 -> touchLeft = 0f
                    1 -> touchRight = 0f
                }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerSlider.clear()
                    touchLeft = 0f
                    touchRight = 0f
                }
                invalidate()
            }
        }
        return true
    }

    private fun updateSliders(e: MotionEvent) {
        for (p in 0 until e.pointerCount) {
            val which = pointerSlider[e.getPointerId(p)] ?: continue
            val r = if (which == 0) sliderL else sliderR
            val v = ((r.centerY() - e.getY(p)) / (r.height() / 2 - 36 * d)).coerceIn(-1f, 1f)
            if (which == 0) touchLeft = v else touchRight = v
        }
        invalidate()
    }
}
