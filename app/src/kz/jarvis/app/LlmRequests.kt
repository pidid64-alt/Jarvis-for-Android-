package kz.jarvis.app

/**
 * Сборка HTTP-запросов к провайдерам. JSON собирается руками (без org.json),
 * поэтому файл не зависит ни от Android, ни от библиотек — его проверяет
 * scripts/test-logic.sh, а используют все три клиента.
 */
object LlmRequests {

    /** Сколько последних реплик диалога отправляем. */
    const val HISTORY_LIMIT = 12

    // ------------------------------------------------------------------ JSON

    /** Строка, готовая к вставке в JSON — вместе с кавычками. */
    fun json(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ------------------------------------------------------------------- URL

    private fun root(base: String): String = base.trim().trimEnd('/')

    /** https://…/v1beta/models/<model>:generateContent */
    fun geminiUrl(base: String, model: String): String =
        "${root(base)}/v1beta/models/${model.trim()}:generateContent"

    /** https://…/v1/chat/completions */
    fun openAiUrl(base: String): String = "${root(base)}/chat/completions"

    /** https://api.anthropic.com/v1/messages */
    fun anthropicUrl(base: String): String = "${root(base)}/messages"

    // ------------------------------------------------------------------ тело

    /** Родной формат Gemini. */
    fun geminiBody(
        system: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        maxTokens: Int = 600
    ): String {
        val sb = StringBuilder()
        sb.append("{\"systemInstruction\":{\"parts\":[{\"text\":").append(json(system)).append("}]}")
        sb.append(",\"contents\":[")
        for ((isUser, t) in history.takeLast(HISTORY_LIMIT)) {
            sb.append("{\"role\":\"").append(if (isUser) "user" else "model")
                .append("\",\"parts\":[{\"text\":").append(json(t)).append("}]}")
                .append(',')
        }
        sb.append("{\"role\":\"user\",\"parts\":[{\"text\":").append(json(text)).append("}]}")
        sb.append("],\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":").append(maxTokens).append("}}")
        return sb.toString()
    }

    /** Формат OpenAI — его понимают OpenRouter, DeepSeek, Groq, Ollama и другие. */
    fun openAiBody(
        model: String,
        system: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        maxTokens: Int = 600
    ): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(json(model)).append(",\"messages\":[")
        sb.append("{\"role\":\"system\",\"content\":").append(json(system)).append("},")
        for ((isUser, t) in history.takeLast(HISTORY_LIMIT)) {
            sb.append("{\"role\":\"").append(if (isUser) "user" else "assistant")
                .append("\",\"content\":").append(json(t)).append("},")
        }
        sb.append("{\"role\":\"user\",\"content\":").append(json(text)).append("}")
        sb.append("],\"temperature\":0.7,\"max_tokens\":").append(maxTokens).append("}")
        return sb.toString()
    }

    /** Формат Anthropic: system — отдельное поле, роли user/assistant. */
    fun anthropicBody(
        model: String,
        system: String,
        history: List<Pair<Boolean, String>>,
        text: String,
        maxTokens: Int = 600
    ): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(json(model))
            .append(",\"max_tokens\":").append(maxTokens)
            .append(",\"temperature\":0.7,\"system\":").append(json(system))
            .append(",\"messages\":[")
        for ((isUser, t) in history.takeLast(HISTORY_LIMIT)) {
            sb.append("{\"role\":\"").append(if (isUser) "user" else "assistant")
                .append("\",\"content\":").append(json(t)).append("},")
        }
        sb.append("{\"role\":\"user\",\"content\":").append(json(text)).append("}]}")
        return sb.toString()
    }
}
