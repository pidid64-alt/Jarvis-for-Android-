package kz.jarvis.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Джарвис, поищи инфу про X и сделай презентацию».
 *
 * Полный цикл:
 *  1. ищем в интернете (Википедия + DuckDuckGo, без ключей) — [WebSearch];
 *  2. складываем найденное в выжимку с источниками;
 *  3. несём выжимку Клоду: по API (если есть ключ Anthropic) либо прямо в
 *     приложение/сайт Claude, где текст уже вставлен — [ClaudeBridge];
 *  4. разбираем ответ в слайды [Deck], сохраняем HTML и Markdown в
 *     «Загрузки/Jarvis» и открываем презентацию — [DeckFile];
 *  5. вслух коротко докладываем, что получилось.
 *
 * Все методы сетевые — вызывать только из фонового потока
 * (CommandEngine.Outcome.async именно так и делает).
 */
object Research {

    /** Максимум символов в озвучиваемом ответе — дальше TTS утомляет. */
    private const val SPEECH_LIMIT = 850

    fun handle(c: Context, plan: ResearchPlan.Plan): String = when (plan.kind) {
        ResearchPlan.Kind.OPEN_LAST -> DeckFile.openLast(c)
        ResearchPlan.Kind.CLAUDE -> toClaude(c, plan)
        ResearchPlan.Kind.RESEARCH -> research(c, plan)
        ResearchPlan.Kind.PRESENTATION -> presentation(c, plan)
    }

    // --------------------------------------------------------------- сценарии

    /** «Спроси у Клода …», «отправь это в Клод». */
    private fun toClaude(c: Context, plan: ResearchPlan.Plan): String {
        if (plan.topic.isBlank()) {
            return ClaudeBridge.send(c, Conversation.lastReply.ifBlank { "Привет, Клод." })
        }
        val claude = Llm.claude(c)
        if (claude != null && !Prefs.claudeApp(c)) {
            val answer = Llm.complete(c, Persona.SYSTEM_PROMPT, plan.topic, 900, claude)
            return "Клод отвечает: " + answer.take(SPEECH_LIMIT)
        }
        return ClaudeBridge.send(c, plan.topic)
    }

    /** «Поищи инфу про …» — без презентации, просто пересказать. */
    private fun research(c: Context, plan: ResearchPlan.Plan): String {
        if (plan.topic.isBlank()) return "Что именно поискать, сэр?"
        val hits = WebSearch.search(plan.topic, 8, ResearchPlan.searchQueries(plan.topic).drop(1))
        if (hits.isEmpty()) return nothingFound(plan.topic)

        val digest = WebSearch.digest(plan.topic, hits, today())
        val worker = if (plan.viaClaudeApp || Prefs.claudeApp(c)) null else Llm.worker(c)

        if (worker == null) {
            val opened = ClaudeBridge.send(
                c,
                ResearchPlan.claudeAppMessage(plan.topic, digest, plan.slides, presentation = false)
            )
            return WebSearch.offlineSummary(plan.topic, hits).take(SPEECH_LIMIT) + " " + opened
        }

        val answer = Llm.complete(
            c,
            ResearchPlan.digestSystem(),
            ResearchPlan.digestPrompt(plan.topic, digest),
            900,
            worker
        )
        if (failed(answer)) return WebSearch.offlineSummary(plan.topic, hits) + " (" + answer.take(120) + ")"
        Conversation.add("Материал по теме «${plan.topic}»: $answer", false)
        return answer.take(SPEECH_LIMIT)
    }

    /** «…и сделай презентацию» — полный цикл до файла. */
    private fun presentation(c: Context, plan: ResearchPlan.Plan): String {
        if (plan.topic.isBlank()) {
            return "Про что делать презентацию, сэр? Скажите: «поищи инфу про искусственный интеллект " +
                "и сделай презентацию»."
        }
        val hits = WebSearch.search(plan.topic, 9, ResearchPlan.searchQueries(plan.topic).drop(1))
        if (hits.isEmpty()) return nothingFound(plan.topic)

        val digest = WebSearch.digest(plan.topic, hits, today())
        val viaApp = plan.viaClaudeApp || Prefs.claudeApp(c)
        val worker = if (viaApp) null else Llm.worker(c)

        // без ключа (или по желанию) — черновик + материал прямо в приложение Клода
        if (worker == null) {
            val draft = WebSearch.draft(plan.topic, hits, plan.slides)
            val saved = store(c, plan.topic, draft)
            val opened = ClaudeBridge.send(
                c,
                ResearchPlan.claudeAppMessage(plan.topic, digest, plan.slides, presentation = true)
            )
            return "Нашёл ${hits.size} источников по теме «${plan.topic}», сэр. $opened " +
                "Черновик слайдов уже лежит в ${saved.ifEmpty { "памяти телефона" }}."
        }

        val raw = Llm.complete(
            c,
            ResearchPlan.deckSystem(),
            ResearchPlan.deckPrompt(plan.topic, digest, plan.slides),
            3_000,
            worker
        )
        val deck = if (failed(raw)) WebSearch.draft(plan.topic, hits, plan.slides)
        else {
            val parsed = Deck.parse(plan.topic, raw, WebSearch.sources(hits))
            if (parsed.ok) parsed else WebSearch.draft(plan.topic, hits, plan.slides)
        }

        val where = store(c, plan.topic, deck)
        val note = if (failed(raw)) " Модель не ответила (${raw.take(90)}), собрал черновик сам." else ""
        val place = if (where.isEmpty()) " Файл сохранить не вышло — текст в буфере обмена."
        else " Файл: $where."
        if (where.isEmpty()) ClaudeBridge.copy(c, Deck.markdown(deck))

        Conversation.add("Презентация «${deck.title}» на ${deck.slides.size} слайдов готова.", false)
        return (Deck.speech(deck) + note + place).take(SPEECH_LIMIT)
    }

    // ------------------------------------------------------------ вспомогательное

    /** Сохраняет презентацию и сразу её открывает; возвращает «где лежит». */
    private fun store(c: Context, topic: String, deck: Deck.Presentation): String {
        if (!deck.ok) return ""
        val base = Deck.slug(topic) + "-" + SimpleDateFormat("ddMM-HHmm", Locale.getDefault()).format(Date())
        val saved = DeckFile.save(c, base, Deck.html(deck), Deck.markdown(deck))
        if (!saved.ok) return ""
        Notify.deck(c, deck.title, saved)
        DeckFile.open(c, saved)
        return saved.where
    }

    private fun failed(answer: String): Boolean =
        answer.isBlank() || answer.startsWith("Ошибка") || answer.startsWith("Сэр, связь") ||
            answer.startsWith("Не смог разобрать") || answer.startsWith("Модель промолчала")

    private fun nothingFound(topic: String): String =
        "По теме «$topic» интернет ничего не отдал, сэр. Проверьте связь или переформулируйте запрос."

    private fun today(): String =
        SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date())
}
