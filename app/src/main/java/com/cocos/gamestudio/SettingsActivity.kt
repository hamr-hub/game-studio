package com.cocos.gamestudio

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
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

        val fpsToggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.fps_toggle_group)
        val shadowsSwitch = findViewById<SwitchMaterial>(R.id.shadows_switch)
        val vulkanSwitch = findViewById<SwitchMaterial>(R.id.vulkan_switch)
        val shadowsRow = findViewById<View>(R.id.shadows_row)
        val vulkanRow = findViewById<View>(R.id.vulkan_row)

        val prefs = getSharedPreferences("engine_settings", Context.MODE_PRIVATE)

        val fpsButtonMap = mapOf(
            R.id.fps_30_btn to 30,
            R.id.fps_60_btn to 60,
            R.id.fps_90_btn to 90,
            R.id.fps_120_btn to 120,
        )
        val currentFps = prefs.getInt("fps_limit", 60)
        val checkedButtonId = fpsButtonMap.entries.firstOrNull { it.value == currentFps }?.key ?: R.id.fps_60_btn
        fpsToggleGroup.check(checkedButtonId)

        shadowsSwitch.isChecked = prefs.getBoolean("enable_shadows", true)
        vulkanSwitch.isChecked = prefs.getBoolean("use_vulkan", false)

        fpsToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val fps = fpsButtonMap[checkedId] ?: return@addOnButtonCheckedListener
                prefs.edit().putInt("fps_limit", fps).apply()
                showSaved(root, getString(R.string.settings_saved_fps, fps.toString()))
            }
        }

        shadowsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_shadows", isChecked).apply()
            val message = if (isChecked) R.string.settings_saved_shadows_on else R.string.settings_saved_shadows_off
            showSaved(root, getString(message))
        }
        shadowsRow.setOnClickListener {
            shadowsSwitch.isChecked = !shadowsSwitch.isChecked
        }

        vulkanSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_vulkan", isChecked).apply()
            val message = if (isChecked) R.string.settings_saved_vulkan_on else R.string.settings_saved_vulkan_off
            showSaved(root, getString(message))
        }
        vulkanRow.setOnClickListener {
            vulkanSwitch.isChecked = !vulkanSwitch.isChecked
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
