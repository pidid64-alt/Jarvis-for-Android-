package kz.jarvis.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Напоминания без интернета: точный будильник + уведомление + озвучка.
 * Работает, даже когда приложение закрыто (пока процесс не выгружен системой).
 */
object ReminderScheduler {

    const val EXTRA_TEXT = "reminder_text"
    const val EXTRA_AT = "reminder_at"

    private const val PREF = "jarvis_reminders"
    private const val K_LIST = "items"
    private const val REQ_BASE = 9100
    private const val MAX_ITEMS = 30

    fun schedule(c: Context, atMillis: Long, text: String): Boolean {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = reqId(atMillis)
        val pi = pending(c, id, atMillis, text)
        val ok = try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
            true
        } catch (e: Exception) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
                true
            } catch (e2: Exception) {
                false
            }
        }
        if (ok) save(c, atMillis, text)
        return ok
    }

    /** Точные будильники разрешены? (Android 12+ требует отдельного разрешения.) */
    fun exactAllowed(c: Context): Boolean = try {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    } catch (e: Exception) {
        false
    }

    fun remove(c: Context, atMillis: Long) {
        val arr = load(c)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("at") != atMillis) out.put(o)
        }
        store(c, out)
    }

    fun cancelAll(c: Context): Int {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val arr = load(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = o.optLong("at")
            try { am.cancel(pending(c, reqId(at), at, o.optString("text"))) } catch (e: Exception) { }
        }
        store(c, JSONArray())
        return arr.length()
    }

    fun list(c: Context): String {
        val arr = load(c)
        if (arr.length() == 0) return "Напоминаний нет, сэр."
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        val sb = StringBuilder("Напоминаний: ${arr.length()}. ")
        for (i in 0 until minOf(arr.length(), 3)) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append("${fmt.format(Date(o.optLong("at")))} — «${o.optString("text")}»; ")
        }
        return sb.toString().trimEnd(';', ' ')
    }

    // ------------------------------------------------------------- внутри

    private fun reqId(at: Long): Int = REQ_BASE + (at % 100_000L).toInt()

    private fun pending(c: Context, id: Int, at: Long, text: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, ReminderReceiver::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun load(c: Context): JSONArray = try {
        JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_LIST, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun store(c: Context, arr: JSONArray) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(K_LIST, arr.toString()).apply()
    }

    private fun save(c: Context, at: Long, text: String) {
        val arr = load(c)
        arr.put(JSONObject().put("at", at).put("text", text))
        while (arr.length() > MAX_ITEMS) arr.remove(0)
        store(c, arr)
    }
}
