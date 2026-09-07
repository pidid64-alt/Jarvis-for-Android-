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

    private lateinit var llMessages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var orb: OrbView
    private lateinit var btnWake: ImageView

    private lateinit var tts: TtsController
    private val ui = Handler(Looper.getMainLooper())

    private val history = ArrayList<Pair<Boolean, String>>()
    private var lastReply = ""
    private var busy = false

    private var speech: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        llMessages = findViewById(R.id.llMessages)
        scroll = findViewById(R.id.scroll)
        tvStatus = findViewById(R.id.tvStatus)
        etInput = findViewById(R.id.etInput)
        orb = findViewById(R.id.orb)
        btnWake = findViewById(R.id.btnWake)

        tts = TtsController(this) { st ->
            ui.post {
                if (st == TtsController.STATE_SPEAKING) {
                    setStatus(getString(R.string.status_speaking))
                    orb.state = OrbView.State.SPEAKING
                } else if (!listening) {
                    setStatus(getString(R.string.status_idle))
                    orb.state = OrbView.State.IDLE
                }
            }
        }
        tts.enabled = Prefs.ttsOn(this)

        findViewById<ImageView>(R.id.btnSend).setOnClickListener { sendTyped() }
        btnWake.setOnClickListener { toggleWake() }
        findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        orb.setOnClickListener { if (listening) stopListening() else startListening() }

        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO), 10
        )

        if (!Prefs.saidHello(this)) {
            addMessage(getString(R.string.hello_first), false)
            Prefs.setSaidHello(this)
            if (Prefs.ttsOn(this)) tts.speak(
                "С возвращением, сэр. Джарвис к вашим услугам."
            )
        }
        refreshWakeIcon()

        if (intent.getBooleanExtra(WakeWordService.EXTRA_AUTO_LISTEN, false)) {
            ui.postDelayed({ startListening() }, 350)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WakeWordService.EXTRA_AUTO_LISTEN, false)) {
            ui.postDelayed({ startListening() }, 350)
        }
    }

    override fun onResume() {
        super.onResume()
        tts.enabled = Prefs.ttsOn(this)
        refreshWakeIcon()
    }

    override fun onDestroy() {
        speech?.destroy()
        speech = null
        tts.shutdown()
        super.onDestroy()
    }

    // ---------------- отправка / обработка ----------------

    private fun sendTyped() {
        val t = etInput.text.toString().trim()
        if (t.isEmpty()) {
            startListening()
            return
        }
        etInput.setText("")
        handleUser(t)
    }

    private fun handleUser(text: String) {
        if (text.isBlank() || busy) return
        addMessage(text, true)
        history.add(true to text)
        tts.stop()

        if (CommandEngine.normalize(text) == "повтори" || CommandEngine.normalize(text) == "скажи еще раз") {
            deliver(lastReply.ifEmpty { "Пока мне нечего повторить, сэр." }, remember = false)
            return
        }

        val outcome = try {
            CommandEngine.execute(this, text)
        } catch (e: Exception) {
            null
        }
        if (outcome != null) {
            if (outcome.launch != null) {
                try {
                    startActivity(outcome.launch)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть: ${e.message?.take(80)}", Toast.LENGTH_SHORT).show()
                }
            }
            deliver(outcome.reply, remember = true, silent = outcome.silent || outcome.stopTts)
            return
        }

        val key = Prefs.apiKey(this)
        if (key.isEmpty()) {
            deliver(getString(R.string.api_key_missing), remember = true)
            return
        }

        busy = true
        setStatus(getString(R.string.status_thinking))
        orb.state = OrbView.State.THINKING
        GeminiClient.chatAsync(key, Prefs.model(this), history.dropLast(1), text) { answer ->
            ui.post {
                busy = false
                deliver(answer, remember = true)
            }
        }
    }

    private fun deliver(reply: String, remember: Boolean = true, silent: Boolean = false) {
        if (remember) {
            lastReply = reply
            history.add(false to reply)
            if (history.size > 24) {
                history.removeAt(0)
                history.removeAt(0)
            }
        }
        addMessage(reply, false)
        if (!silent && Prefs.ttsOn(this)) tts.speak(reply)
        else {
            setStatus(getString(R.string.status_idle))
            orb.state = OrbView.State.IDLE
        }
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
        tts.stop()
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speech?.startListening(intent)
        listening = true
        setStatus(getString(R.string.status_listening))
        orb.state = OrbView.State.LISTENING
    }

    private fun stopListening() {
        try {
            speech?.stopListening()
        } catch (e: Exception) {
        }
        listening = false
        setStatus(getString(R.string.status_idle))
        orb.state = OrbView.State.IDLE
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            listening = false
            orb.state = OrbView.State.IDLE
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    setStatus(getString(R.string.status_idle))
                else -> {
                    setStatus(getString(R.string.status_idle))
                    if (error != SpeechRecognizer.ERROR_CLIENT) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.mic_error, error.toString()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
            if (!text.isNullOrBlank()) handleUser(text)
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
            } else {
                Toast.makeText(this, "Без микрофона голос не работает — пишите текстом", Toast.LENGTH_LONG).show()
            }
            11 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleWake()
        }
    }
}
