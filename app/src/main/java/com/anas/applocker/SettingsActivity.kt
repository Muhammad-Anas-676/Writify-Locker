package com.anas.applocker

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var pinManager: PinManager
    private lateinit var lockedAppsStore: LockedAppsStore

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeBackup(uri)
        }

    private val openBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBackup(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsStore = SettingsStore(this)
        pinManager = PinManager(this)
        lockedAppsStore = LockedAppsStore(this)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        setupColorSwatches()
        setupIconAliasOptions()
        setupDecoySwitch()
        setupAutoRelockOptions()

        findViewById<TextView>(R.id.changePinRow).setOnClickListener { showChangePinDialog() }
        findViewById<TextView>(R.id.backupButton).setOnClickListener {
            createBackupLauncher.launch("writify_backup.json")
        }
        findViewById<TextView>(R.id.restoreButton).setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    // ---------- Appearance ----------

    private fun setupColorSwatches() {
        val row = findViewById<LinearLayout>(R.id.colorSwatchRow)
        row.removeAllViews()
        val selectedId = settingsStore.getAccentColorId()
        val sizePx = (40 * resources.displayMetrics.density).toInt()
        val marginPx = (10 * resources.displayMetrics.density).toInt()

        ThemeManager.PALETTE.forEach { swatch ->
            val view = View(this)
            val params = LinearLayout.LayoutParams(sizePx, sizePx)
            params.marginEnd = marginPx
            view.layoutParams = params

            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(swatch.color)
            if (swatch.id == selectedId) {
                drawable.setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
            }
            view.background = drawable

            view.setOnClickListener {
                settingsStore.setAccentColorId(swatch.id)
                Toast.makeText(this, "${swatch.label} applied", Toast.LENGTH_SHORT).show()
                setupColorSwatches()
            }
            row.addView(view)
        }
    }

    // ---------- App Icon ----------

    private fun setupIconAliasOptions() {
        val group = findViewById<RadioGroup>(R.id.iconAliasGroup)
        group.removeAllViews()
        val selected = settingsStore.getIconAliasId()

        IconAliasManager.OPTIONS.forEach { option ->
            val radio = RadioButton(this)
            radio.text = option.label
            radio.setTextColor(resources.getColor(R.color.text, theme))
            radio.id = View.generateViewId()
            radio.isChecked = option.id == selected
            radio.tag = option.id
            group.addView(radio)
        }

        group.setOnCheckedChangeListener { rg, checkedId ->
            val radio = rg.findViewById<RadioButton>(checkedId)
            val id = radio?.tag as? String ?: return@setOnCheckedChangeListener
            IconAliasManager.setActiveAlias(this, id)
            Toast.makeText(this, "Home screen icon updated", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Security ----------

    private fun showChangePinDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad, pad, pad)

        val realInput = EditText(this).apply {
            hint = "New real PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val fakeInput = EditText(this).apply {
            hint = "New fake (decoy) PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        container.addView(realInput)
        container.addView(fakeInput)

        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val real = realInput.text.toString()
                val fake = fakeInput.text.toString()
                when {
                    real.length < 4 || fake.length < 4 ->
                        Toast.makeText(this, "PINs must be at least 4 digits", Toast.LENGTH_LONG).show()
                    real == fake ->
                        Toast.makeText(this, "Real and fake PIN must be different", Toast.LENGTH_LONG).show()
                    else -> {
                        pinManager.setupPins(real, fake)
                        Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupDecoySwitch() {
        val switch = findViewById<Switch>(R.id.decoyModeSwitch)
        switch.isChecked = settingsStore.isDecoyModeEnabled()
        switch.setOnCheckedChangeListener { _, isChecked ->
            settingsStore.setDecoyModeEnabled(isChecked)
        }
    }

    // ---------- Auto re-lock ----------

    private fun setupAutoRelockOptions() {
        val group = findViewById<RadioGroup>(R.id.autoRelockGroup)
        group.removeAllViews()
        val options = listOf(5L to "5 seconds", 15L to "15 seconds (default)", 30L to "30 seconds", 60L to "1 minute", 300L to "5 minutes")
        val current = settingsStore.getAutoRelockSeconds()

        options.forEach { (seconds, label) ->
            val radio = RadioButton(this)
            radio.text = label
            radio.setTextColor(resources.getColor(R.color.text, theme))
            radio.id = View.generateViewId()
            radio.isChecked = seconds == current
            radio.tag = seconds
            group.addView(radio)
        }

        group.setOnCheckedChangeListener { rg, checkedId ->
            val radio = rg.findViewById<RadioButton>(checkedId)
            val seconds = radio?.tag as? Long ?: return@setOnCheckedChangeListener
            settingsStore.setAutoRelockSeconds(seconds)
        }
    }

    // ---------- Backup & Restore ----------

    private fun writeBackup(uri: android.net.Uri) {
        try {
            val json = JSONObject()
            val settingsJson = JSONObject()
            settingsStore.exportToMap().forEach { (k, v) -> settingsJson.put(k, v) }
            json.put("settings", settingsJson)
            json.put("lockedApps", JSONArray(lockedAppsStore.getLockedPackages().toList()))
            json.put("backupVersion", 1)

            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toString(2).toByteArray())
            }
            Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBackup(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Could not read file")
            val json = JSONObject(text)

            val settingsJson = json.optJSONObject("settings")
            if (settingsJson != null) {
                val map = mutableMapOf<String, String>()
                settingsJson.keys().forEach { key -> map[key] = settingsJson.getString(key) }
                settingsStore.importFromMap(map)
            }

            val lockedAppsJson = json.optJSONArray("lockedApps")
            if (lockedAppsJson != null) {
                for (i in 0 until lockedAppsJson.length()) {
                    lockedAppsStore.setLocked(lockedAppsJson.getString(i), true)
                }
            }

            IconAliasManager.applyStoredAlias(this)
            Toast.makeText(this, "Restore complete", Toast.LENGTH_SHORT).show()
            setupColorSwatches()
            setupIconAliasOptions()
            setupDecoySwitch()
            setupAutoRelockOptions()
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
