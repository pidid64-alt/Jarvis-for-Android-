package kz.jarvis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            WakeWordService.CH_ID,
            "Голосовая активация",
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "Фоновая служба ожидания команды «Джарвис»"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }
}
