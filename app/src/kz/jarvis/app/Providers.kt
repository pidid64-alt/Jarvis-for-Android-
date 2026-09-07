package kz.jarvis.app

/**
 * Каталог ИИ-провайдеров: пользователь сам выбирает, чья модель отвечает
 * за свободные вопросы. Добавлены три варианта протокола:
 *  • [Api.GEMINI]    — родной API Google Gemini;
 *  • [Api.OPENAI]    — формат OpenAI «chat/completions»: OpenAI, OpenRouter,
 *                      DeepSeek, Groq, Mistral, Together, Ollama, LM Studio,
 *                      vLLM и любой свой прокси;
 *  • [Api.ANTHROPIC] — API Anthropic «/v1/messages».
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object Providers {

    enum class Api { GEMINI, OPENAI, ANTHROPIC }

    class Provider(
        val id: String,
        val name: String,
        val api: Api,
        val baseUrl: String,
        val models: List<String>,
        val keyUrl: String,
        /** Ключ не обязателен (локальная модель). */
        val keyOptional: Boolean = false,
        /** Адрес можно (и нужно) менять руками. */
        val urlEditable: Boolean = false,
        val keyExample: String = ""
    ) {
        /** Модель по умолчанию — первая из списка. */
        val defaultModel: String get() = models.firstOrNull().orEmpty()

        /** Подсказка для экрана настроек. */
        fun description(): String {
            val where = if (keyOptional) "ключ не нужен" else "ключ: $keyUrl"
            val modelsHint = if (models.isEmpty()) "модель впишите свою" else "модели: ${models.take(3).joinToString(", ")}"
            return "$where.\n$modelsHint"
        }
    }

    val ALL: List<Provider> = listOf(
        Provider(
            id = "gemini",
            name = "Google Gemini",
            api = Api.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            models = listOf(
                "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite",
                "gemini-3.1-pro-preview", "gemini-2.5-flash", "gemini-2.5-pro"
            ),
            keyUrl = "aistudio.google.com/apikey",
            keyExample = "AIza…"
        ),
        Provider(
            id = "openai",
            name = "OpenAI",
            api = Api.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini"),
            keyUrl = "platform.openai.com/api-keys",
            keyExample = "sk-…"
        ),
        Provider(
            id = "openrouter",
            name = "OpenRouter (сотни моделей)",
            api = Api.OPENAI,
            baseUrl = "https://openrouter.ai/api/v1",
            models = listOf(
                "openai/gpt-4o-mini", "anthropic/claude-3.5-haiku",
                "meta-llama/llama-3.3-70b-instruct", "deepseek/deepseek-chat"
            ),
            keyUrl = "openrouter.ai/keys",
            keyExample = "sk-or-…"
        ),
        Provider(
            id = "deepseek",
            name = "DeepSeek",
            api = Api.OPENAI,
            baseUrl = "https://api.deepseek.com",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            keyUrl = "platform.deepseek.com/api_keys",
            keyExample = "sk-…"
        ),
        Provider(
            id = "groq",
            name = "Groq (очень быстрый)",
            api = Api.OPENAI,
            baseUrl = "https://api.groq.com/openai/v1",
            models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768"),
            keyUrl = "console.groq.com/keys",
            keyExample = "gsk_…"
        ),
        Provider(
            id = "mistral",
            name = "Mistral AI",
            api = Api.OPENAI,
            baseUrl = "https://api.mistral.ai/v1",
            models = listOf("mistral-large-latest", "open-mistral-nemo", "ministral-8b-latest"),
            keyUrl = "console.mistral.ai/api-keys",
            keyExample = "…"
        ),
        Provider(
            id = "together",
            name = "Together AI",
            api = Api.OPENAI,
            baseUrl = "https://api.together.xyz/v1",
            models = listOf(
                "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                "Qwen/Qwen2.5-72B-Instruct-Turbo"
            ),
            keyUrl = "api.together.ai/settings/api-keys",
            keyExample = "…"
        ),
        Provider(
            id = "anthropic",
            name = "Anthropic Claude",
            api = Api.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
            models = listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest"),
            keyUrl = "console.anthropic.com/settings/keys",
            keyExample = "sk-ant-…"
        ),
        Provider(
            id = "ollama",
            name = "Ollama / LM Studio (локально)",
            api = Api.OPENAI,
            baseUrl = "http://127.0.0.1:11434/v1",
            models = listOf("llama3.2", "qwen2.5:7b", "phi3"),
            keyUrl = "",
            keyOptional = true,
            urlEditable = true,
            keyExample = "любой текст или пусто"
        ),
        Provider(
            id = "custom",
            name = "Свой сервер (формат OpenAI)",
            api = Api.OPENAI,
            baseUrl = "https://",
            models = emptyList(),
            keyUrl = "",
            urlEditable = true,
            keyExample = "зависит от вашего сервера"
        )
    )

    /** Провайдер по id; неизвестный id — Gemini (как было до появления выбора). */
    fun byId(id: String?): Provider = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun indexOf(id: String?): Int = ALL.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** Названия для списка выбора. */
    fun names(): List<String> = ALL.map { it.name }
}
