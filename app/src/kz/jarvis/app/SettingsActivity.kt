package kz.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var spProvider: Spinner
    private lateinit var tvProviderDesc: TextView
    private lateinit var etBaseUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var tvKeyDesc: TextView
    private lateinit var etModel: EditText
    private lateinit var tvModelHint: TextView
    private lateinit var swTts: Switch
    private lateinit var swWake: Switch
    private lateinit var tvBgStatus: TextView

    /** Провайдер, чьи настройки сейчас показаны в полях. */
    private var activeProvider: Providers.Provider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spProvider = findViewById(R.id.spProvider)
        tvProviderDesc = findViewById(R.id.tvProviderDesc)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etKey = findViewById(R.id.etKey)
        tvKeyDesc = findViewById(R.id.tvKeyDesc)
        etModel = findViewById(R.id.etModel)
        tvModelHint = findViewById(R.id.tvModelHint)
        swTts = findViewById(R.id.swTts)
        swWake = findViewById(R.id.swWake)
        tvBgStatus = findViewById(R.id.tvBgStatus)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // --- провайдер ИИ ---
        spProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, Providers.names()
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spProvider.setSelection(Providers.indexOf(Prefs.provider(this).id))
        showProvider(Prefs.provider(this))

        // Спиннер сообщает о выборе и при первой отрисовке — поэтому смотрим,
        // поменялся ли провайдер на самом деле.
        spProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long
            ) {
                val chosen = Providers.ALL[pos]
                val prev = activeProvider
                when {
                    prev == null -> showProvider(chosen)
                    prev.id == chosen.id -> return
                    else -> {
                        saveFields(prev)   // настройки прежнего провайдера — в его слот
                        Prefs.setProvider(this@SettingsActivity, chosen.id)
                        showProvider(chosen)
                    }
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        swTts.isChecked = Prefs.ttsOn(this)
        swWake.isChecked = Prefs.wakeOn(this)

        swTts.setOnCheckedChangeListener { _, checked -> Prefs.setTtsOn(this, checked) }

        swWake.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    swWake.isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 21)
                    return@setOnCheckedChangeListener
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    swWake.isChecked = false
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 23)
                    return@setOnCheckedChangeListener
                }
                Prefs.setWakeOn(this, true)
                WakeWordService.start(this)
                Toast.makeText(this, "Скажите «Джарвис» — я услышу даже с закрытым приложением", Toast.LENGTH_LONG).show()
            } else {
                Prefs.setWakeOn(this, false)
                WakeWordService.stop(this)
            }
        }

        findViewById<Button>(R.id.btnCheckKey).setOnClickListener {
            val p = currentProvider()
            val key = etKey.text.toString().trim()
            val model = etModel.text.toString().trim()
            if (key.isEmpty() && !p.keyOptional) {
                Toast.makeText(this, "Вставьте ключ, сэр", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (model.isEmpty()) {
                Toast.makeText(this, "Впишите название модели, сэр", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveFields()
            Toast.makeText(this, "Проверяю ${p.name}…", Toast.LENGTH_SHORT).show()
            Thread {
                val err = Llm.ping(p, Prefs.baseUrl(this, p), key, model)
                runOnUiThread {
                    if (err == null) {
                        Toast.makeText(this, getString(R.string.key_ok, p.name), Toast.LENGTH_LONG).show()
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

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            } else {
                Toast.makeText(this, "Уже разрешено ✔", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Уже разрешено ✔", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            if (!safeStart(i)) safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        findViewById<Button>(R.id.btnExact).setOnClickListener {
            if (ReminderScheduler.exactAllowed(this)) {
                Toast.makeText(this, "Уже разрешено ✔", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 31) {
                safeStart(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else {
                Toast.makeText(this, "На этой версии Android разрешение не требуется", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        swWake.isChecked = Prefs.wakeOn(this)
        refreshBgStatus()
        if (Prefs.wakeOn(this)) WakeWordService.start(this)
    }

    private fun refreshBgStatus() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        val exact = ReminderScheduler.exactAllowed(this)
        val listening = Prefs.wakeOn(this)

        fun mark(ok: Boolean) = if (ok) "✔" else "✖"
        tvBgStatus.text = buildString {
            append("${mark(listening)} ожидание слова «Джарвис»\n")
            append("${mark(mic)} микрофон\n")
            append("${mark(notif)} уведомления\n")
            append("${mark(overlay)} поверх других окон\n")
            append("${mark(battery)} без ограничений батареи\n")
            append("${mark(exact)} точные напоминания")
        }
    }

    private fun safeStart(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        true
    } catch (e: Exception) {
        Toast.makeText(this, "Не смог открыть системные настройки", Toast.LENGTH_SHORT).show()
        false
    }

    // ------------------------------------------------------- провайдер ИИ

    private fun currentProvider(): Providers.Provider = activeProvider
        ?: Providers.ALL[spProvider.selectedItemPosition.coerceIn(0, Providers.ALL.lastIndex)]

    /** Заполняет поля под выбранного провайдера. */
    private fun showProvider(p: Providers.Provider) {
        activeProvider = p
        val customUrl = Prefs.baseUrl(this, p)
        etBaseUrl.setText(if (customUrl == p.baseUrl) "" else customUrl)
        etBaseUrl.hint = p.baseUrl
        etKey.setText(Prefs.apiKey(this, p))
        etKey.hint = p.keyExample.ifEmpty { "ключ не требуется" }
        etModel.setText(Prefs.model(this, p))
        etModel.hint = p.defaultModel.ifEmpty { "например: llama3.2" }
        tvProviderDesc.text = p.description()
        tvKeyDesc.text = if (p.keyOptional) {
            "Ключ не нужен — модель работает на вашем устройстве или в вашей сети."
        } else {
            "Ключ хранится только на телефоне. Получить: ${p.keyUrl}"
        }
        tvModelHint.text = if (p.models.isEmpty()) {
            "Впишите название модели, которое отдаёт ваш сервер."
        } else {
            "Можно вписать любую: ${p.models.joinToString(", ")}"
        }
    }

    /** Сохраняет то, что сейчас в полях, для выбранного провайдера. */
    private fun saveFields(p: Providers.Provider = currentProvider()) {
        Prefs.setApiKey(this, etKey.text.toString(), p)
        Prefs.setModel(this, etModel.text.toString(), p)
        Prefs.setBaseUrl(this, etBaseUrl.text.toString(), p)
    }

    override fun onPause() {
        saveFields()
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
            23 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Prefs.setWakeOn(this, true)
                WakeWordService.start(this)
                swWake.isChecked = true
            } else {
                Toast.makeText(this, "Без микрофона голосовая активация невозможна", Toast.LENGTH_LONG).show()
            }
        }
        refreshBgStatus()
    }
}
