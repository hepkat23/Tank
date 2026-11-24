package workshop.readingcompanion

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One plain black-on-white screen: which AI to use, its key and model, and the window size. */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("companion", Context.MODE_PRIVATE) }

    private lateinit var group: RadioGroup
    private lateinit var apiBox: LinearLayout
    private lateinit var note: TextView
    private lateinit var base: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var full: CheckBox

    /** Radio button id -> backend choice ("web:CHATGPT", "api:openrouter", ...). */
    private val choices = mutableMapOf<Int, String>()
    private var shownPreset: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        val current = Backends.current(prefs)
        choices.entries.firstOrNull { it.value == current }?.let { group.check(it.key) }
        full.isChecked = prefs.getBoolean("fullscreen", false)
        showFor(current)
    }

    private fun selected(): String = choices[group.checkedRadioButtonId] ?: Backends.current(prefs)

    /** Fills the API fields for [choice], keeping any edits to the previous preset. */
    private fun showFor(choice: String) {
        stashFields()
        if (!Backends.isApi(choice)) {
            apiBox.visibility = View.GONE
            shownPreset = null
            return
        }
        val s = ApiChat.settings(prefs, Backends.apiPreset(choice).id)
        apiBox.visibility = View.VISIBLE
        note.text = s.preset.note
        base.setText(s.base)
        key.setText(s.key)
        key.hint = if (s.preset.needsKey) "API key" else "API key (usually not needed)"
        model.setText(s.model)
        shownPreset = s.preset.id
    }

    private fun stashFields() {
        val id = shownPreset ?: return
        ApiChat.save(prefs, id, base.text.toString(), key.text.toString(), model.text.toString())
    }

    private fun save() {
        stashFields()
        val choice = selected()
        if (Backends.isApi(choice)) {
            val s = ApiChat.settings(prefs, Backends.apiPreset(choice).id)
            if (s.model.isBlank()) { toast("Pick a model first"); return }
            if (s.preset.needsKey && s.key.isBlank()) { toast("Paste your ${s.label} key first"); return }
        }
        Backends.set(prefs, choice)
        prefs.edit().putBoolean("fullscreen", full.isChecked).apply()
        finish()
    }

    private fun pickModel() {
        stashFields()
        val id = shownPreset ?: return
        val s = ApiChat.settings(prefs, id)
        toast("Asking ${s.label} for its models…")
        Thread {
            val result = runCatching { ApiChat.models(s) }
            runOnUiThread {
                result.onSuccess { list ->
                    if (list.isEmpty()) toast("The server listed no models")
                    else AlertDialog.Builder(this).setTitle("Model")
                        .setItems(list.toTypedArray()) { _, i -> model.setText(list[i]) }.show()
                }.onFailure { toast("Couldn't list models: ${it.message}") }
            }
        }.start()
    }

    private fun build(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 40)
            setBackgroundColor(Color.WHITE)
        }
        col.addView(text("Tank settings", 24f, bold = true))
        col.addView(text("Pick where your lookups go. Each book keeps its own chat.", 15f))

        col.addView(text("AI", 19f, bold = true, top = 28))
        group = RadioGroup(this)
        fun option(label: String, choice: String) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 17f
                setTextColor(Color.BLACK)
                setPadding(8, 14, 8, 14)
            }
            choices[rb.id] = choice
            group.addView(rb)
        }
        option("ChatGPT website: free, log in once (experimental)", "web:CHATGPT")
        option("Claude website: free, log in once with email code (experimental)", "web:CLAUDE")
        for (p in Backends.PRESETS) option(p.label + if (p.needsKey) ": API key" else "", "api:${p.id}")
        group.setOnCheckedChangeListener { _, _ -> showFor(selected()) }
        col.addView(group)

        apiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 0) }
        note = text("", 15f)
        base = field("Address", InputType.TYPE_TEXT_VARIATION_URI)
        key = field("API key", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        model = field("Model", InputType.TYPE_TEXT_VARIATION_NORMAL)
        apiBox.addView(note)
        apiBox.addView(text("Address", 15f, top = 12)); apiBox.addView(base)
        apiBox.addView(text("API key (stays on this device)", 15f, top = 12)); apiBox.addView(key)
        apiBox.addView(text("Model", 15f, top = 12)); apiBox.addView(model)
        apiBox.addView(button("Choose model from server") { pickModel() })
        col.addView(apiBox)

        col.addView(text("Window", 19f, bold = true, top = 28))
        full = CheckBox(this).apply {
            text = "Open full screen instead of a popup"
            textSize = 17f
            setTextColor(Color.BLACK)
        }
        col.addView(full)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 32, 0, 0) }
        row.addView(button("Save") { save() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(button("Cancel") { finish() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(text("Tank: a reading companion. Not affiliated with Onyx/BOOX, OpenAI or Anthropic.", 13f, top = 32))

        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(col, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.BLACK)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, top, 0, 4)
    }

    private fun field(hint: String, variation: Int) = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        textSize = 17f
        setTextColor(Color.BLACK)
        inputType = InputType.TYPE_CLASS_TEXT or variation
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
