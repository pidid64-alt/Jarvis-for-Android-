package kz.jarvis.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Курсы валют без API-ключей: открытый сервис open.er-api.com (обновляется
 * раз в сутки). Вызывается из фонового потока — см. CommandEngine.Outcome.async.
 */
object Rates {

    data class Query(val amount: Double, val from: String, val to: String)

    private class Cur(
        val code: String,
        val stems: List<String>,
        val one: String,
        val few: String,
        val many: String
    )

    private val currencies = listOf(
        Cur("USD", listOf("доллар", "usd", "бакс"), "доллар", "доллара", "долларов"),
        Cur("EUR", listOf("евро", "eur"), "евро", "евро", "евро"),
        Cur("RUB", listOf("рубль", "руб", "rub"), "рубль", "рубля", "рублей"),
        Cur("KZT", listOf("тенге", "kzt", "тг"), "тенге", "тенге", "тенге"),
        Cur("CNY", listOf("юан", "cny", "женьминь"), "юань", "юаня", "юаней"),
        Cur("JPY", listOf("иен", "jpy"), "иена", "иены", "иен"),
        Cur("GBP", listOf("стерлинг", "gbp"), "фунт стерлингов", "фунта стерлингов", "фунтов стерлингов"),
        Cur("TRY", listOf("лир", "try"), "лира", "лиры", "лир"),
        Cur("UAH", listOf("гривн", "uah"), "гривна", "гривны", "гривен"),
        Cur("AMD", listOf("драм", "amd"), "драм", "драма", "драмов"),
        Cur("KGS", listOf("сом", "kgs"), "сом", "сома", "сомов"),
        Cur("UZS", listOf("сум", "uzs"), "сум", "сума", "сумов"),
        Cur("AED", listOf("дирхам", "aed"), "дирхам", "дирхама", "дирхамов"),
        Cur("CHF", listOf("франк", "chf"), "франк", "франка", "франков"),
        Cur("PLN", listOf("злот", "pln"), "злотый", "злотых", "злотых"),
        Cur("INR", listOf("руп", "inr"), "рупия", "рупии", "рупий"),
        Cur("AZN", listOf("манат", "azn"), "манат", "маната", "манатов"),
        Cur("GEL", listOf("лари", "gel"), "лари", "лари", "лари"),
        Cur("BYN", listOf("белорусск", "byn"), "белорусский рубль", "белорусских рубля", "белорусских рублей"),
        Cur("AUD", listOf("австралийск", "aud"), "австралийский доллар", "австралийских доллара", "австралийских долларов"),
        Cur("CAD", listOf("канадск", "cad"), "канадский доллар", "канадских доллара", "канадских долларов"),
        Cur("BRL", listOf("реал", "brl"), "реал", "реала", "реалов")
    )

    /** Слова, которые начинаются как валюта, но ею не являются. */
    private val notCurrency = listOf("сумм", "сомн", "реальн", "рубер", "лирическ")

    private fun byCode(code: String): Cur = currencies.first { it.code == code }

    /** Ищет валюту в токене: «долларов» -> USD. */
    fun find(token: String): String? {
        val t = token.lowercase(Locale.ROOT).replace('ё', 'е')
        if (notCurrency.any { t.startsWith(it) }) return null
        var best: Cur? = null
        var bestLen = 0
        for (c in currencies) for (s in c.stems) {
            if (t.startsWith(s) && s.length > bestLen) { best = c; bestLen = s.length }
        }
        return best?.code
    }

    /**
     * Разбирает фразу: «курс доллара», «100 долларов в тенге»,
     * «сколько рублей в 50 тенге», «доллар к рублю».
     */
    fun parse(raw: String): Query? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null
        if (Regex("курс\\s+валют|все курсы|какие курсы").containsMatchIn(s)) return Query(0.0, "ALL", "KZT")

        val found = ArrayList<String>()
        for (tok in s.split(Regex("\\s+"))) {
            find(tok)?.let { if (found.isEmpty() || found.last() != it) found.add(it) }
        }
        if (found.isEmpty()) return null

        val num = Regex("\\d+(?:[.,]\\d+)?").find(s)?.value?.replace(',', '.')?.toDoubleOrNull() ?: 1.0
        val from = found[0]
        val to = found.getOrNull(1) ?: if (from == "KZT") "USD" else "KZT"

        // «курс доллара», «100 долларов в тенге», «доллар к рублю» — а иначе пусть решает ИИ
        val looksLikeRates = Regex("курс").containsMatchIn(s) || found.size >= 2 ||
            Regex("\\d").containsMatchIn(s)
        if (!looksLikeRates) return null
        return Query(num, from, to)
    }

    /** Готовый ответ (выполняется в фоновом потоке). */
    fun answer(q: Query): String {
        if (q.from == "ALL") return summary()
        val rates = fetch(q.from) ?: return "Нет связи с сервером курсов, сэр."
        val r = rates[q.to] ?: return "Курс ${byCode(q.to).many} мне не пришёл, сэр."
        val res = q.amount * r
        val from = byCode(q.from)
        val to = byCode(q.to)
        val a = MathEngine.format(q.amount)
        val fromName = TextTools.plural(q.amount.toLong(), from.one, from.few, from.many)
        val toName = to.many
        return "$a $fromName — это ${MathEngine.format(res)} $toName (курс на ${today()})."
    }

    /** «Курс валют»: доллар, евро, рубль и юань к тенге. */
    fun summary(): String {
        val rates = fetch("USD") ?: return "Нет связи с сервером курсов, сэр."
        val kzt = rates["KZT"] ?: return "Курс тенге не пришёл, сэр."
        fun per(code: String): String? {
            val r = rates[code] ?: return null
            return MathEngine.format(kzt / r)
        }
        val parts = ArrayList<String>()
        per("EUR")?.let { parts.add("евро — $it") }
        per("RUB")?.let { parts.add("рубль — $it") }
        per("CNY")?.let { parts.add("юань — $it") }
        val tail = if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
        return "Доллар — ${MathEngine.format(kzt)} тенге$tail (курс на ${today()})."
    }

    private fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())

    private fun fetch(base: String): Map<String, Double>? {
        val url = "https://open.er-api.com/v6/latest/$base"
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 9000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "Jarvis/2.1")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return null
            val o = JSONObject(text)
            if (o.optString("result") != "success") return null
            val r = o.optJSONObject("rates") ?: return null
            val out = HashMap<String, Double>()
            for (k in r.keys()) r.optDouble(k).takeIf { !it.isNaN() }?.let { out[k] = it }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }

    fun help(): String = """
        Курсы валют (нужен интернет), сэр:
        • «курс доллара», «курс тенге», «курс валют»
        • «100 долларов в тенге», «сколько рублей в 500 тенге»
        • «доллар к рублю», «курс евро к юаню»
        Доступны: доллар, евро, рубль, тенге, юань, иена, фунт, лира, гривна,
        драм, сом, сум, дирхам, франк, злотый, рупия, манат, лари и другие.
    """.trimIndent()
}
