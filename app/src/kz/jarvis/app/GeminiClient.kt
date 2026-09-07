package kz.jarvis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object GeminiClient {

    private val executor = Executors.newSingleThreadExecutor()

    private const val SYSTEM_PROMPT =
        "Ты — Джарвис, голосовой ИИ-ассистент на Android, вдохновлённый Джарвисом из «Железного человека». " +
        "Правила: отвечай по-русски, кратко (обычно 1–3 предложения), по делу, дружелюбно, с лёгкой иронией и " +
        "изредка обращайся к пользователю «сэр». Текущую дату и время ты не знаешь — не выдумывай их. " +
        "Ты также умеешь управлять телефоном офлайн-командами (они выполняются мгновенно, даже когда приложение " +
        "закрыто): «включи фонарик», «громкость 40 процентов», «пауза», «следующий трек», «включи музыку <название>», " +
        "«открой <приложение>», «позвони <имя>», «напиши <имя> <текст>», «будильник на 7:30», «таймер на 5 минут», " +
        "«напомни через 20 минут <дело>», «сколько будет 12 умножить на 7», «20 процентов от 150», «корень из 144», " +
        "«100 фаренгейта в цельсиях», «сколько заряда», «сколько памяти», «который час», «время в Лондоне», " +
        "«какая погода», «маршрут до <места>», «найди <запрос>», «переведи <слово>», «новости», «подбрось монетку», " +
        "«спи 10 минут», «что ты умеешь». " +
        "Если пользователь просит такое действие — коротко подтверди готовность и предложи произнести команду."

    /** Асинхронный запрос. cb вызывается в фоновом потоке — вызывающий сам переводит в UI. */
    fun chatAsync(
        key: String,
        model: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        cb: (String) -> Unit
    ) {
        executor.execute {
            val answer = try {
                chat(key, model, history, text)
            } catch (e: Exception) {
                "Сэр, связь с сервером оборвалась: ${e.message ?: "неизвестная ошибка"}"
            }
            cb(answer)
        }
    }

    /** Быстрый тест ключа. Возвращает null при успехе, иначе текст ошибки. */
    fun ping(key: String, model: String): String? = try {
        val answer = chat(key, model, emptyList(), "Ответь ровно одним словом: работает")
        if (answer.startsWith("Ошибка")) answer else null
    } catch (e: Exception) {
        e.message ?: "ошибка сети"
    }

    private fun chat(
        key: String,
        model: String,
        history: List<Pair<Boolean, String>>,
        text: String
    ): String {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)

            val body = JSONObject()
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            val contents = JSONArray()
            for ((isUser, t) in history.takeLast(12)) {
                contents.put(
                    JSONObject()
                        .put("role", if (isUser) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", t)))
                )
            }
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
            body.put("contents", contents)
            body.put(
                "generationConfig",
                JSONObject().put("temperature", 0.7).put("maxOutputTokens", 600)
            )

            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = try {
                    JSONObject(raw).getJSONObject("error").optString("message")
                } catch (e: Exception) {
                    "HTTP $code"
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
