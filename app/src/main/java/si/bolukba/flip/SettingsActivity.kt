package si.bolukba.flip

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Plain framework widgets, no libraries: stick layout plus one switch per haptic cue. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = Driver.get(this)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun header(text: String) = list.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFFFFC93C.toInt())
            setPadding(0, pad, 0, pad / 3)
        })

        header("Stick layout")
        val group = RadioGroup(this)
        Driver.MODE_HINTS.forEachIndexed { i, hint ->
            group.addView(RadioButton(this).apply {
                id = i + 1
                text = hint
                minHeight = (48 * d).toInt()
            })
        }
        group.check(driver.mode + 1)
        group.setOnCheckedChangeListener { _, id -> driver.setMode(id - 1) }
        list.addView(group)
        list.addView(TextView(this).apply {
            text = "Press L2 + R2 together on the controller to cycle layouts."
            textSize = 13f
            alpha = 0.7f
        })

        header("Controller haptics")
        for ((key, label) in Driver.HAPTICS) {
            list.addView(Switch(this).apply {
                text = label
                minHeight = (48 * d).toInt()
                gravity = Gravity.CENTER_VERTICAL
                isChecked = driver.hapticOn(key)
                setOnCheckedChangeListener { _, on -> driver.setHaptic(key, on) }
            })
        }

        setContentView(ScrollView(this).apply { addView(list, MATCH_PARENT, WRAP_CONTENT) })
        title = "Settings"
    }
}
