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

/**
 * Фоновая служба голосовой активации: непрерывно слушает микрофон
 * и ждёт слово-триггер «Джарвис».
 */
class WakeWordService : Service() {

    companion object {
        const val CH_ID = "jarvis_wake"
        const val EXTRA_AUTO_LISTEN = "auto_listen"
        private val WAKE_PATTERN = Regex("джарвис|джервис|джарус|jarvis|джарв")

        fun start(c: Context) {
            val i = Intent(c, WakeWordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Exception) {
                // на Android 14+ фоновый запуск микрофон-службы может быть запрещён
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, WakeWordService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var speech: SpeechRecognizer? = null
    private var running = false
    private var lastTrigger = 0L
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startForeground(1, buildNotification())
            tone = try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            } catch (e: Exception) {
                null
            }
            ui.postDelayed({ restartListening() }, 200)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            speech?.destroy()
        } catch (e: Exception) {
        }
        speech = null
        tone?.release()
        tone = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH_ID) else Notification.Builder(this)
        return b
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис на связи")
            .setContentText("Скажите «Джарвис», чтобы разбудить")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun restartListening(delay: Long = 300) {
        if (!running) return
        ui.postDelayed({
            if (!running) return@postDelayed
            try {
                speech?.destroy()
            } catch (e: Exception) {
            }
            try {
                speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(wakeListener)
                }
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }
                speech?.startListening(i)
            } catch (e: Exception) {
                ui.postDelayed({ restartListening(0) }, 1500)
            }
        }, delay)
    }

    private fun scanForWake(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val s = text.lowercase().replace('ё', 'е')
        if (!WAKE_PATTERN.containsMatchIn(s)) return false
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3500) return true // недавно срабатывали — молчим
        lastTrigger = now
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
        } catch (e: Exception) {
        }
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 70, 60), -1))
        } catch (e: Exception) {
        }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_AUTO_LISTEN, true)
        try {
            startActivity(i)
        } catch (e: Exception) {
        }
        return true
    }

    private val wakeListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (texts != null) {
                for (t in texts) if (scanForWake(t)) {
                    try {
                        speech?.stopListening()
                    } catch (e: Exception) {
                    }
                    return
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            var triggered = false
            if (texts != null) {
                for (t in texts) if (scanForWake(t)) {
                    triggered = true
                    break
                }
            }
            // после срабатывания пауза подольше: главное приложение само начнёт слушать
            restartListening(if (triggered) 4000L else 150L)
        }

        override fun onError(error: Int) {
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Long.MAX_VALUE
                else -> 500L
            }
            if (delay != Long.MAX_VALUE) restartListening(delay) else stopSelf()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
