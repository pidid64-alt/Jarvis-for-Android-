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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
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
    private lateinit var swNoise: Switch
    private lateinit var tvNoiseDesc: TextView
    private lateinit var tvBgStatus: TextView

    // --- голос ---
    private lateinit var spVoice: Spinner
    private lateinit var tvVoiceDesc: TextView
    private lateinit var spVoiceName: Spinner
    private lateinit var tvPitch: TextView
    private lateinit var sbPitch: SeekBar
    private lateinit var tvRate: TextView
    private lateinit var sbRate: SeekBar
    private lateinit var swVoiceFx: Switch
    private lateinit var swClaudeApp: Switch
    private lateinit var tvClaudeDesc: TextView

    // --- непрерывный диалог и автоответчик ---
    private lateinit var swDialog: Switch
    private lateinit var swAuto: Switch
    private lateinit var tvAutoDesc: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var tvNotifDesc: TextView
    private lateinit var btnAutoDiag: Button
    private lateinit var swAutoScreen: Switch
    private lateinit var swAutoLoc: Switch
    private lateinit var swAutoVoice: Switch
    private lateinit var etAutoRules: EditText

    /** Своя озвучка — чтобы дать послушать голос прямо в настройках. */
    private var tts: TtsController? = null

    /** Имена системных голосов в том же порядке, что и в списке выбора. */
    private var voiceNames: List<String> = listOf("")

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
        swNoise = findViewById(R.id.swNoise)
        tvNoiseDesc = findViewById(R.id.tvNoiseDesc)
        tvBgStatus = findViewById(R.id.tvBgStatus)
        spVoice = findViewById(R.id.spVoice)
        tvVoiceDesc = findViewById(R.id.tvVoiceDesc)
        spVoiceName = findViewById(R.id.spVoiceName)
        tvPitch = findViewById(R.id.tvPitch)
        sbPitch = findViewById(R.id.sbPitch)
        tvRate = findViewById(R.id.tvRate)
        sbRate = findViewById(R.id.sbRate)
        swVoiceFx = findViewById(R.id.swVoiceFx)
        swClaudeApp = findViewById(R.id.swClaudeApp)
        tvClaudeDesc = findViewById(R.id.tvClaudeDesc)
        swDialog = findViewById(R.id.swDialog)
        swAuto = findViewById(R.id.swAuto)
        tvAutoDesc = findViewById(R.id.tvAutoDesc)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        tvNotifDesc = findViewById(R.id.tvNotifDesc)
        btnAutoDiag = findViewById(R.id.btnAutoDiag)
        swAutoScreen = findViewById(R.id.swAutoScreen)
        swAutoLoc = findViewById(R.id.swAutoLoc)
        swAutoVoice = findViewById(R.id.swAutoVoice)
        etAutoRules = findViewById(R.id.etAutoRules)

        setupVoice()
        setupResearch()
        setupAssistantModes()

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

        // --- шумоподавление микрофона ---
        AudioFx.sync(this)
        swNoise.isChecked = Prefs.noiseSuppress(this)
        refreshNoiseStatus()
        swNoise.setOnCheckedChangeListener { _, checked ->
            Prefs.setNoiseSuppress(this, checked)
            AudioFx.sync(this)
            refreshNoiseStatus()
        }

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
        swNoise.isChecked = Prefs.noiseSuppress(this)
        refreshNoiseStatus()
        refreshAutoDesc()
        refreshBgStatus()
        if (Prefs.wakeOn(this)) WakeWordService.start(this)
    }

    /** Строка-статус под переключателем: что реально работает на этом устройстве. */
    private fun refreshNoiseStatus() {
        tvNoiseDesc.text = when {
            !Prefs.noiseSuppress(this) ->
                getString(R.string.noise_desc) + "\n" + getString(R.string.noise_off)
            AudioFx.activeNames().isNotEmpty() ->
                getString(R.string.noise_desc) + "\n" +
                    getString(R.string.noise_working, AudioFx.activeNames().joinToString(", "))
            else ->
                getString(R.string.noise_desc) + "\n" + getString(R.string.noise_not_supported)
        }
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

    // ---------------------------------- непрерывный диалог и автоответчик

    private fun setupAssistantModes() {
        swDialog.isChecked = Prefs.followUp(this)
        swDialog.setOnCheckedChangeListener { _, checked ->
            Prefs.setFollowUp(this, checked)
            if (checked) {
                Toast.makeText(this, "Скажите «Джарвис» — и говорите дальше без повтора слова", Toast.LENGTH_LONG).show()
            }
        }

        swAuto.isChecked = Prefs.autoReply(this)
        swAutoScreen.isChecked = Prefs.autoReplyScreenOff(this)
        swAutoLoc.isChecked = Prefs.autoReplyLoc(this)
        swAutoVoice.isChecked = Prefs.autoReplyVoice(this)
        etAutoRules.setText(Prefs.autoReplyRules(this))
        etAutoRules.hint = getString(R.string.autoreply_rules_hint)

        swAuto.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoReply(this, checked)
            refreshAutoDesc()
            if (!checked) return@setOnCheckedChangeListener
            // Джарвису самому нужно право показывать уведомления — иначе не видно,
            // что он ответил или почему не ответил (Android 13+)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 25)
            }
            if (!AutoReplyService.isGranted(this)) {
                Toast.makeText(
                    this,
                    "Включил. Теперь в системном списке включите «Джарвис — автоответчик» (кнопка «Доступ к уведомлениям» ниже)",
                    Toast.LENGTH_LONG
                ).show()
            } else if (!AutoReplyService.contactsGranted(this)) {
                Toast.makeText(
                    this,
                    "Включил. Дайте ещё доступ к контактам (кнопка «Контакты» ниже) — иначе я не отличу своих от чужих",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        swAutoScreen.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoReplyScreenOff(this, checked)
            refreshAutoDesc()
        }
        swAutoVoice.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoReplyVoice(this, checked)
        }
        swAutoLoc.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoReplyLoc(this, checked)
            if (checked) {
                requestLocationIfNeeded()
            }
        }

        btnNotifAccess.setOnClickListener {
            safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Полная проверка: показывает ровно то, чего не хватает автоответчику.
        btnAutoDiag.setOnClickListener {
            val health = AutoReplyService.health(this)
            tvAutoDesc.text = getString(R.string.autoreply_desc) + "\n\n" + health
            Toast.makeText(this, health.replace("\n", " ").take(220), Toast.LENGTH_LONG).show()
        }

        refreshAutoDesc()
    }

    private fun requestLocationIfNeeded() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED && coarse == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Доступ к геолокации есть ✔", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 24
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 24)
        }
    }

    /** Что реально мешает/помогает автоответчику — карточка с честным статусом. */
    private fun refreshAutoDesc() {
        val base = getString(R.string.autoreply_desc)
        val extra = StringBuilder()
        if (!Prefs.autoReply(this)) {
            extra.append("\n\nАвтоответчик выключен. Скажите «включи автоответ» или включите переключатель выше.")
        } else {
            fun line(ok: Boolean, okText: String, badText: String) {
                extra.append("\n").append(if (ok) "✔ " else "✖ ").append(if (ok) okText else badText)
            }
            line(
                Llm.ready(this),
                "провайдер ИИ настроен",
                "нет ключа ИИ — ответы некому сочинять (Настройки → Провайдер ИИ)"
            )
            line(
                AutoReplyService.isGranted(this),
                "доступ к уведомлениям выдан",
                "нет «доступа к уведомлениям» — включите «Джарвис — автоответчик» в системном списке"
            )
            line(
                AutoReplyService.contactsGranted(this),
                "доступ к контактам есть",
                "нет доступа к контактам — не отличаю своих от чужих (кнопка «Контакты» ниже)"
            )
            line(
                Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
                "свои уведомления вижу",
                "мои уведомления запрещены — не покажу, что ответил или почему не ответил"
            )
            extra.append("\nОтвечаю ")
                .append(
                    if (Prefs.autoReplyScreenOff(this)) {
                        "только при выключенном экране (проверка: погасите экран)."
                    } else {
                        "и при включённом экране."
                    }
                )
            if (Prefs.autoReplyLoc(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                extra.append("\n⚠ Геолокация включена, но разрешение не выдано — «где ты» будет без адреса.")
            }
            if (!Prefs.autoReplyRules(this).isBlank()) {
                extra.append("\nПравил: ").append(Prefs.autoReplyRules(this).lines().size)
            }
            extra.append("\n\nНе отвечает? Нажмите «Проверить, почему не отвечает» — скажу точно.")
        }
        tvAutoDesc.text = base.toString() + extra
        tvNotifDesc.text = if (AutoReplyService.isGranted(this)) {
            getString(R.string.autoreply_notif_access_ok)
        } else {
            getString(R.string.autoreply_notif_access_bad)
        }
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
        Prefs.setAutoReplyRules(this, etAutoRules.text.toString())
        super.onPause()
    }

    // ------------------------------------------------------------------ голос

    private fun setupVoice() {
        spVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, VoiceProfile.names()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spVoice.setSelection(VoiceProfile.indexOf(Prefs.voiceId(this)))
        tvVoiceDesc.text = Prefs.voiceProfile(this).hint

        spVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val chosen = VoiceProfile.ALL[pos]
                if (chosen.id == Prefs.voiceId(this@SettingsActivity)) {
                    tvVoiceDesc.text = chosen.hint
                    return
                }
                Prefs.setVoiceId(this@SettingsActivity, chosen.id)
                Prefs.setVoiceName(this@SettingsActivity, "")
                Voice.changed()
                tvVoiceDesc.text = chosen.hint
                showTone()
                fillVoiceNames()
                tts?.refresh()
                speakSample()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        showTone()
        sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setVoicePitch(this@SettingsActivity, VoiceProfile.PITCH_MIN + value / 100f)
                Voice.changed()
                labelTone()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                tts?.refresh()
                speakSample()
            }
        })
        sbRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setVoiceRate(this@SettingsActivity, VoiceProfile.RATE_MIN + value / 100f)
                Voice.changed()
                labelTone()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                tts?.refresh()
                speakSample()
            }
        })

        swVoiceFx.isChecked = Prefs.voiceFx(this)
        swVoiceFx.setOnCheckedChangeListener { _, checked ->
            Prefs.setVoiceFx(this, checked)
            Voice.syncFx(this)
            Voice.changed()
            if (checked && !Voice.fxWorks()) {
                Toast.makeText(this, "Прошивка не дала эффект эха, сэр", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnVoiceTest).setOnClickListener {
            tts?.refresh()
            speakSample()
        }

        // движок TTS поднимается не мгновенно — список голосов заполняем по готовности
        tts = TtsController(this, { }, { runOnUiThread { fillVoiceNames() } })
    }

    private fun showTone() {
        sbPitch.progress = ((Prefs.voicePitch(this) - VoiceProfile.PITCH_MIN) * 100).toInt()
            .coerceIn(0, sbPitch.max)
        sbRate.progress = ((Prefs.voiceRate(this) - VoiceProfile.RATE_MIN) * 100).toInt()
            .coerceIn(0, sbRate.max)
        labelTone()
    }

    private fun labelTone() {
        tvPitch.text = getString(R.string.voice_pitch, fmt(Prefs.voicePitch(this)))
        tvRate.text = getString(R.string.voice_rate, fmt(Prefs.voiceRate(this)))
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun speakSample() {
        if (!Prefs.ttsOn(this)) {
            Toast.makeText(this, "Озвучка выключена — включите «Голосовые ответы»", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(VoiceProfile.sampleText())
    }

    /** Заполняет список системных голосов телефона. */
    private fun fillVoiceNames() {
        val pairs = tts?.systemVoices() ?: listOf("" to "Авто — Джарвис выберет сам")
        voiceNames = pairs.map { it.first }
        spVoiceName.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, pairs.map { it.second }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val saved = Prefs.voiceName(this)
        spVoiceName.setSelection(voiceNames.indexOf(saved).coerceAtLeast(0))
        spVoiceName.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val name = voiceNames.getOrElse(pos) { "" }
                if (name == Prefs.voiceName(this@SettingsActivity)) return
                Prefs.setVoiceName(this@SettingsActivity, name)
                Voice.changed()
                tts?.refresh()
                speakSample()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------- поиск и презентации

    private fun setupResearch() {
        swClaudeApp.isChecked = Prefs.claudeApp(this)
        refreshClaudeDesc()
        swClaudeApp.setOnCheckedChangeListener { _, checked ->
            Prefs.setClaudeApp(this, checked)
            refreshClaudeDesc()
        }
        findViewById<Button>(R.id.btnOpenDeck).setOnClickListener {
            Toast.makeText(this, DeckFile.openLast(this), Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshClaudeDesc() {
        val app = if (ClaudeBridge.installed(this)) "Приложение Claude найдено на телефоне."
        else "Приложения Claude нет — открою claude.ai в браузере."
        val api = if (Llm.claude(this) != null) "Ключ Anthropic указан: могу работать и без приложения."
        else "Ключа Anthropic нет — материал уйдёт в приложение или на сайт."
        tvClaudeDesc.text = getString(R.string.claude_app_desc) + "\n" + app + " " + api
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
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
            24 -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Доступ к геолокации выдан ✔", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Геолокация не выдана — «где ты» будет без адреса", Toast.LENGTH_LONG).show()
            }
            25 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Уведомления Джарвиса теперь видны ✔", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Без разрешения на уведомления вы не увидите, что я ответил или почему не ответил",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        refreshBgStatus()
        refreshAutoDesc()
    }
}
