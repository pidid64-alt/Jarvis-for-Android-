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
        const val ACTION_REC_STOP = "kz.jarvis.app.action.REC_STOP"
        const val ACTION_RESUME = "kz.jarvis.app.action.RESUME"
        const val ACTION_UI_ACTIVE = "kz.jarvis.app.action.UI_ACTIVE"
        const val ACTION_OFF = "kz.jarvis.app.action.OFF"
        const val EXTRA_TEXT = "kz.jarvis.app.extra.TEXT"

        private const val NOTIF_ID = 1
        private const val WATCHDOG_MS = 4_000L

        /**
         * Пауза в разговоре, после которой непрерывный диалог заканчивается
         * и Джарвис снова ждёт слово «Джарвис».
         */
        private const val FOLLOW_IDLE_MS = 14_000L

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

        /**
         * Сообщить службе, что окно открылось/закрылось. Когда окно открыто,
         * служба сразу освобождает микрофон — им владеет окно, и два
         * распознавателя не конфликтуют (иначе микрофон «сбрасывается»).
         * Выключенную вручную службу не воскрешаем.
         */
        fun notifyUi(c: Context, visible: Boolean) {
            uiActive = visible
            if (!Prefs.wakeOn(c)) return
            try {
                c.startService(
                    Intent(c, WakeWordService::class.java)
                        .setAction(if (visible) ACTION_UI_ACTIVE else ACTION_RESUME)
                )
            } catch (e: Exception) { }
        }
    }

    /**
     * WAKE — ждём слово «Джарвис» (или кодовое слово диктофона),
     * COMMAND — слушаем команду,
     * RECORD — пишем звук, микрофон у диктофона (см. [Recorder]).
     */
    private enum class Mode { WAKE, COMMAND, RECORD }

    /** Фразы, после которых разговор завершается (ждём «Джарвис» снова). */
    private val GOODBYE = Regex(
        "^(пока|до свидания|прощай|отбой|до встречи|вс[её] пока|на сегодня вс[её]|" +
            "заверши разговор|заканчиваем|хватит на сегодня|спасибо пока|пока джарвис)$"
    )

    private val ui = Handler(Looper.getMainLooper())
    private var speech: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null
    private var tts: TtsController? = null
    private var overlay: OverlayController? = null

    private var running = false
    private var mode = Mode.WAKE
    @Volatile private var speaking = false

    /** Идёт долгая работа (поиск в интернете, Клод, сборка презентации). */
    @Volatile private var working = false
    private var lastTrigger = 0L
    private var lastCallback = System.currentTimeMillis()
    private var snoozeUntil = 0L
    private var errorStreak = 0

    /**
     * Непрерывный диалог: после ответа Джарвис продолжает слушать следующую
     * реплику без нового «Джарвис» и закрывает сессию после тишины.
     */
    @Volatile private var followUp = false

    /** В непрерывном диалоге сейчас пауза — ждём следующую реплику. */
    @Volatile private var waitingFollowUp = false

    /** До какого момента ждём следующую реплику (иначе сессия закрывается). */
    private var followDeadline = 0L

    /** После ответа сессию нужно закрыть (запуск окна/приложения, «пока»…). */
    private var closeAfterReply = false

    /** Поколение перезапусков: новый restartListening отменяет старые отложенные. */
    private var listenGen = 0

    // ------------------------------------------------------------ диктофон

    /** Идёт запись звука (микрофон у [Recorder], распознаватель выключен). */
    @Volatile private var recording = false

    /** Сейчас запись поставлена на паузу и мы слушаем «стоп запись». */
    @Volatile private var recGapListening = false

    /** Когда начался текущий кусок записи — от него считаем паузу на команду. */
    private var recSegmentStart = 0L

    /** Когда в последний раз обновляли уведомление о записи. */
    private var lastRecNotify = 0L

    /** Сколько раз подряд распознаватель умер молча (без колбэков). */
    private var silentDrops = 0

    /** Показали ли уведомление «микрофон сбрасывался» (чтобы вернуть «в строю»). */
    private var noticedMicDrop = false

    /** Показали ли «микрофон выключен в системе». */
    private var noticedMicMute = false

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
            ACTION_REC_STOP -> {
                // кнопка «Остановить» в уведомлении диктофона
                if (recording || Recorder.recording) {
                    if (!running) {
                        finishDictaphone("остановлено из уведомления")
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    finishDictaphone("остановлено из уведомления")
                } else if (!running) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            ACTION_RESUME -> {
                if (running && !snoozed() && speech == null && mode != Mode.RECORD) restartListening(200)
            }
            ACTION_UI_ACTIVE -> {
                // окно приложения открылось — отдаём ему микрофон немедленно,
                // иначе два распознавателя дерутся и микрофон «сбрасывается».
                // Заодно закрываем непрерывный диалог: дальше говорит окно.
                if (running) {
                    followUp = false
                    waitingFollowUp = false
                    try { speech?.destroy() } catch (e: Exception) { }
                    speech = null
                    if (recording) {
                        // диктофон держит микрофон — окно его не отберёт
                        recGapListening = false
                        mode = Mode.RECORD
                        restartListening(3_000)
                    } else {
                        mode = Mode.WAKE
                        restartListening(3_000)
                    }
                }
            }
        }

        if (!running) {
            if (!goForeground()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            running = true
            mode = Mode.WAKE
            followUp = false
            waitingFollowUp = false
            closeAfterReply = false
            snoozeUntil = Prefs.snoozeUntil(this)
            AudioFx.sync(this)   // шумоподавление микрофона
            Voice.syncFx(this)   // эффект «брони» для голоса, если включён
            tone = try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            } catch (e: Exception) { null }
            tts = TtsController(this, { })
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
        // служба умирает — запись надо сохранить, а не выбросить
        if (recording || Recorder.recording) {
            try {
                val saved = Recorder.stop(this)
                Notify.recordingSaved(this, saved)
            } catch (e: Exception) { }
            recording = false
            recGapListening = false
        }
        try { speech?.destroy() } catch (e: Exception) { }
        speech = null
        try { tone?.release() } catch (e: Exception) { }
        tone = null
        tts?.shutdown()
        tts = null
        overlay?.hide()
        Voice.releaseFx()
        // служба умерла и окно закрыто — шумоподавление больше не нужно
        if (!App.uiVisible) AudioFx.release()
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
            // Длинные команды (например, сообщение в WhatsApp) должны
            // распознаваться целиком, а не обрываться после двух слов.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1900L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

    /**
     * Единственный способ (пере)запустить распознавание.
     *
     * Вызовы «гоняются по поколениям»: каждый новый вызов отменяет все прежние
     * отложенные перезапуски, поэтому копии распознавателя не плодятся и
     * микрофон не дёргается попусту (раньше отложенные циклы накапливались —
     * после закрытия окна приложения залпом создавались десятки распознавателей
     * и микрофон «сбрасывался»).
     */
    private fun restartListening(delay: Long = 300) {
        if (!running) return
        val gen = ++listenGen
        ui.postDelayed({
            if (!running || gen != listenGen) return@postDelayed
            // говорит ли кто-то из Джарвисов (окно, служба, напоминание) —
            // микрофон в это время не включаем: услышим собственный голос
            if (speaking || SpeechState.speaking()) { restartListening(500); return@postDelayed }
            if (snoozed()) { restartListening(5_000); return@postDelayed }
            // идёт запись: микрофон у диктофона, распознаватель включаем только
            // в коротких паузах, чтобы услышать «стоп запись»
            if (mode == Mode.RECORD && !recGapListening) return@postDelayed
            if (uiActive && mode == Mode.WAKE) {
                // окно приложения открыто и само владеет микрофоном — не мешаем,
                // но если микрофон остался у нас, освобождаем
                try { speech?.destroy() } catch (e: Exception) { }
                speech = null
                restartListening(3_000)
                return@postDelayed
            }
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

    /**
     * Страховка от «залипшего» распознавателя: иногда он умирает молча (сервис
     * Google обновился, микрофон перехватило другое приложение, аудиотракт
     * перезапустился) — колбэков нет, ошибок нет. Тогда поднимаем заново,
     * с нарастающей паузой, чтобы не долбить систему.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            ui.postDelayed(this, WATCHDOG_MS)
            if (speaking || working || SpeechState.speaking()) return
            val idle = System.currentTimeMillis() - lastCallback
            when {
                snoozed() -> { /* спим */ }
                // диктофон: следим за временем и за паузами «стоп запись»
                mode == Mode.RECORD && !recGapListening -> tickRecording()
                mode == Mode.RECORD && recGapListening && idle > 6_000 -> {
                    // в паузе ничего не услышали — снова пишем
                    closeRecGap()
                }
                mode == Mode.WAKE && uiActive -> { /* микрофон у окна приложения */ }
                mode == Mode.WAKE && idle > 15_000 -> {
                    silentDrops++
                    val delay = when {
                        silentDrops <= 2 -> 0L
                        silentDrops <= 5 -> 5_000L
                        else -> 30_000L
                    }
                    if (silentDrops >= 2) {
                        noticedMicDrop = true
                        notifyState("Микрофон сбрасывался — перезапускаю прослушивание…")
                    }
                    restartListening(delay)
                }
                // непрерывный диалог: собеседник замолчал — сессия закрыта,
                // снова ждём «Джарвис»
                mode == Mode.COMMAND && followUp && waitingFollowUp &&
                    !speaking && !working &&
                    System.currentTimeMillis() > followDeadline -> {
                    endFollowUpSession()
                }
                mode == Mode.COMMAND && !waitingFollowUp && idle > 18_000 -> {
                    say("Не расслышал, сэр.") { backToWake() }
                }
            }
            // микрофон выключили системным тумблером? Скажем об этом один раз
            try {
                val am = getSystemService(AudioManager::class.java) ?: return
                if (am.isMicrophoneMute) {
                    if (!noticedMicMute) {
                        noticedMicMute = true
                        notifyState("Микрофон выключен в системе — включите его, сэр")
                    }
                } else {
                    noticedMicMute = false
                }
            } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ пробуждение

    /**
     * Услышали «Джарвис». Если следом в той же фразе уже была команда
     * ([tail]) — выполняем её сразу; иначе слушаем команду.
     *
     * Почему без «Слушаю, сэр.» в непрерывном диалоге: пока Джарвис говорит
     * эту фразу, микрофон закрыт (иначе услышим собственный голос) — и команду
     * приходится ждать 2–3 секунды. Теперь после сигнала микрофон свободен
     * почти сразу, а сферу с текстом «Слушаю, сэр…» видно поверх экрана.
     */
    private fun onWake(tail: String? = null): Boolean {
        // Джарвис сам сейчас говорит (доклад о презентации, напоминание,
        // ответ из окна приложения) — микрофон поймал его собственный голос,
        // а не человека. Это не пробуждение.
        if (SpeechState.speaking()) return true
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
        followUp = Prefs.followUp(this)
        waitingFollowUp = false
        closeAfterReply = false
        notifyState("Слушаю команду…")
        overlay?.show("Слушаю, сэр…")

        val phrase = tail?.trim().orEmpty()
        if (phrase.isNotEmpty()) {
            // «Джарвис, который час» — вся фраза уже в микрофоне, не переслушиваем
            handleCommand(phrase)
            return true
        }
        if (followUp) {
            // непрерывный диалог: микрофон почти сразу, «Слушаю, сэр» не говорим
            restartListening(120)
        } else {
            // старый режим: короткое подтверждение и слушаем
            say("Слушаю, сэр.") {
                ui.postDelayed({
                    mode = Mode.COMMAND
                    restartListening(0)
                }, 150)
            }
        }
        return true
    }

    /**
     * Что услышали в паузе между кусками записи: «стоп запись», повтор
     * кодового слова, «Джарвис» (тогда запись заканчивается и дальше идёт
     * обычный диалог) или ничего — тогда просто пишем дальше.
     */
    private fun onGapHeard(text: String?) {
        if (text.isNullOrBlank()) {
            closeRecGap()
            return
        }
        val words = recWords()
        when {
            CodeWords.isStop(text) -> finishDictaphone("остановлено голосом")
            CodeWords.isStopByPhrase(words, text) -> finishDictaphone("остановлено кодовым словом")
            WakeWords.contains(text) -> {
                val tail = WakeWords.removeFromText(text)
                finishDictaphone("остановлено словом «Джарвис»") {
                    if (tail.isNotBlank()) {
                        mode = Mode.COMMAND
                        handleCommand(tail)
                    }
                }
            }
            else -> closeRecGap()
        }
    }

    /**
     * Своё кодовое слово диктофона: услышали — включаем запись.
     * Слово задаёт владелец («кодовое слово: пиши меня»), в программе ничего
     * не зашито; сравнение терпимое к ошибкам распознавания (см. [CodeWords]).
     */
    private fun onCodeWord(texts: ArrayList<String>?) {
        val words = recWords()
        if (words.isEmpty() || SpeechState.speaking() || App.uiVisible) {
            restartListening(150)
            return
        }
        var hit: String? = null
        if (texts != null) {
            for (t in texts) {
                if (t != null && CodeWords.matchesAny(words, t)) { hit = t; break }
            }
        }
        if (hit == null) {
            restartListening(150)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3_500) {
            restartListening(150)
            return
        }
        lastTrigger = now
        // системный диктофон (по умолчанию): открываем его и не берём микрофон
        if (Prefs.recSystem(this) && Recorder.systemIntent(this) != null) {
            notifyState("Кодовое слово — открываю диктофон…")
            overlay?.show("Системный диктофон…")
            say("Открываю системный диктофон, сэр.") {
                mode = Mode.WAKE
                Recorder.systemIntent(this)?.let { launch(it) }
                // микрофон у диктофона телефона: служба вернётся к слову
                // «Джарвис», когда он его отпустит
            }
            return
        }
        notifyState("Кодовое слово — включаю диктофон…")
        overlay?.show("Пишу, сэр…")
        // договариваем и только потом берём микрофон: иначе запишем свой голос
        say("Пишу, сэр.") { startDictaphone() }
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
        followUp = false
        waitingFollowUp = false
        mode = Mode.WAKE
        overlay?.autoHide(2_500)
        notifyState("Слушаю слово «Джарвис»…")
        restartListening(350)
    }

    /** Тихая концовка непрерывного диалога: собеседник замолчал. */
    private fun endFollowUpSession() {
        if (!running) return
        followUp = false
        waitingFollowUp = false
        overlay?.update("Жду «Джарвис»…")
        overlay?.autoHide(1_500)
        notifyState("Слушаю слово «Джарвис»…")
        mode = Mode.WAKE
        restartListening(350)
    }

    /**
     * Что делать после произнесённого ответа: в непрерывном диалоге — снова
     * слушать следующую реплику (без «Джарвис»), иначе — вернуться к ожиданию
     * слова.
     */
    private fun afterReply() {
        if (!running) return
        if (followUp && mode == Mode.COMMAND && !closeAfterReply && !snoozed()) {
            waitingFollowUp = true
            followDeadline = System.currentTimeMillis() + FOLLOW_IDLE_MS
            overlay?.update("Слушаю, сэр… (можно без «Джарвис»)")
            restartListening(500)
            return
        }
        backToWake()
    }

    // ------------------------------------------------------------ выполнение команды

    private fun handleCommand(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            backToWake()
            return
        }
        waitingFollowUp = false
        closeAfterReply = false

        val norm = CommandEngine.normalize(text)
        // одно «Джарвис» посреди разговора — команды нет, просто слушаем дальше
        if (norm.isEmpty()) {
            if (followUp) {
                waitingFollowUp = true
                followDeadline = System.currentTimeMillis() + FOLLOW_IDLE_MS
                restartListening(250)
            } else {
                backToWake()
            }
            return
        }
        // в непрерывном диалоге «пока» / «отбой» завершают разговор
        if (followUp && GOODBYE.matches(norm)) closeAfterReply = true

        overlay?.update("«$text»")
        notifyState("Думаю…")

        val outcome = try {
            CommandEngine.execute(this, text)
        } catch (e: Exception) {
            null
        }

            if (outcome != null) {
            Conversation.add(text, true)
            if (outcome.reply.isNotBlank()) Conversation.add(outcome.reply, false)

            // уходим в сон / открываем окно / чужое приложение —
            // непрерывный диалог на этом заканчивается
            if (outcome.sleepMinutes > 0) {
                closeAfterReply = true
                snooze(outcome.sleepMinutes)
            }
            if (outcome.launch != null) {
                closeAfterReply = true
                launch(outcome.launch)
            }
            if (outcome.openApp) {
                closeAfterReply = true
                launchActivity(false)
            }
            if (outcome.endDialog) {
                closeAfterReply = true
            }

            if (outcome.stopWake) {
                Prefs.setWakeOn(this, false)
                say(outcome.reply) { stopSelf() }
                return
            }
            if (outcome.record == RecCmd.Action.START) {
                // сначала договариваем, потом пишем: иначе в запись попадёт свой голос
                followUp = false
                closeAfterReply = true
                say(outcome.reply) { startDictaphone() }
                return
            }
            if (outcome.record == RecCmd.Action.STOP) {
                finishDictaphone("остановлено голосом")
                return
            }
            if (outcome.async != null) {
                // сначала голосом подтверждаем, что взялись за дело
                if (outcome.reply.isNotBlank()) say(outcome.reply)
                working = true
                notifyState("Работаю над запросом…")
                Thread {
                    var answer = "Не получилось, сэр."
                    try {
                        outcome.async(this) { answer = it }
                    } catch (e: Throwable) {
                        // ловим всё, включая Error: фоновый поток не должен
                        // уронить процесс — иначе приложение вылетит прямо
                        // посреди озвучки приветствия
                        answer = "Ошибка: ${e.message?.take(80) ?: "неизвестно"}"
                    }
                    ui.post {
                        working = false
                        say(answer) { afterReply() }
                    }
                }.start()
                return
            }
            // «стоп»/«хватит» (silent) тоже продолжают слушать — собеседник
            // прервал ответ и хочет сказать следующее
            say(if (outcome.silent) "" else outcome.reply, outcome.mood) { afterReply() }
            return
        }

        // не офлайн-команда — спрашиваем ИИ
        if (!Llm.ready(this)) {
            // откроем окно с настройками ключа — диалог службы завершаем
            closeAfterReply = true
            say("Такой команды офлайн у меня нет, сэр. " + Llm.missingKeyText(this)) {
                launchActivity(false)
                afterReply()
            }
            return
        }
        Conversation.add(text, true)
        working = true
        Llm.chatAsync(this, Conversation.history(), text) { answer ->
            val final = try {
                Research.afterModel(this, text, answer)
            } catch (_: Throwable) {
                answer
            }
            ui.post {
                working = false
                Conversation.add(final, false)
                say(final) { afterReply() }
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

    // ------------------------------------------------------------------ диктофон

    /** Кодовые слова диктофона, если владелец их задал и не выключил. */
    private fun recWords(): List<String> =
        if (Prefs.recOn(this)) Prefs.recWords(this) else emptyList()

    /**
     * Начать запись. Микрофон передаётся [Recorder], распознаватель гаснет:
     * двух владельцев микрофона Android не терпит.
     */
    private fun startDictaphone() {
        if (!running || recording || Recorder.recording) return

        // системный диктофон телефона (по умолчанию): ответ уже проговорен
        // диспетчером или кодовым словом — просто открываем, свой не пишет
        if (Prefs.recSystem(this)) {
            val sys = Recorder.systemIntent(this)
            if (sys != null) {
                mode = Mode.WAKE
                launch(sys)
                return
            }
            // системного диктофона нет — пишем своим встроенным
        }

        try { speech?.destroy() } catch (e: Exception) { }
        speech = null
        val limit = Prefs.recLimitMin(this)
        val err = try {
            Recorder.start(this, limit) { ui.post { finishDictaphone("время записи вышло") } }
        } catch (e: Exception) {
            e.message?.take(80) ?: "сбой записи"
        }
        if (err != null) {
            mode = Mode.WAKE
            say(err) { backToWake() }
            return
        }
        recording = true
        recGapListening = false
        recSegmentStart = System.currentTimeMillis()
        lastRecNotify = recSegmentStart
        followUp = false
        waitingFollowUp = false
        mode = Mode.RECORD
        beep()
        overlay?.update("● Пишу звук…")
        notifyState("Идёт запись — скажите «стоп запись»")
        Notify.recording(this, 0)
    }

    /** Пока пишем: обновляем уведомление и открываем паузу для «стоп запись». */
    private fun tickRecording() {
        if (!Recorder.recording) {
            finishDictaphone("запись оборвалась")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRecNotify >= 15_000) {
            lastRecNotify = now
            val sec = Recorder.elapsedSeconds()
            notifyState("Идёт запись: ${RecCmd.durationLabel(sec)}")
            Notify.recording(this, sec)
            overlay?.update("● Пишу звук… ${RecCmd.durationLabel(sec)}")
        }
        val gap = Prefs.recGapSec(this)
        if (gap > 0 && now - recSegmentStart >= gap * 1_000L) openRecGap()
    }

    /**
     * Пауза на команду: диктофон отпускает микрофон, распознаватель пару
     * секунд слушает «стоп запись» (или повтор кодового слова).
     */
    private fun openRecGap() {
        if (!recording || recGapListening) return
        Recorder.pauseForListening()
        recGapListening = true
        mode = Mode.COMMAND
        lastCallback = System.currentTimeMillis()
        overlay?.update("Слушаю «стоп запись»…")
        restartListening(120)
    }

    /** Пауза закончилась — снова пишем: кусочки потом склеятся в один файл. */
    private fun closeRecGap() {
        if (!recording) return
        recGapListening = false
        val err = try {
            Recorder.resume(this)
        } catch (e: Exception) {
            e.message?.take(80) ?: "сбой записи"
        }
        if (err != null) {
            finishDictaphone("микрофон не вернулся к записи")
            return
        }
        mode = Mode.RECORD
        recSegmentStart = System.currentTimeMillis()
        overlay?.update("● Пишу звук…")
        notifyState("Идёт запись: ${RecCmd.durationLabel(Recorder.elapsedSeconds())}")
    }

    /** Остановить запись, сохранить файл и доложить. */
    private fun finishDictaphone(why: String, then: () -> Unit = {}) {
        val wasRecording = recording || Recorder.recording
        recording = false
        recGapListening = false
        if (!wasRecording) {
            then()
            return
        }
        val saved = try {
            Recorder.stop(this)
        } catch (e: Exception) {
            Recorder.Saved(false, error = e.message?.take(80) ?: "сбой сохранения")
        }
        if (saved.ok) Notify.recordingSaved(this, saved)
        val text = when {
            !saved.ok && saved.error.isNotBlank() -> "Запись не сохранилась, сэр: ${saved.error}"
            saved.seconds < 1 -> "Звук не записался, сэр: получилось меньше секунды."
            else -> "Готово, сэр: запись на ${RecCmd.durationLabel(saved.seconds)}, " +
                "сохранил в ${saved.where}." +
                (if (why.isNotBlank()) " ($why)" else "")
        }
        mode = Mode.WAKE
        overlay?.autoHide(2_500)
        if (running) {
            say(text) {
                then()
                backToWake()
            }
        } else {
            then()
        }
    }

    // ------------------------------------------------------------ речь / окна

    /**
     * @param mood интонация из команды «скажи радостно: …»; null — настроение
     *             определяется по самому тексту ответа ([Emotion])
     */
    private fun say(
        text: String,
        mood: Emotion.Mood? = null,
        attempt: Int = 0,
        onDone: () -> Unit = {}
    ) {
        if (text.isBlank() || !Prefs.ttsOn(this)) {
            onDone()
            return
        }
        val t = tts
        tts?.enabled = Prefs.ttsOn(this)
        if (t == null || !t.ready) {
            if (attempt < 6) ui.postDelayed({ say(text, mood, attempt + 1, onDone) }, 400) else onDone()
            return
        }
        speaking = true
        overlay?.update(text)
        t.speak(text, mood) {
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
            silentDrops = 0
            errorStreak = 0
            if (noticedMicDrop) {
                // микрофон сбрасывался, но мы его вернули — сообщаем
                noticedMicDrop = false
                notifyState("Микрофон снова в строю — слушаю слово «Джарвис»")
            }
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
            // В режиме ожидания слова НЕ рвём распознавание на середине фразы.
            // Раньше «Джарвис» в частичном результате мгновенно гасил микрофон
            // и перезапускал распознаватель — команда «Джарвис, который час»,
            // сказанная одним выдохом, терялась, а пауза до следующего приёма
            // речи ощущалась как «микрофон отключается на пару секунд».
            // Теперь даём фразе договорить и разбираем её целиком в onResults.
        }

        override fun onResults(results: Bundle?) {
            lastCallback = System.currentTimeMillis()
            errorStreak = 0
            silentDrops = 0
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (mode == Mode.WAKE) {
                var hitText: String? = null
                if (!SpeechState.speaking() && texts != null) {
                    for (t in texts) {
                        if (t != null && WakeWords.contains(t)) { hitText = t; break }
                    }
                }
                if (hitText != null) {
                    // «Джарвис, который час» одной фразой — берём команду из хвоста
                    val tail = WakeWords.removeFromText(hitText)
                    onWake(tail.ifBlank { null })
                } else {
                    onCodeWord(texts)
                }
            } else {
                val text = texts?.firstOrNull { !it.isNullOrBlank() }
                // во время записи это пауза «стоп запись», а не обычная команда
                if (recGapListening) {
                    onGapHeard(text)
                    return
                }
                if (text.isNullOrBlank()) {
                    if (waitingFollowUp) {
                        // непрерывный диалог: тишина — просто слушаем дальше
                        restartListening(300)
                    } else {
                        say("Не расслышал, сэр.") { backToWake() }
                    }
                } else {
                    try { speech?.stopListening() } catch (e: Exception) { }
                    handleCommand(text)
                }
            }
        }

        override fun onError(error: Int) {
            lastCallback = System.currentTimeMillis()
            if (recGapListening) {
                // в паузе ничего не услышали — возвращаемся к записи
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    finishDictaphone("нет доступа к микрофону")
                } else {
                    closeRecGap()
                }
                return
            }
            if (mode == Mode.RECORD) return   // микрофон у диктофона — ошибки распознавателя не наши
            if (mode == Mode.COMMAND &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                if (waitingFollowUp) {
                    // пауза в непрерывном диалоге: не нудим «не расслышал»,
                    // а тихо ждём следующую реплику (сессию закроет watchdog)
                    restartListening(250)
                } else {
                    say("Не расслышал, сэр.") { backToWake() }
                }
                return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                notifyState("Нет доступа к микрофону — откройте Джарвис и дайте разрешение")
                Notify.needRestart(this@WakeWordService)
                restartListening(30_000)
                return
            }
            errorStreak++
            // микрофон перехватило другое приложение или аудиотракт перезапустился
            val micTrouble = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_CLIENT
            if (micTrouble && errorStreak >= 2) {
                noticedMicDrop = true
                notifyState(
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                        "Микрофон занят другим приложением — пробую вернуть…"
                    else
                        "Микрофон сбрасывался — переподключаюсь…"
                )
            }
            val delay = when {
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                error == SpeechRecognizer.ERROR_AUDIO -> 2_500L
                errorStreak > 6 -> { errorStreak = 0; 15_000L }
                else -> 500L
            }
            if (mode == Mode.COMMAND && errorStreak > 2 && !waitingFollowUp) {
                backToWake()
            } else {
                restartListening(delay)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
