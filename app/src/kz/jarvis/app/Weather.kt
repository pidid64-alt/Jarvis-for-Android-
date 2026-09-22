package kz.jarvis.app

import android.content.Context
import org.json.JSONObject

/**
 * Погода без API-ключей.
 *
 *  • «какая погода сейчас» — короткий текстовый ответ wttr.in ([fetch]);
 *  • «какая погода будет завтра», «прогноз на неделю» — прогноз на несколько
 *    дней вперёд по открытому open-meteo.com ([forecast]), с запасным вариантом
 *    на wttr.in, если open-meteo не ответил.
 *
 * Всё вызывается из фонового потока (см. CommandEngine.Outcome.async).
 */
object Weather {

    private const val UA = "Jarvis/2.9 (Android)"

    // ------------------------------------------------------------------ сейчас

    fun fetch(city: String?): String {
        val place = city?.trim().orEmpty()
        val fmt = "%l: %c %t (ощущается %f), ветер %w, влажность %h"
        val url = "https://wttr.in/" + java.net.URLEncoder.encode(place, "UTF-8") +
            "?format=" + java.net.URLEncoder.encode(fmt, "UTF-8") + "&lang=ru"
        return try {
            val answer = get(url) ?: return "Нет связи с сервером погоды, сэр."
            val code = answer.first
            val text = clean(answer.second)
            when {
                code !in 200..299 -> "Сервер погоды ответил ошибкой $code, сэр."
                text.length < 3 || !text.any { it.isDigit() } ->
                    "Не разобрал ответ сервера погоды, сэр."
                else -> "Погода: $text"
            }
        } catch (e: Exception) {
            "Нет связи с сервером погоды: ${e.message?.take(80) ?: "ошибка сети"}"
        }
    }

    // ------------------------------------------------------------------ прогноз

    /**
     * Прогноз на [Forecast.Query.days] дней, начиная с [Forecast.Query.offset].
     * Город — из фразы; если не назван, берём последнее известное место телефона.
     */
    fun forecast(c: Context, q: Forecast.Query): String {
        val city = q.city?.trim().orEmpty()
        var lat = 0.0
        var lon = 0.0
        var place = city
        if (city.isNotEmpty()) {
            val g = geocode(city)
            if (g == null) {
                // open-meteo не знает город — попробуем wttr.in, он понимает названия
                return wttrForecast(city, q)
            }
            lat = g.second
            lon = g.third
            place = g.first.ifBlank { city }
        } else {
            val fix = try {
                Geo.lastKnown(c)
            } catch (e: Exception) {
                null
            }
            if (fix == null) {
                return "Не знаю, где вы, сэр. Назовите город — «какая погода будет завтра в Сочи» — " +
                    "или дайте Джарвису доступ к местоположению."
            }
            lat = fix.lat
            lon = fix.lng
            place = reverseName(fix) ?: ""
        }

        val days = q.totalDays
        val json = get(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon&forecast_days=$days&timezone=auto" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,precipitation_sum,wind_speed_10m_max" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m"
        )
        val body = json?.takeIf { it.first in 200..299 }?.second
        val parsed = body?.let { parseMeteo(it, q) }
        if (!parsed.isNullOrEmpty()) return Forecast.speak(place, parsed, q.part)

        // запасной вариант: wttr.in отдаёт три дня
        return if (days <= 3) wttrForecast(if (city.isNotEmpty()) city else "$lat,$lon", q)
        else "Сервер прогноза не ответил, сэр. Попробуйте позже или спросите про один день."
    }

