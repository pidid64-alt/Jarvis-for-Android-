package kz.jarvis.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фоновая служба голосовой активации.
 *
 * Что делает:
 *  1. непрерывно слушает микрофон и ждёт слово «Джарвис»;
 *  2. услышав его, слушает команду и ВЫПОЛНЯЕТ ЕЁ САМА — приложение
 *     открывать не нужно: ответ озвучивается, поверх экрана появляется сфера;
 *  3. если команда не офлайн-команда — спрашивает Gemini и озвучивает ответ;
 *  4. окно приложения открывается только когда без экрана никак
 *     (звонок, запуск стороннего приложения) и только если Android это
 *     разрешает (нужно разрешение «Показ поверх других окон»).
 *
 * Важное ограничение Android 14+: службу с типом microphone нельзя запустить
 * из фона (в том числе по BOOT_COMPLETED) — только пока приложение видно.
 * Поэтому после перезагрузки приходит уведомление «нажмите, чтобы включить».
 */
class WakeWordService : Service() {

    companion object {
        const val CH_ID = "jarvis_wake"
        const val EXTRA_AUTO_LISTEN = "auto_listen"

        const val ACTION_SPEAK = "kz.jarvis.app.action.SPEAK"
        const val ACTION_RESUME = "kz.jarvis.app.action.RESUME"
        const val ACTION_OFF = "kz.jarvis.app.action.OFF"
        const val EXTRA_TEXT = "kz.jarvis.app.extra.TEXT"

        private const val NOTIF_ID = 1
        private const val WATCHDOG_MS = 4_000L

        /** MainActivity на экране — служба в это время молчит, чтобы не мешать. */
        @Volatile
        var uiActive: Boolean = false

        fun start(c: Context) {
            val i = Intent(c, WakeWordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Exception) {
                // Android 12+ запрещает запуск из фона — позовём пользователя уведомлением
                Notify.needRestart(c)
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, WakeWordService::class.java))
            } catch (e: Exception) { }
        }

        /** Озвучить фразу службой (например, сработавшее напоминание). */
        fun speak(c: Context, text: String) {
            try {
                val i = Intent(c, WakeWordService::class.java)
                    .setAction(ACTION_SPEAK)
                    .putExtra(EXTRA_TEXT, text)
                c.startService(i)
            } catch (e: Exception) { }
        }

        /** Сообщить службе, что окно открылось/закрылось. */
        fun notifyUi(c: Context, visible: Boolean) {
            uiActive = visible
            if (!visible) {
                try {
                    c.startService(Intent(c, WakeWordService::class.java).setAction(ACTION_RESUME))
                } catch (e: Exception) { }
            }
        }
    }

    private enum class Mode { WAKE, COMMAND }

    private val ui = Handler(Looper.getMainLooper())
    private var speech: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null
    private var tts: TtsController? = null
    private var overlay: OverlayController? = null

    private var running = false
    private var mode = Mode.WAKE
    @Volatile private var speaking = false
    private var lastTrigger = 0L
    private var lastCallback = System.currentTimeMillis()
    private var snoozeUntil = 0L
    private var errorStreak = 0

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ жизненный цикл

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OFF -> {
                Prefs.setWakeOn(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (running && text.isNotBlank()) say(text)
                else if (text.isNotBlank()) Speaker.once(this, text)
                if (!running) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            ACTION_RESUME -> {
                if (running && !snoozed() && speech == null) restartListening(200)
            }
        }

        if (!running) {
            if (!goForeground()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            running = true
            mode = Mode.WAKE
            snoozeUntil = Prefs.snoozeUntil(this)
            tone = try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            } catch (e: Exception) { null }
            tts = TtsController(this) { }
            tts?.enabled = Prefs.ttsOn(this)
            overlay = OverlayController(this)
            ui.postDelayed({ restartListening() }, 250)
            ui.postDelayed(watchdog, WATCHDOG_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacks(watchdog)
        try { speech?.destroy() } catch (e: Exception) { }
        speech = null
        try { tone?.release() } catch (e: Exception) { }
        tone = null
        tts?.shutdown()
        tts = null
        overlay?.hide()
        super.onDestroy()
    }

    /**
     * Поднимаем foreground-уведомление. На Android 14+ из фона это запрещено
     * для типа microphone — тогда просим пользователя нажать на уведомление.
     */
    private fun goForeground(): Boolean = try {
        startForeground(NOTIF_ID, notification("Слушаю слово «Джарвис»…"))
        true
    } catch (e: Exception) {
        Notify.needRestart(this)
        false
    }

    // ------------------------------------------------------------ уведомления

    private fun notification(state: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val off = PendingIntent.getService(
            this, 3,
            Intent(this, WakeWordService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID) else Notification.Builder(this)
        return b
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис на связи")
            .setContentText(state)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Выключить", off)
            .build()
    }

    private fun notifyState(state: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(state))
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------ слушание

    private fun snoozed(): Boolean {
        if (snoozeUntil > System.currentTimeMillis()) return true
        val fromPrefs = Prefs.snoozeUntil(this)   // «спи N минут» могли сказать в окне приложения
        if (fromPrefs > System.currentTimeMillis()) {
            snoozeUntil = fromPrefs
            return true
        }
        snoozeUntil = 0
        return false
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    private fun restartListening(delay: Long = 300) {
        if (!running) return
        ui.postDelayed({
            if (!running) return@postDelayed
            if (speaking) { restartListening(500); return@postDelayed }
            if (snoozed()) { restartListening(5_000); return@postDelayed }
            if (uiActive && mode == Mode.WAKE) { restartListening(2_000); return@postDelayed }
            try { speech?.destroy() } catch (e: Exception) { }
            speech = null
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                notifyState("Распознавание речи недоступно на этом устройстве")
                restartListening(60_000)
                return@postDelayed
            }
            try {
                speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(listener)
                }
                speech?.startListening(recognizerIntent())
                lastCallback = System.currentTimeMillis()
            } catch (e: Exception) {
                restartListening(2_000)
            }
        }, delay)
    }

    /** Страховка от «залипшего» распознавателя. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            ui.postDelayed(this, WATCHDOG_MS)
            if (speaking) return
            val idle = System.currentTimeMillis() - lastCallback
            when {
                snoozed() -> { /* спим */ }
                mode == Mode.WAKE && idle > 15_000 -> restartListening(0)
                mode == Mode.COMMAND && idle > 18_000 -> {
                    say("Не расслышал, сэр.") { backToWake() }
                }
            }
        }
    }

    // ------------------------------------------------------------ пробуждение

    private fun onWake(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3_500) return true // недавно срабатывали — молчим
        lastTrigger = now
        beep()

        // приложение открыто — команду послушает оно само
        if (App.uiVisible) {
            launchActivity(listen = true)
            restartListening(6_000)
            return true
        }

        mode = Mode.COMMAND
        notifyState("Слушаю команду…")
        overlay?.show("Слушаю, сэр…")
        say("Слушаю, сэр.") {
            ui.postDelayed({
                mode = Mode.COMMAND
                restartListening(0)
            }, 150)
        }
        return true
    }

    private fun beep() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150) } catch (e: Exception) { }
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(Vibrator::class.java)
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 70, 60), -1))
        } catch (e: Exception) { }
    }

    private fun backToWake() {
        mode = Mode.WAKE
        overlay?.autoHide(2_500)
        notifyState("Слушаю слово «Джарвис»…")
        restartListening(350)
    }

    // ------------------------------------------------------------ выполнение команды

    private fun handleCommand(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            backToWake()
            return
        }
        overlay?.update("«$text»")
        notifyState("Думаю…")

        val outcome = try {
            CommandEngine.execute(this, text)
        } catch (e: Exception) {
            null
        }

        if (outcome != null) {
            Conversation.add(text, true)
            Conversation.add(outcome.reply, false)

            if (outcome.sleepMinutes > 0) snooze(outcome.sleepMinutes)
            if (outcome.launch != null) launch(outcome.launch)
            if (outcome.openApp) launchActivity(false)

            if (outcome.stopWake) {
                Prefs.setWakeOn(this, false)
                say(outcome.reply) { stopSelf() }
                return
            }
            if (outcome.async != null) {
                Thread {
                    var answer = "Не получилось, сэр."
                    try {
                        outcome.async(this) { answer = it }
                    } catch (e: Exception) {
                        answer = "Ошибка: ${e.message?.take(80) ?: "неизвестно"}"
                    }
                    ui.post { say(answer) { backToWake() } }
                }.start()
                return
            }
            say(if (outcome.silent) "" else outcome.reply) { backToWake() }
            return
        }

        // не офлайн-команда — спрашиваем ИИ
        if (!Llm.ready(this)) {
            say("Такой команды офлайн у меня нет, сэр. " + Llm.missingKeyText(this)) {
                launchActivity(false)
                backToWake()
            }
            return
        }
        Conversation.add(text, true)
        Llm.chatAsync(this, Conversation.history(), text) { answer ->
            ui.post {
                Conversation.add(answer, false)
                say(answer) { backToWake() }
            }
        }
    }

    private fun snooze(minutes: Int) {
        snoozeUntil = System.currentTimeMillis() + minutes * 60_000L
        Prefs.setSnoozeUntil(this, snoozeUntil)
        try { speech?.destroy() } catch (e: Exception) { }
        speech = null
        overlay?.hide()
        val until = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snoozeUntil))
        notifyState("Сплю до $until")
        ui.postDelayed({
            snoozeUntil = 0
            Prefs.setSnoozeUntil(this, 0)
            if (running) {
                notifyState("Слушаю слово «Джарвис»…")
                restartListening(0)
            }
        }, minutes * 60_000L)
    }

    // ------------------------------------------------------------ речь / окна

    private fun say(text: String, attempt: Int = 0, onDone: () -> Unit = {}) {
        if (text.isBlank() || !Prefs.ttsOn(this)) {
            onDone()
            return
        }
        val t = tts
        tts?.enabled = Prefs.ttsOn(this)
        if (t == null || !t.ready) {
            if (attempt < 6) ui.postDelayed({ say(text, attempt + 1, onDone) }, 400) else onDone()
            return
        }
        speaking = true
        overlay?.update(text)
        t.speak(text) {
            speaking = false
            ui.postDelayed({ onDone() }, 200)
        }
    }

    private fun launch(i: Intent) {
        try {
            if (i.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
            lastTrigger = System.currentTimeMillis()
        } catch (e: Exception) {
            // Android 10+ не пускает Activity из фона без «поверх других окон»
            val msg = "Не смог открыть окно из фона — включите «Показ поверх других окон» в настройках Джарвиса."
            overlay?.update(msg)
            notifyState(msg)
        }
    }

    private fun launchActivity(listen: Boolean) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (listen) i.putExtra(EXTRA_AUTO_LISTEN, true)
        try {
            startActivity(i)
        } catch (e: Exception) {
            overlay?.update("Откройте Джарвис вручную, сэр — Android не дал поднять окно из фона.")
        }
    }

    // ------------------------------------------------------------ распознаватель

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastCallback = System.currentTimeMillis()
        }

        override fun onBeginningOfSpeech() {
            lastCallback = System.currentTimeMillis()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            lastCallback = System.currentTimeMillis()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            lastCallback = System.currentTimeMillis()
            if (mode != Mode.WAKE) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!t.isNullOrBlank()) overlay?.update("«$t»")
                return
            }
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            for (t in texts) {
                if (t != null && WakeWords.contains(t)) {
                    try { speech?.stopListening() } catch (e: Exception) { }
                    onWake()
                    return
                }
            }
        }

        override fun onResults(results: Bundle?) {
            lastCallback = System.currentTimeMillis()
            errorStreak = 0
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (mode == Mode.WAKE) {
                var hit = false
                if (texts != null) for (t in texts) if (t != null && WakeWords.contains(t)) hit = true
                if (hit) onWake() else restartListening(150)
            } else {
                val text = texts?.firstOrNull { !it.isNullOrBlank() }
                if (text.isNullOrBlank()) {
                    say("Не расслышал, сэр.") { backToWake() }
                } else {
                    try { speech?.stopListening() } catch (e: Exception) { }
                    handleCommand(text)
                }
            }
        }

        override fun onError(error: Int) {
            lastCallback = System.currentTimeMillis()
            if (mode == Mode.COMMAND &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                say("Не расслышал, сэр.") { backToWake() }
                return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                notifyState("Нет доступа к микрофону — откройте Джарвис и дайте разрешение")
                Notify.needRestart(this@WakeWordService)
                restartListening(30_000)
                return
            }
            errorStreak++
            val delay = when {
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                errorStreak > 6 -> { errorStreak = 0; 10_000L }
                else -> 500L
            }
            if (mode == Mode.COMMAND && errorStreak > 2) backToWake() else restartListening(delay)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
