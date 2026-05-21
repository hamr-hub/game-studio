package com.cocos.gamestudio

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val fpsSpinner = findViewById<Spinner>(R.id.fps_spinner)
        val shadowsSwitch = findViewById<SwitchMaterial>(R.id.shadows_switch)
        val vulkanSwitch = findViewById<SwitchMaterial>(R.id.vulkan_switch)

        val prefs = getSharedPreferences("engine_settings", Context.MODE_PRIVATE)

        // Setup FPS Spinner
        val fpsOptions = arrayOf("30", "60", "90", "120")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = adapter
        
        val currentFps = prefs.getInt("fps_limit", 60).toString()
        fpsSpinner.setSelection(fpsOptions.indexOf(currentFps).coerceAtLeast(1))

        // Setup Switches
        shadowsSwitch.isChecked = prefs.getBoolean("enable_shadows", true)
        vulkanSwitch.isChecked = prefs.getBoolean("use_vulkan", false)

        // Save on change
        fpsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("fps_limit", fpsOptions[position].toInt()).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        shadowsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_shadows", isChecked).apply()
        }

        vulkanSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_vulkan", isChecked).apply()
        }
    }
}