    /** Координаты города (open-meteo geocoding, без ключа). */
    private fun geocode(name: String): Triple<String, Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=3&language=ru&format=json" +
            "&name=" + java.net.URLEncoder.encode(name, "UTF-8")
        val body = get(url)?.takeIf { it.first in 200..299 }?.second ?: return null
        return try {
            val arr = JSONObject(body).optJSONArray("results") ?: return null
            if (arr.length() == 0) return null
            val o = arr.optJSONObject(0) ?: return null
            Triple(
                o.optString("name", name),
                o.optDouble("latitude", Double.NaN),
                o.optDouble("longitude", Double.NaN)
            ).takeIf { !it.second.isNaN() && !it.third.isNaN() }
        } catch (e: Exception) {
            null
        }
    }

    /** Название места по координатам — для фразы «завтра в Астане…». */
    private fun reverseName(fix: Geo.Fix): String? = try {
        val url = String.format(
            java.util.Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&accept-language=ru&lat=%.5f&lon=%.5f",
            fix.lat, fix.lng
        )
        val body = get(url)?.takeIf { it.first in 200..299 }?.second ?: return null
        val a = JSONObject(body).optJSONObject("address") ?: return null
        a.optString("city").ifBlank {
            a.optString("town").ifBlank { a.optString("village").ifBlank { a.optString("county") } }
        }.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Разбирает ответ open-meteo в готовые дни прогноза. */
    private fun parseMeteo(body: String, q: Forecast.Query): List<Pair<Int, Forecast.Day>>? {
        return try {
            val o = JSONObject(body)
            val daily = o.optJSONObject("daily") ?: return null
            val times = strings(daily, "time")
            if (times.isEmpty()) return null
            val tMax = doubles(daily, "temperature_2m_max")
            val tMin = doubles(daily, "temperature_2m_min")
            val code = ints(daily, "weather_code")
            val prob = ints(daily, "precipitation_probability_max")
            val sum = doubles(daily, "precipitation_sum")
            val wind = doubles(daily, "wind_speed_10m_max")

            val hourly = o.optJSONObject("hourly")
            val hTimes = if (hourly != null) strings(hourly, "time") else emptyList()
            val hT = if (hourly != null) doubles(hourly, "temperature_2m") else emptyList()
            val hCode = if (hourly != null) ints(hourly, "weather_code") else emptyList()
            val hProb = if (hourly != null) ints(hourly, "precipitation_probability") else emptyList()
            val hWind = if (hourly != null) doubles(hourly, "wind_speed_10m") else emptyList()

            val out = ArrayList<Pair<Int, Forecast.Day>>()
            for (i in 0 until q.days) {
                val idx = q.offset + i
                if (idx >= times.size) break
                val date = times[idx]
                val info = Forecast.DayInfo(
                    tMin = at(tMin, idx),
                    tMax = at(tMax, idx),
                    code = at(code, idx).toInt(),
                    precipProb = at(prob, idx).toInt(),
                    precipMm = at(sum, idx),
                    wind = at(wind, idx)
                )
                val part = q.part?.let { p ->
                    partInfo(date, p, hTimes, hT, hCode, hProb, hWind)
                }
                out.add(idx to Forecast.Day(date, info, part))
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }

    /** Средняя погода за часть суток («вечером» → часы 17..22). */
    private fun partInfo(
        date: String,
        part: Forecast.Part,
        times: List<String>,
        temps: List<Double>,
        codes: List<Int>,
        probs: List<Int>,
        winds: List<Double>
    ): Forecast.PartInfo? {
        if (times.isEmpty()) return null
        val hours = Forecast.hours(part)
        val idx = ArrayList<Int>()
        for (i in times.indices) {
            val t = times[i]
            if (!t.startsWith(date)) continue
            val h = t.substringAfter('T', "").take(2).toIntOrNull() ?: continue
            if (h in hours) idx.add(i)
        }
        if (idx.isEmpty()) return null
        val t = idx.map { at(temps, it) }.filter { !it.isNaN() }
        val codesList = idx.map { at(codes, it).toInt() }
        return Forecast.PartInfo(
            t = if (t.isEmpty()) Double.NaN else t.average(),
            code = codesList.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 3,
            precipProb = idx.maxOf { at(probs, it).toInt() },
            wind = idx.maxOf { at(winds, it) }
        )
    }

    /** Запасной источник: wttr.in?format=j1 (три дня). */
    private fun wttrForecast(place: String, q: Forecast.Query): String {
        val url = "https://wttr.in/" + java.net.URLEncoder.encode(place, "UTF-8") +
            "?format=j1&lang=ru"
        val body = get(url)?.takeIf { it.first in 200..299 }?.second
            ?: return "Сервер прогноза не ответил, сэр."
        return try {
            val weather = JSONObject(body).optJSONArray("weather") ?: return "Прогноз не пришёл, сэр."
            val out = ArrayList<Pair<Int, Forecast.Day>>()
            for (i in 0 until q.days) {
                val idx = q.offset + i
                if (idx >= weather.length()) break
                val d = weather.optJSONObject(idx) ?: continue
                val hourly = d.optJSONArray("hourly")
                val mid = hourly?.optJSONObject(hourly.length() / 2)
                val prob = hourly?.let { arr ->
                    (0 until arr.length()).maxOf { arr.optJSONObject(it)?.optString("chanceofrain")?.toIntOrNull() ?: 0 }
                } ?: 0
                val wind = mid?.optString("windspeedKmph")?.toDoubleOrNull() ?: 0.0
                val info = Forecast.DayInfo(
                    tMin = d.optString("mintempC").toDoubleOrNull() ?: Double.NaN,
                    tMax = d.optString("maxtempC").toDoubleOrNull() ?: Double.NaN,
                    code = wttrCode(mid?.optString("weatherCode")?.toIntOrNull() ?: 0, prob),
                    precipProb = prob,
                    precipMm = mid?.optString("precipMM")?.toDoubleOrNull() ?: 0.0,
                    wind = wind
                )
                val part = q.part?.let { p ->
                    val h = Forecast.hours(p)
                    val items = (0 until (hourly?.length() ?: 0)).mapNotNull { hourly?.optJSONObject(it) }
                        .filter { (it.optString("time").toIntOrNull() ?: -1) / 100 in h }
                    if (items.isEmpty()) null else Forecast.PartInfo(
                        t = items.map { it.optString("tempC").toDoubleOrNull() ?: Double.NaN }
                            .filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() },
                        code = wttrCode(items[items.size / 2].optString("weatherCode").toIntOrNull() ?: 0, prob),
                        precipProb = items.maxOf { it.optString("chanceofrain").toIntOrNull() ?: 0 },
                        wind = items.maxOf { it.optString("windspeedKmph").toDoubleOrNull() ?: 0.0 }
                    )
                }
                out.add(idx to Forecast.Day(d.optString("date"), info, part))
            }
            if (out.isEmpty()) "Прогноз для «$place» не нашёлся, сэр."
            else Forecast.speak(place, out, q.part)
        } catch (e: Exception) {
            "Не разобрал прогноз, сэр: ${e.message?.take(60) ?: "ошибка"}"
        }
    }

    /** Код погоды wttr.in (0..395) → ближайший код WMO, который знает [Forecast]. */
    private fun wttrCode(code: Int, rainProb: Int): Int = when {
        code in 386..395 -> 95
        code in 350..377 -> 85
        code in 320..338 -> 71
        code in 227..230 -> 73
        code in 176..185 -> 61
        code in 263..296 -> 61
        code in 299..317 -> 63
        code == 200 -> 95
        code in 143..150 -> 45
        code in 116..119 -> 2
        code == 113 -> 0
        rainProb >= 70 -> 63
        else -> 3
    }

    // ------------------------------------------------------------------ сеть

    /** GET-запрос: код ответа и текст. */
    private fun get(url: String): Pair<Int, String>? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 9000
        conn.readTimeout = 14000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json,text/plain")
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
        conn.disconnect()
        code to text
    } catch (e: Exception) {
        null
    }

    private fun clean(text: String): String =
        text.replace(Regex("[\\u2600-\\u27BF\\uFE0F]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun doubles(o: JSONObject, key: String): List<Double> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { if (a.isNull(it)) Double.NaN else a.optDouble(it) }
    }

    private fun ints(o: JSONObject, key: String): List<Int> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { if (a.isNull(it)) 0 else a.optInt(it) }
    }

    private fun at(list: List<Double>, i: Int): Double = list.getOrElse(i) { Double.NaN }
    private fun at(list: List<Int>, i: Int): Int = list.getOrElse(i) { 0 }

    fun help(): String = """
        Погода, сэр:
        • «какая погода», «погода в Сочи» — что за окном сейчас
        • «какая погода будет завтра», «прогноз на послезавтра»
        • «будет ли дождь вечером», «что с погодой в субботу утром»
        • «прогноз на неделю», «погода на три дня», «что с погодой через 4 дня»
        Город можно не называть — возьму последнее известное место телефона
        (нужен доступ к геолокации). Данные: open-meteo.com и wttr.in, без ключей.
    """.trimIndent()
}
