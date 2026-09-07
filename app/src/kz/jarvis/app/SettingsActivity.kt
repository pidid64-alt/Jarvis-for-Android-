package kz.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var etKey: EditText
    private lateinit var spModel: Spinner
    private lateinit var swTts: Switch
    private lateinit var swWake: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etKey = findViewById(R.id.etKey)
        spModel = findViewById(R.id.spModel)
        swTts = findViewById(R.id.swTts)
        swWake = findViewById(R.id.swWake)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        etKey.setText(Prefs.apiKey(this))

        val models = Prefs.MODELS
        spModel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, models
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = Prefs.model(this)
        spModel.setSelection(models.indexOf(current).coerceAtLeast(0))

        swTts.isChecked = Prefs.ttsOn(this)
        swWake.isChecked = Prefs.wakeOn(this)

        swTts.setOnCheckedChangeListener { _, checked -> Prefs.setTtsOn(this, checked) }

        swWake.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    swWake.isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 21)
                    return@setOnCheckedChangeListener
                }
                Prefs.setWakeOn(this, true)
                WakeWordService.start(this)
            } else {
                Prefs.setWakeOn(this, false)
                WakeWordService.stop(this)
            }
        }

        findViewById<Button>(R.id.btnCheckKey).setOnClickListener {
            val key = etKey.text.toString().trim()
            val model = models[spModel.selectedItemPosition.coerceAtLeast(0)]
            if (key.isEmpty()) {
                Toast.makeText(this, "Вставьте ключ, сэр", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show()
            Thread {
                val err = GeminiClient.ping(key, model)
                runOnUiThread {
                    if (err == null) {
                        Toast.makeText(this, R.string.key_ok, Toast.LENGTH_LONG).show()
                        Prefs.setApiKey(this, key)
                        Prefs.setModel(this, model)
                    } else {
                        Toast.makeText(this, getString(R.string.key_bad, err.take(120)), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 22)
            } else {
                Toast.makeText(this, "Доступ уже есть ✔", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.etKey)?.let {}
    }

    override fun onPause() {
        Prefs.setApiKey(this, etKey.text.toString())
        Prefs.setModel(this, Prefs.MODELS[spModel.selectedItemPosition.coerceAtLeast(0)])
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            21 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                swWake.isChecked = true
            }
            22 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Доступ к контактам выдан ✔", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
