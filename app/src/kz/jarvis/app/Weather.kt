package kz.jarvis.app

/**
 * Погода без API-ключей: текстовый ответ сервиса wttr.in.
 * Вызывается из фонового потока (см. CommandEngine.Outcome.async).
 */
object Weather {

    fun fetch(city: String?): String {
        val place = city?.trim().orEmpty()
        val fmt = "%l: %c %t (ощущается %f), ветер %w, влажность %h"
        val url = "https://wttr.in/" + java.net.URLEncoder.encode(place, "UTF-8") +
            "?format=" + java.net.URLEncoder.encode(fmt, "UTF-8") + "&lang=ru"
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 9000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "curl/8.0")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            conn.disconnect()
            val clean = text.replace(Regex("[\\u2600-\\u27BF\\uFE0F]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            when {
                code !in 200..299 -> "Сервер погоды ответил ошибкой $code, сэр."
                clean.length < 3 || !clean.any { it.isDigit() } ->
                    "Не разобрал ответ сервера погоды, сэр."
                place.isEmpty() -> "Погода: $clean"
                else -> "Погода: $clean"
            }
        } catch (e: Exception) {
            "Нет связи с сервером погоды: ${e.message?.take(80) ?: "ошибка сети"}"
        }
    }
}
