package kz.jarvis.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Клиент для провайдеров с форматом OpenAI (`/chat/completions`):
 * OpenAI, OpenRouter, DeepSeek, Groq, Mistral, Together, Ollama, LM Studio,
 * vLLM и любой свой прокси. Ключ не обязателен — локальные серверы работают
 * и без него.
 */
object OpenAiClient {

    private val executor = Executors.newSingleThreadExecutor()

    /** Асинхронный запрос; cb вызывается в фоновом потоке. */
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

    /** Быстрый тест связки «адрес + ключ + модель». null — всё работает. */
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
        val conn = URL(LlmRequests.openAiUrl(baseUrl)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (key.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("x-api-key", key) // часть прокси читает именно его
            }

            val bytes = LlmRequests.openAiBody(model, Persona.SYSTEM_PROMPT, history, text)
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
                val hint = when (code) {
                    401, 403 -> " — проверьте API-ключ в настройках"
                    404 -> " — проверьте адрес сервера и название модели"
                    429 -> " — лимит запросов исчерпан"
                    else -> ""
                }
                return "Ошибка API ($code): ${msg.take(300)}$hint"
            }

            return try {
                val message = JSONObject(raw).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")
                val out = message?.optString("content")?.trim().orEmpty()
                    .ifEmpty { message?.optString("reasoning_content")?.trim().orEmpty() }
                if (out.isEmpty()) "Модель промолчала, сэр" else out
            } catch (e: Exception) {
                "Не смог разобрать ответ модели: ${e.message}"
            }
        } finally {
            conn.disconnect()
        }
    }
}
