package com.cocos.gamestudio

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GameOrientationLock.clear(this)
        setContentView(R.layout.activity_settings)

        val root = findViewById<View>(R.id.settings_root)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val fpsSpinner = findViewById<Spinner>(R.id.fps_spinner)
        val shadowsSwitch = findViewById<SwitchMaterial>(R.id.shadows_switch)
        val vulkanSwitch = findViewById<SwitchMaterial>(R.id.vulkan_switch)

        val prefs = getSharedPreferences("engine_settings", Context.MODE_PRIVATE)

        val fpsOptions = arrayOf("30", "60", "90", "120")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = adapter
        
        val currentFps = prefs.getInt("fps_limit", 60).toString()
        fpsSpinner.setSelection(fpsOptions.indexOf(currentFps).coerceAtLeast(1))

        shadowsSwitch.isChecked = prefs.getBoolean("enable_shadows", true)
        vulkanSwitch.isChecked = prefs.getBoolean("use_vulkan", false)

        var suppressInitialFpsEvent = true
        fpsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("fps_limit", fpsOptions[position].toInt()).apply()
                if (suppressInitialFpsEvent) {
                    suppressInitialFpsEvent = false
                    return
                }
                showSaved(root, getString(R.string.settings_saved_fps, fpsOptions[position]))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        shadowsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_shadows", isChecked).apply()
            val message = if (isChecked) R.string.settings_saved_shadows_on else R.string.settings_saved_shadows_off
            showSaved(root, getString(message))
        }

        vulkanSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_vulkan", isChecked).apply()
            val message = if (isChecked) R.string.settings_saved_vulkan_on else R.string.settings_saved_vulkan_off
            showSaved(root, getString(message))
        }
    }

    override fun onResume() {
        super.onResume()
        GameOrientationLock.clear(this)
    }

    private fun showSaved(root: View, message: String) {
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
        root.announceForAccessibility(message)
    }
}
