package si.bolukba.flip

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Plain framework widgets, no libraries. */
class SettingsActivity : Activity() {

    private lateinit var driver: Driver
    private lateinit var list: LinearLayout
    private var d = 1f
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driver = Driver.get(this)
        d = resources.displayMetrics.density
        pad = (20 * d).toInt()
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        header("Stick layout")
        radios(Driver.MODE_HINTS.toList(), driver.mode) { driver.setMode(it) }
        hint("Press L2 + R2 together on the controller to cycle layouts.")

        header("Driving")
        for (t in Driver.TOGGLES) toggle(t.title, t.hint, driver.flag(t)) { driver.setFlag(t, it) }

        for (c in Driver.CHOICES) {
            header(c.title)
            radios(c.options, driver.option(c), horizontal = c.options.sumOf { it.length } <= 24) { driver.setOption(c, it) }
        }

        header("Controller haptics")
        for ((key, label) in Driver.HAPTICS) toggle(label, null, driver.hapticOn(key)) { driver.setHaptic(key, it) }

        header("Hub")
        val name = EditText(this).apply {
            hint = "New hub name (max 14 letters)"
            filters = arrayOf(InputFilter.LengthFilter(14))
            isSingleLine = true
        }
        list.addView(name)
        list.addView(button("Rename hub") {
            val n = name.text.toString().trim().filter { it.code in 32..126 }
            if (n.isNotEmpty()) driver.renameHub(n)
        })
        list.addView(button("Turn off hub") {
            AlertDialog.Builder(this)
                .setMessage("Turn the hub off? Press its button to turn it on again.")
                .setPositiveButton("Turn off") { _, _ ->
                    driver.powerOffHub()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })

        val scroll = ScrollView(this).apply {
            addView(list, MATCH_PARENT, WRAP_CONTENT)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)
        title = "Settings"
    }

    private fun header(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFFFC93C.toInt())
        setPadding(0, pad, 0, pad / 3)
    })

    private fun hint(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.7f
    })

    private fun radios(options: List<String>, checked: Int, horizontal: Boolean = false, onPick: (Int) -> Unit) {
        val group = RadioGroup(this).apply {
            orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
        }
        options.forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                id = i + 1
                text = label
                minHeight = (48 * d).toInt()
                setPadding(0, 0, (8 * d).toInt(), 0)
            })
        }
        group.check(checked + 1)
        group.setOnCheckedChangeListener { _, id -> onPick(id - 1) }
        list.addView(group)
    }

    private fun toggle(label: String, hint: String?, on: Boolean, onChange: (Boolean) -> Unit) {
        list.addView(Switch(this).apply {
            text = if (hint == null) label else "$label\n$hint"
            minHeight = (48 * d).toInt()
            gravity = Gravity.CENTER_VERTICAL
            isChecked = on
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        minHeight = (48 * d).toInt()
        setOnClickListener { onClick() }
    }
}
