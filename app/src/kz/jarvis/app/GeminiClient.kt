package kz.jarvis.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Клиент Google Gemini (`:generateContent`). Системный промпт и тело запроса
 * берутся из [Persona] и [LlmRequests] — там же они у остальных провайдеров.
 */
object GeminiClient {

    private val executor = Executors.newSingleThreadExecutor()

    /** Асинхронный запрос. cb вызывается в фоновом потоке — вызывающий сам переводит в UI. */
    fun chatAsync(
        key: String,
        model: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        baseUrl: String = Providers.ALL[0].baseUrl,
        cb: (String) -> Unit
    ) {
        executor.execute {
            val answer = try {
                chat(baseUrl, key, model, history, text)
            } catch (e: Exception) {
                "Сэр, связь с сервером оборвалась: ${e.message ?: "неизвестная ошибка"}"
            }
            cb(answer)
        }
    }

    /** Быстрый тест ключа. Возвращает null при успехе, иначе текст ошибки. */
    fun ping(
        key: String,
        model: String,
        baseUrl: String = Providers.ALL[0].baseUrl
    ): String? = try {
        val answer = chat(baseUrl, key, model, emptyList(), "Ответь ровно одним словом: работает")
        if (answer.startsWith("Ошибка")) answer else null
    } catch (e: Exception) {
        e.message ?: "ошибка сети"
    }

    /** Синхронный запрос со своим системным промптом (для презентаций). */
    fun complete(
        baseUrl: String,
        key: String,
        model: String,
        system: String,
        text: String,
        maxTokens: Int
    ): String = try {
        chat(baseUrl, key, model, emptyList(), text, system, maxTokens)
    } catch (e: Exception) {
        "Сэр, связь с сервером оборвалась: ${e.message ?: "неизвестная ошибка"}"
    }

    private fun chat(
        baseUrl: String,
        key: String,
        model: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        system: String = Persona.SYSTEM_PROMPT,
        maxTokens: Int = 600
    ): String {
        val conn = URL(LlmRequests.geminiUrl(baseUrl, model)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)

            val bytes = LlmRequests.geminiBody(system, history, text, maxTokens)
                .toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(raw).getJSONObject("error").optString("message")
                } catch (e: Exception) {
                    raw.take(200).ifBlank { "HTTP $code" }
                }
                val hint = if (code == 400 || code == 401 || code == 403)
                    " — проверьте API-ключ в настройках" else ""
                return "Ошибка API ($code): ${msg.take(300)}$hint"
            }

            return try {
                val candidates = JSONObject(raw).optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) "Пустой ответ модели"
                else {
                    val parts = candidates.getJSONObject(0)
                        .getJSONObject("content").optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            sb.append(parts.getJSONObject(i).optString("text"))
                        }
                    }
                    val out = sb.toString().trim()
                    if (out.isEmpty()) "Модель промолчала, сэр" else out
                }
            } catch (e: Exception) {
                "Не смог разобрать ответ модели: ${e.message}"
            }
        } finally {
            conn.disconnect()
        }
    }
}
