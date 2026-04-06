package com.trainwidget.config

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.trainwidget.R
import com.trainwidget.prefs.WidgetPrefs
import com.trainwidget.widget.CommuteNotificationManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: WidgetPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = WidgetPrefs(this)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        setupServerSection()
        setupNotificationSection()
    }

    private fun setupServerSection() {
        val etServerUrl = findViewById<TextInputEditText>(R.id.et_server_url)
        val btnSave = findViewById<Button>(R.id.btn_save_server)

        etServerUrl.setText(prefs.serverUrl)

        btnSave.setOnClickListener {
            var url = etServerUrl.text?.toString()?.trim() ?: ""
            if (url.isBlank()) {
                Snackbar.make(btnSave, "Server URL is required", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            url = url.trimEnd('/')
            prefs.serverUrl = url
            etServerUrl.setText(url)
            Snackbar.make(btnSave, "Server URL saved", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupNotificationSection() {
        val switch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_notification_mode)
        switch.isChecked = prefs.notificationModeEnabled
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.notificationModeEnabled = isChecked
            if (!isChecked) {
                CommuteNotificationManager.clear(this)
            }
        }
    }
}
