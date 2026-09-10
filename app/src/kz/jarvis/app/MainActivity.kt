package kz.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date

class MainActivity : Activity() {

    companion object {
        const val EXTRA_RESTART_WAKE = "restart_wake"
    }

    private lateinit var llMessages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var orb: OrbView
    private lateinit var btnWake: ImageView

    private lateinit var tts: TtsController
    private val ui = Handler(Looper.getMainLooper())

    private var busy = false

    private var speech: SpeechRecognizer? = null
    private var listening = false

    /** Попыток переподключить микрофон после сбоя (сбрасывается при успехе). */
    private var micRetries = 0

    /** Поколение попыток: новая сессия отменяет отложенный автоповтор. */
    private var listenGen = 0

    /** Последняя реплика пришла голосом — после ответа можно снова слушать. */
    private var lastInputVoice = false

    /** Сколько раз подряд распознавание «не услышало» в непрерывном диалоге. */
    private var autoMisses = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        llMessages = findViewById(R.id.llMessages)
        scroll = findViewById(R.id.scroll)
        tvStatus = findViewById(R.id.tvStatus)
        etInput = findViewById(R.id.etInput)
        orb = findViewById(R.id.orb)
        btnWake = findViewById(R.id.btnWake)

        tts = TtsController(this, { st ->
            ui.post {
                if (st == TtsController.STATE_SPEAKING) {
                    setStatus(getString(R.string.status_speaking))
                    orb.state = OrbView.State.SPEAKING
                } else if (!listening) {
                    setStatus(getString(R.string.status_idle))
                    orb.state = OrbView.State.IDLE
                }
            }
        })
        tts.enabled = Prefs.ttsOn(this)

        findViewById<ImageView>(R.id.btnSend).setOnClickListener { sendTyped() }
        btnWake.setOnClickListener { toggleWake() }
        findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        orb.setOnClickListener {
            if (listening) stopListening()
            else {
                micRetries = 0
                autoMisses = 0
                startListening()
            }
        }

