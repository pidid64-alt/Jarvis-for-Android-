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
 * Напоминания и таймеры без интернета: точный будильник + уведомление + озвучка.
 * После перезагрузки [restore] ставит их снова.
 */
object ReminderScheduler {

    const val EXTRA_TEXT = "reminder_text"
    const val EXTRA_AT = "reminder_at"
    const val EXTRA_KIND = "reminder_kind"

    const val KIND_REMINDER = "reminder"
    const val KIND_TIMER = "timer"

    private const val PREF = "jarvis_reminders"
    private const val K_LIST = "items"
    private const val K_NEXT = "next_id"
    private const val REQ_BASE = 9100
    private const val MAX_ITEMS = 30

    fun schedule(c: Context, atMillis: Long, text: String, kind: String = KIND_REMINDER): Boolean {
        val id = nextId(c)
        val ok = arm(c, id, atMillis, text, kind)
        if (ok) save(c, id, atMillis, text, kind)
        return ok
    }

    /** Точные будильники разрешены? (Android 12+ требует отдельного разрешения.) */
    fun exactAllowed(c: Context): Boolean = try {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    } catch (e: Exception) {
        false
    }

    /** После BOOT_COMPLETED / обновления приложения — заново вооружить будущие. */
    fun restore(c: Context) {
        val now = System.currentTimeMillis()
        val arr = load(c)
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = o.optLong("at")
            if (at <= now) continue
            val id = o.optInt("id", reqId(at))
            val text = o.optString("text")
            val kind = o.optString("kind", KIND_REMINDER)
            if (arm(c, id, at, text, kind)) keep.put(o)
        }
        store(c, keep)
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

    fun cancelAll(c: Context, kind: String? = null): Int {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val arr = load(c)
        val keep = JSONArray()
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("kind", KIND_REMINDER)
            if (kind != null && k != kind) {
                keep.put(o)
                continue
            }
            val at = o.optLong("at")
            val id = o.optInt("id", reqId(at))
            try {
                am.cancel(pending(c, id, at, o.optString("text"), k))
            } catch (e: Exception) { }
            n++
        }
        store(c, keep)
        return n
    }

    fun list(c: Context, kind: String? = null): String {
        val arr = load(c)
        val items = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (kind != null && o.optString("kind", KIND_REMINDER) != kind) continue
            items.add(o)
        }
        val noun = when (kind) {
            KIND_TIMER -> if (items.isEmpty()) "Таймеров нет, сэр." else "Таймеров: ${items.size}. "
            else -> if (items.isEmpty()) "Напоминаний нет, сэр." else "Напоминаний: ${items.size}. "
        }
        if (items.isEmpty()) return noun
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
        val sb = StringBuilder(noun)
        for (i in 0 until minOf(items.size, 3)) {
            val o = items[i]
            val label = if (o.optString("kind") == KIND_TIMER) "таймер"
            else "«${o.optString("text")}»"
            sb.append("${fmt.format(Date(o.optLong("at")))} — $label; ")
        }
        return sb.toString().trimEnd(';', ' ')
    }

    // ------------------------------------------------------------- внутри

    private fun nextId(c: Context): Int {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = sp.getInt(K_NEXT, 1)
        sp.edit().putInt(K_NEXT, n + 1).apply()
        return REQ_BASE + n
    }

    private fun reqId(at: Long): Int = REQ_BASE + (at % 100_000L).toInt()

    private fun arm(c: Context, id: Int, atMillis: Long, text: String, kind: String): Boolean {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, id, atMillis, text, kind)
        return try {
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
    }

    private fun pending(c: Context, id: Int, at: Long, text: String, kind: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, id,
            Intent(c, ReminderReceiver::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_AT, at)
                .putExtra(EXTRA_KIND, kind),
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

    private fun save(c: Context, id: Int, at: Long, text: String, kind: String) {
        val arr = load(c)
        arr.put(JSONObject().put("id", id).put("at", at).put("text", text).put("kind", kind))
        while (arr.length() > MAX_ITEMS) arr.remove(0)
        store(c, arr)
    }
}
