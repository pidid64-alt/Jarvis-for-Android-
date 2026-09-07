package kz.jarvis.app

import android.content.Context
import org.json.JSONArray

/**
 * Офлайн-списки: покупки и заметки. Хранятся в SharedPreferences,
 * поэтому работают без интернета и переживают перезапуск телефона.
 */
object Lists {

    const val SHOP = "shop"
    const val NOTES = "notes"

    private const val PREF = "jarvis_lists"
    private const val MAX = 60
    private const val SHOW = 10

    // ------------------------------------------------------------- публичное

    /** Добавляет пункт(ы). Текст можно перечислять: «молоко, хлеб». */
    fun add(c: Context, key: String, text: String): String {
        val items = text.split(Regex(",|\\s+и\\s+"))
            .map { it.trim().trim('.', '!', '?', ' ', ':') }
            .filter { it.length >= 2 }
        if (items.isEmpty()) return "Не разобрал, что добавить, сэр."
        val arr = load(c, key)
        for (it in items) if (!contains(arr, it)) arr.put(it)
        while (arr.length() > MAX) arr.remove(0)
        store(c, key, arr)
        val added = items.joinToString(", ")
        return "Добавил в ${name(key, false)}: «$added». Всего пунктов: ${arr.length()}."
    }

    fun show(c: Context, key: String): String {
        val arr = load(c, key)
        if (arr.length() == 0) return "${name(key, true)} пуст, сэр."
        val items = (0 until minOf(arr.length(), SHOW)).map { arr.optString(it) }
        val tail = if (arr.length() > SHOW) " …и ещё ${arr.length() - SHOW}." else "."
        return "В ${name(key, false)} ${arr.length()} пункт(ов): ${items.joinToString("; ")}$tail"
    }

    /** Удаляет пункт по части названия: «удали молоко из списка покупок». */
    fun remove(c: Context, key: String, item: String): String {
        val needle = item.trim().trim('.', '!', '?', ' ', ':').lowercase()
        if (needle.length < 2) return "Скажите, что убрать, сэр."
        val arr = load(c, key)
        val out = JSONArray()
        var removed = 0
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.lowercase().contains(needle) || needle.contains(v.lowercase())) removed++
            else out.put(v)
        }
        if (removed == 0) return "Не нашёл «$item» в ${name(key, false)}."
        store(c, key, out)
        return "Убрал «$item» из ${name(key, false)}. Осталось пунктов: ${out.length()}."
    }

    fun clear(c: Context, key: String): String {
        val n = load(c, key).length()
        store(c, key, JSONArray())
        return if (n == 0) "${name(key, true)} и так пуст, сэр." else "${name(key, true)} очищен: убрано $n пункт(ов)."
    }

    fun count(c: Context, key: String): Int = load(c, key).length()

    // ---------------------------------------------------------------- внутри

    private fun name(key: String, upper: Boolean): String = when {
        key == SHOP && upper -> "Список покупок"
        key == SHOP -> "список покупок"
        upper -> "Список заметок"
        else -> "списке заметок"
    }

    private fun contains(arr: JSONArray, item: String): Boolean {
        val a = item.lowercase()
        for (i in 0 until arr.length()) if (arr.optString(i).lowercase() == a) return true
        return false
    }

    private fun load(c: Context, key: String): JSONArray = try {
        JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    private fun store(c: Context, key: String, arr: JSONArray) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }
}
