package kz.jarvis.app

import android.content.Context

/**
 * Единая точка входа к модели: смотрит, какой провайдер выбрал пользователь,
 * и передаёт запрос в нужный клиент. MainActivity и WakeWordService больше не
 * знают, кто именно отвечает — Gemini, OpenAI, DeepSeek, Claude или локальная
 * Ollama.
 */
object Llm {

    /** Асинхронный запрос к выбранному провайдеру; cb приходит в фоновом потоке. */
    fun chatAsync(
        c: Context,
        history: List<Pair<Boolean, String>>,
        text: String,
        cb: (String) -> Unit
    ) {
        val p = Prefs.provider(c)
        val key = Prefs.apiKey(c, p)
        val model = Prefs.model(c, p)
        val base = Prefs.baseUrl(c, p)
        when (p.api) {
            Providers.Api.GEMINI ->
                GeminiClient.chatAsync(key, model, history, text, base) { cb(it) }

            Providers.Api.OPENAI ->
                OpenAiClient.chatAsync(base, key, model, history, text, cb)

            Providers.Api.ANTHROPIC ->
                AnthropicClient.chatAsync(base, key, model, history, text, cb)
        }
    }

    /**
     * Синхронный запрос со своим системным промптом — для долгих задач вроде
     * «поищи инфу и сделай презентацию». Вызывать ТОЛЬКО из фонового потока.
     *
     * @param prefer провайдер, которого надо использовать вместо выбранного
     *               (например, Claude — «отнеси материал в Клод»)
     */
    fun complete(
        c: Context,
        system: String,
        text: String,
        maxTokens: Int = 2000,
        prefer: Providers.Provider? = null
    ): String {
        val p = prefer ?: Prefs.provider(c)
        val key = Prefs.apiKey(c, p)
        val model = Prefs.model(c, p)
        val base = Prefs.baseUrl(c, p)
        return when (p.api) {
            Providers.Api.GEMINI -> GeminiClient.complete(base, key, model, system, text, maxTokens)
            Providers.Api.OPENAI -> OpenAiClient.complete(base, key, model, system, text, maxTokens)
            Providers.Api.ANTHROPIC -> AnthropicClient.complete(base, key, model, system, text, maxTokens)
        }
    }

    /** Провайдер Claude, если у него есть ключ, — «сходить именно в Клод». */
    fun claude(c: Context): Providers.Provider? {
        val p = Providers.ALL.firstOrNull { it.api == Providers.Api.ANTHROPIC } ?: return null
        return if (Prefs.apiKey(c, p).isNotEmpty()) p else null
    }

    /** Есть ли вообще рабочий провайдер (Claude, текущий или локальный). */
    fun anyReady(c: Context): Boolean = claude(c) != null || ready(c)

    /** Кто будет отвечать на тяжёлый запрос: Claude по ключу или текущий провайдер. */
    fun worker(c: Context): Providers.Provider? = claude(c) ?: if (ready(c)) Prefs.provider(c) else null

    /** Хватает ли настроек, чтобы задать вопрос модели. */
    fun ready(c: Context): Boolean {
        val p = Prefs.provider(c)
        if (p.keyOptional) return true
        return Prefs.apiKey(c, p).isNotEmpty()
    }

    /** Что сказать, когда ключа нет. */
    fun missingKeyText(c: Context): String {
        val p = Prefs.provider(c)
        val where = if (p.keyUrl.isEmpty()) "выберите провайдера и впишите настройки"
        else "вставьте ключ: ${p.keyUrl}"
        return "Сэр, для свободных ответов нужен API-ключ провайдера «${p.name}». " +
            "Откройте Настройки ⚙ → Провайдер ИИ и $where."
    }

    /** Проверка связки «провайдер + адрес + ключ + модель». null — всё хорошо. */
    fun ping(p: Providers.Provider, baseUrl: String, key: String, model: String): String? =
        when (p.api) {
            Providers.Api.GEMINI -> GeminiClient.ping(key, model, baseUrl)
            Providers.Api.OPENAI -> OpenAiClient.ping(baseUrl, key, model)
            Providers.Api.ANTHROPIC -> AnthropicClient.ping(baseUrl, key, model)
        }
}
