package kz.jarvis.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Клиент Anthropic Claude (`/v1/messages`). */
object AnthropicClient {

    private const val API_VERSION = "2023-06-01"

    private val executor = Executors.newSingleThreadExecutor()

    fun chatAsync(
        baseUrl: String,
        key: String,
        model: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        cb: (String) -> Unit
    ) {
        executor.execute {
            cb(
                try {
                    chat(baseUrl, key, model, history, text)
                } catch (e: Exception) {
                    "Сэр, связь с сервером оборвалась: ${e.message ?: "неизвестная ошибка"}"
                }
            )
        }
    }

    fun ping(baseUrl: String, key: String, model: String): String? = try {
        val answer = chat(baseUrl, key, model, emptyList(), "Ответь ровно одним словом: работает")
        if (answer.startsWith("Ошибка")) answer else null
    } catch (e: Exception) {
        e.message ?: "ошибка сети"
    }

    private fun chat(
        baseUrl: String,
        key: String,
        model: String,
        history: List<Pair<Boolean, String>>,
        text: String
    ): String {
        val conn = URL(LlmRequests.anthropicUrl(baseUrl)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", API_VERSION)

            val bytes = LlmRequests.anthropicBody(model, Persona.SYSTEM_PROMPT, history, text)
                .toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(raw).getJSONObject("error").optString("message")
                } catch (e: Exception) {
                    raw.take(200).ifBlank { "HTTP $code" }
                }
                val hint = if (code == 401 || code == 403) " — проверьте API-ключ в настройках" else ""
                return "Ошибка API ($code): ${msg.take(300)}$hint"
            }

            return try {
                val blocks = JSONObject(raw).optJSONArray("content")
                val sb = StringBuilder()
                if (blocks != null) {
                    for (i in 0 until blocks.length()) {
                        val b = blocks.optJSONObject(i) ?: continue
                        if (b.optString("type") == "text") sb.append(b.optString("text"))
                    }
                }
                val out = sb.toString().trim()
                if (out.isEmpty()) "Модель промолчала, сэр" else out
            } catch (e: Exception) {
                "Не смог разобрать ответ модели: ${e.message}"
            }
        } finally {
            conn.disconnect()
        }
    }
}