        etInput.inputType = InputType.TYPE_CLASS_TEXT
        etInput.setOnEditorActionListener { _, _, _ -> sendTyped(); true }

        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)

        if (!Prefs.saidHello(this)) {
            addMessage(getString(R.string.hello_first), false)
            Prefs.setSaidHello(this)
            if (Prefs.ttsOn(this)) tts.speak("С возвращением, сэр. Джарвис к вашим услугам.")
        }

        handleWakeIntent(intent)
        refreshWakeIcon()
        ensureWakeService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_RESTART_WAKE, false)) {
            Prefs.setWakeOn(this, true)
            WakeWordService.start(this)
            Toast.makeText(this, "Прослушивание включено — скажите «Джарвис»", Toast.LENGTH_LONG).show()
            intent.removeExtra(EXTRA_RESTART_WAKE)
        }
        if (intent.getBooleanExtra(WakeWordService.EXTRA_AUTO_LISTEN, false)) {
            intent.removeExtra(WakeWordService.EXTRA_AUTO_LISTEN)
            ui.postDelayed({ startListening() }, 350)
        }
    }

    override fun onResume() {
        super.onResume()
        tts.enabled = Prefs.ttsOn(this)
        tts.refresh()   // голос могли поменять в настройках
        refreshWakeIcon()
        WakeWordService.notifyUi(this, true)
        ensureWakeService()
    }

    override fun onPause() {
        // окно закрывается — пусть фоновая служба снова слушает микрофон
        WakeWordService.notifyUi(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        speech?.destroy()
        speech = null
        tts.shutdown()
        super.onDestroy()
    }

    /** Возвращаем фоновое прослушивание к жизни (например, после перезагрузки). */
    private fun ensureWakeService() {
        if (!Prefs.wakeOn(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        WakeWordService.start(this)
    }

    // ---------------- отправка / обработка ----------------

    private fun sendTyped() {
        val t = etInput.text.toString().trim()
        if (t.isEmpty()) {
            startListening()
            return
        }
        etInput.setText("")
        lastInputVoice = false   // печатали — микрофон сам открываться не будет
        autoMisses = 0
        handleUser(t)
    }

    private fun handleUser(text: String) {
        if (text.isBlank() || busy) return
        val shown = WakeWords.removeFromText(text).ifBlank { text }
        tts.stop()

        val norm = CommandEngine.normalize(shown)
        // одно только «Джарвис» — команды нет: просто слушаем дальше
        if (norm.isEmpty() || WakeWords.removeFromText(shown).isEmpty()) {
            if (Prefs.followUp(this)) {
                lastInputVoice = true
                autoMisses = 0
                maybeAutoListen()
            } else {
                setStatus(getString(R.string.status_idle))
                orb.state = OrbView.State.IDLE
            }
            return
        }

        addMessage(shown, true)
        if (norm == "повтори" || norm == "скажи еще раз" || norm == "повтори еще раз") {
            val again = Conversation.lastReply.ifEmpty { "Пока мне нечего повторить, сэр." }
            deliver(again, remember = false)
            return
        }
        if (norm == "очисти историю" || norm == "забудь все") {
            Conversation.clear()
            deliver("История диалога очищена, сэр.")
            return
        }

        Conversation.add(shown, true)

        val outcome = try {
            CommandEngine.execute(this, shown)
        } catch (e: Exception) {
            null
        }
        if (outcome != null) {
            if (outcome.sleepMinutes > 0) {
                Prefs.setSnoozeUntil(this, System.currentTimeMillis() + outcome.sleepMinutes * 60_000L)
            }
            if (outcome.stopWake) {
                Prefs.setWakeOn(this, false)
                WakeWordService.stop(this)
                refreshWakeIcon()
            }
            if (outcome.launch != null) {
                try {
                    startActivity(outcome.launch)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть: ${e.message?.take(80)}", Toast.LENGTH_SHORT).show()
                }
            }
            if (outcome.async != null) {
                // долгая операция (поиск, Клод, презентация) — сначала докладываем,
                // что взялись за дело, и только потом уходим в сеть
                if (outcome.endDialog) lastInputVoice = false  // уходим в ютуб/вацап — микрофон не переоткрываем
                if (outcome.reply.isNotBlank()) deliver(outcome.reply, remember = false)
                busy = true
                setStatus(getString(R.string.status_thinking))
                orb.state = OrbView.State.THINKING
                Thread {
                    var answer = "Не получилось, сэр."
                    try {
                        outcome.async(this) { answer = it }
                    } catch (e: Throwable) {
                        // ловим всё, включая Error: падение фонового потока
                        // убило бы всё приложение прямо во время озвучки
                        answer = "Ошибка: ${e.message?.take(80) ?: "неизвестно"}"
                    }
                    ui.post {
                        busy = false
                        deliver(answer)
                    }
                }.start()
                return
            }
            deliver(outcome.reply, remember = true, silent = outcome.silent || outcome.stopTts)
            return
        }

        if (!Llm.ready(this)) {
            deliver(Llm.missingKeyText(this), remember = true)
            return
        }

        busy = true
        setStatus(getString(R.string.status_thinking))
        orb.state = OrbView.State.THINKING
        Llm.chatAsync(this, Conversation.history().dropLast(1), shown) { answer ->
            ui.post {
                busy = false
                deliver(answer, remember = true)
            }
        }
    }

    private fun deliver(reply: String, remember: Boolean = true, silent: Boolean = false) {
        if (remember) {
            Conversation.add(reply, false)
        }
        addMessage(reply, false)
        if (!silent && Prefs.ttsOn(this)) {
            tts.speak(reply) { maybeAutoListen() }
        } else {
            setStatus(getString(R.string.status_idle))
            orb.state = OrbView.State.IDLE
            maybeAutoListen()
        }
    }

    /**
     * Непрерывный диалог в окне: после произнесённого ответа снова открываем
     * микрофон, чтобы следующую фразу можно было сказать без «Джарвис».
     * Тишину «прощаем» пару раз, потом сфера снова ждёт нажатия.
     */
    private fun maybeAutoListen() {
        if (isFinishing || isDestroyed) return
        if (busy || listening) return
        if (!Prefs.followUp(this) || !lastInputVoice) {
            if (!listening) {
                setStatus(getString(R.string.status_idle))
                orb.state = OrbView.State.IDLE
            }
            return
        }
        val gen = listenGen
        ui.postDelayed({
            if (isFinishing || isDestroyed || busy || listening) return@postDelayed
            if (gen != listenGen) return@postDelayed
            if (!App.uiVisible) return@postDelayed   // окно ушло в фон — микрофон у службы
            startListening()
        }, 700)
    }

    // ---------------- чат UI ----------------

    private fun addMessage(text: String, isUser: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(getColor(if (isUser) R.color.text_main else R.color.cyan_bright))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_jarvis)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            if (!isUser) setTypeface(typeface, Typeface.NORMAL)
        }
        val time = TextView(this).apply {
            this.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(Date())
            setTextColor(getColor(R.color.text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.BOTTOM
        }
        row.addView(if (isUser) time else tv)
        row.addView(if (isUser) tv else time)
        row.setPadding(0, dp(3), 0, dp(3))
        llMessages.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) {
        tvStatus.text = s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- голосовой ввод ----------------

    private fun startListening() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Распознавание речи недоступно на этом устройстве", Toast.LENGTH_LONG).show()
            return
        }
        AudioFx.sync(this)
        tts.stop()
        listenGen++
        try { speech?.destroy() } catch (e: Exception) { }
        speech = try {
            SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
        } catch (e: Exception) {
            // распознаватель может не создаться (сервис Google перезапускается) — повторим
            if (!retryMic("распознаватель недоступен")) {
                setStatus(getString(R.string.status_idle))
            }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Не обрывать фразу после первых двух слов: даём человеку
            // договорить предложение и считаем паузой только длительное молчание.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1900L)
        }
        speech?.startListening(intent)
        listening = true
        setStatus(getString(R.string.status_listening))
        orb.state = OrbView.State.LISTENING
    }

    private fun stopListening() {
        listenGen++   // отменяем отложенные автоповторы
        try {
            speech?.stopListening()
        } catch (e: Exception) { }
        listening = false
        setStatus(getString(R.string.status_idle))
        orb.state = OrbView.State.IDLE
    }

    /**
     * Микрофон «сбросился» (занят другим приложением, сбой аудио, клиентская
     * ошибка распознавателя) — пробуем переподключиться пару раз молча.
     * Возвращает true, если повтор запланирован.
     */
    private fun retryMic(cause: String): Boolean {
        if (micRetries >= 2 || busy || isFinishing || isDestroyed) return false
        micRetries++
        val gen = listenGen
        setStatus(getString(R.string.status_reconnecting, cause))
        ui.postDelayed({
            // пользователь успел что-то нажать или запустить новую сессию — не мешаем
            if (listening || gen != listenGen || busy || isFinishing || isDestroyed) return@postDelayed
            startListening()
        }, 600)
        return true
    }

    /** Человекочитаемое имя ошибки распознавания — для тоста. */
    private fun micErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "микрофон занят другим приложением"
        SpeechRecognizer.ERROR_AUDIO -> "сбой захвата звука"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "нет сети"
        SpeechRecognizer.ERROR_CLIENT -> "сбой клиента распознавания"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "ошибка на сервере распознавания"
        else -> error.toString()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            micRetries = 0   // микрофон работает — счётчик сбоев обнуляем
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            listening = false
            orb.state = OrbView.State.IDLE
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // непрерывный диалог: собеседник задумался — прощаем тишину
                    // пару раз и снова открываем микрофон
                    if (Prefs.followUp(this@MainActivity) && lastInputVoice && autoMisses < 2) {
                        autoMisses++
                        setStatus(getString(R.string.status_listening))
                        ui.postDelayed({
                            if (listening || busy || isFinishing || isDestroyed) return@postDelayed
                            if (!App.uiVisible) return@postDelayed   // окно в фоне — микрофон у службы
                            if (!Prefs.followUp(this@MainActivity) || !lastInputVoice || autoMisses > 2) {
                                setStatus(getString(R.string.status_idle))
                                return@postDelayed
                            }
                            startListening()
                        }, 500)
                    } else {
                        lastInputVoice = false
                        setStatus(getString(R.string.status_idle))
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setStatus(getString(R.string.status_idle))
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
                }
                // технические сбои: микрофон мог «сброситься» — тихо переподключаемся
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    if (!retryMic(micErrorName(error))) {
                        setStatus(getString(R.string.status_idle))
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.mic_error, micErrorName(error)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                else -> {
                    setStatus(getString(R.string.status_idle))
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.mic_error, micErrorName(error)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            orb.state = OrbView.State.IDLE
            setStatus(getString(R.string.status_idle))
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                lastInputVoice = true
                autoMisses = 0
                handleUser(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) setStatus("… $text")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------- wake-слово ----------------

    private fun toggleWake() {
        if (Prefs.wakeOn(this)) {
            Prefs.setWakeOn(this, false)
            WakeWordService.stop(this)
            Toast.makeText(this, "Голосовая активация выключена", Toast.LENGTH_SHORT).show()
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
                return
            }
            Prefs.setWakeOn(this, true)
            WakeWordService.start(this)
            Toast.makeText(this, "Скажите «Джарвис» — и я проснусь, сэр!", Toast.LENGTH_SHORT).show()
        }
        refreshWakeIcon()
    }

    private fun refreshWakeIcon() {
        btnWake.alpha = if (Prefs.wakeOn(this)) 1f else 0.42f
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            10 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                setStatus(getString(R.string.status_idle))
                ensureWakeService()
            } else {
                Toast.makeText(this, "Без микрофона голос не работает — пишите текстом", Toast.LENGTH_LONG).show()
            }
            11 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleWake()
            43 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Доступ к телефону выдан ✔ — теперь звоню всегда с первой SIM",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "SIM не выбрана — при звонке система спросит сама", Toast.LENGTH_LONG).show()
            }
        }
    }
}
