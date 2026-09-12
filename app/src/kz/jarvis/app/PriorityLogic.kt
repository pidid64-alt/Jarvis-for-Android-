package kz.jarvis.app

/**
 * Приоритетные контакты — чистая логика без Android (покрыта
 * scripts/test-logic.sh, выполняется на обычной JVM).
 *
 * Смысл режима: если пишет кто-то из «приоритетных» (мама, папа, жена…),
 * Джарвис НЕ отвечает за владельца сам. Он показывает сообщение, спрашивает
 * «что ответить?», получает ответ владельца (текстом или голосом), аккуратно
 * оформляет его в живое сообщение, показывает итог и отправляет ТОЛЬКО после
 * явного «да/ок/отправляй».
 *
 * Здесь: список контактов, узнавание чата, стадии диалога и все фразы,
 * категории частых вопросов, шаблоны и обучение на прежних ответах,
 * «стиль общения» с контактом, лёгкая обработка черновика, промпт для модели
 * и логика напоминания о непрочитанном.
 */
object PriorityLogic {

    // ==================================================== список контактов

    fun norm(s: String): String = AutoReplyLogic.norm(s)

    /** Список имён из текста настроек (по одному в строке). */
    fun parseList(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.lines()) {
            val n = line.trim().trim(',', ';', '.')
            if (n.isEmpty()) continue
            if (out.any { norm(it) == norm(n) }) continue
            out.add(n)
        }
        return out
    }

    fun formatList(list: List<String>): String = list.joinToString("\n")

    fun hasName(raw: String, name: String): Boolean =
        parseList(raw).any { norm(it) == norm(name) }

    /** Добавить имя в список (повторно — не дублируем). */
    fun addName(raw: String, name: String): String {
        val n = name.trim().trim(',', ';', '.', '!', '?')
        if (n.isEmpty()) return raw
        if (hasName(raw, n)) return raw
        return (parseList(raw) + n).joinToString("\n")
    }

    /** Убрать имя из списка. Возвращает новый текст и признак «было». */
    fun removeName(raw: String, name: String): Pair<String, Boolean> {
        val list = parseList(raw)
        val kept = list.filter { norm(it) != norm(name) }
        return kept.joinToString("\n") to (kept.size != list.size)
    }

    /**
     * Чат [title] (как он назван в мессенджере) принадлежит контакту из
     * списка? Сравнение мягкое: «Мама» = «мама» = «Мама (моб)».
     */
    fun matchName(title: String, list: List<String>): String? {
        val t = norm(title)
        if (t.isEmpty()) return null
        return list.firstOrNull { c ->
            val n = norm(c)
            when {
                n.isEmpty() -> false
                n == t -> true
                t.startsWith("$n ") || t.endsWith(" $n") -> true
                n.length >= 3 && t.startsWith(n) -> true
                t.length >= 3 && n.startsWith(t) -> true
                n.length >= 4 && t.contains(n) -> true
                else -> false
            }
        }
    }

    fun matches(title: String, list: List<String>): Boolean = matchName(title, list) != null

    // ====================================================== стадии диалога

    enum class Stage { ASK, CONFIRM }

    /** «Мама пишет: «Ты покушал?»». */
    fun incomingLine(title: String, text: String): String =
        "$title пишет: «${text.trim()}»"

    /** «Мама пишет: «Ты покушал?» — Что ответить Маме?» */
    fun askLine(title: String, text: String): String =
        "${incomingLine(title, text)} — Что ответить ${dative(title)}?"

    /** «Отправляю Маме: «Да, мам, уже поел» — ок?» */
    fun confirmLine(title: String, draft: String): String =
        "Отправляю ${dative(title)}: «$draft» — ок?"

    /** «Отправил Маме: «Да, мам, уже поел».» */
    fun sentLine(title: String, draft: String): String =
        "Отправил ${dative(title)}: «$draft»."

    fun cancelledLine(title: String): String =
        "Отменил, сэр — ${dative(title)} ничего не ушло."

    fun skipLine(title: String): String =
        "Хорошо, сэр: ${dative(title)} ничего не пишу. Сообщение осталось в чате."

    fun failedLine(title: String): String =
        "Не смог отправить ${dative(title)}: мессенджер закрыл действие «Ответить». " +
            "Откройте чат и отправьте «$title» вручную, сэр."

    fun remindLine(title: String, text: String, minutes: Long): String =
        "Сэр, вы так и не ответили ${dative(title)}: «${text.trim()}» — " +
            "сообщение ждёт уже ${minutesString(minutes)}. Что ответить?"

    fun minutesString(minutes: Long): String {
        val m = minutes.coerceAtLeast(1)
        return when {
            m % 10 == 1L && m % 100 != 11L -> "$m минуту"
            m % 10 in 2..4 && m % 100 !in 12..14 -> "$m минуты"
            else -> "$m минут"
        }
    }

    /**
     * Лёгкое склонение имени в дательный падеж («Мама» → «Маме»), чтобы
     * фразы звучали по-русски. Правила покрывают самые частые имена и
     * обращения; если имени в правилах нет — оставляем как есть (лучше
     * «Отправляю Саша», чем выдуманный падеж).
     */
    fun dative(name: String): String {
        val raw = name.trim()
        if (raw.isEmpty()) return name
        // склоняем последнее слово: «Мама Лена» → «Мама Лене»
        val head = raw.substringBeforeLast(' ', "")
        val last = raw.substringAfterLast(' ')
        val d = dativeWord(last)
        return (if (head.isEmpty()) "" else "$head ") + d
    }

    private val DATIVE_EXCEPT = mapOf(
        "мама" to "маме", "папа" to "папе", "мамуля" to "мамуле", "папуля" to "папуле",
        "бабушка" to "бабушке", "бабуля" to "бабуле", "дедушка" to "дедушке", "дедуля" to "дедуле",
        "жена" to "жене", "муж" to "мужу", "дочь" to "дочери", "сын" to "сыну",
        "мать" to "матери", "отец" to "отцу", "сестра" to "сестре", "брат" to "брату",
        "тётя" to "тёте", "тетя" to "тете", "дядя" to "дяде", "бабуся" to "бабусе",
        "любовь" to "любови", "мачеха" to "мачехе", "отчим" to "отчиму"
    )

    private fun dativeWord(word: String): String {
        val w = word.trim()
        if (w.length < 2) return w
        val low = w.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        val result = when {
            DATIVE_EXCEPT.containsKey(low) -> DATIVE_EXCEPT.getValue(low)
            low.endsWith("ия") -> low.dropLast(1) + "и"          // Мария → Марии
            low.endsWith("ья") -> low.dropLast(1) + "е"           // Наталья → Наталье
            low.endsWith("я") -> low.dropLast(1) + "е"            // Настя → Насте
            low.endsWith("а") -> low.dropLast(1) + "е"            // Мама → Маме
            low.endsWith("й") -> low.dropLast(1) + "ю"            // Андрей → Андрею
            low.endsWith("ь") -> low.dropLast(1) + "ю"            // Игорь → Игорю
            // фамилии на -ко/-ых/-их не склоняем: Петренко, Ковальчук, Седых
            low.endsWith("ко") || low.endsWith("ых") || low.endsWith("их") -> low
            low.endsWith("о") -> low.dropLast(1) + "у"
            low.endsWith("е") || low.endsWith("э") || low.endsWith("и") ||
                low.endsWith("ы") || low.endsWith("у") || low.endsWith("ю") -> low
            else -> low + "у"                       // твёрдый согласный: Александр → Александру
        }
        return if (w.first().isUpperCase()) {
            result.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
        } else result
    }

    // ========================================================== да / нет

    private val YES_WORDS = listOf(
        "да", "ага", "угу", "гуд", "ок", "окей", "оk", "yes", "йес",
        "хорошо", "го", "гоу", "давай", "давайте", "валяй", "жги", "вперёд", "поехали",
        "конечно", "точно", "именно", "верно", "правильно", "отлично", "супер", "класс",
        "согласен", "согласна", "подтверждаю", "подтверди", "одобряю", "разрешаю",
        "отправляй", "отправь", "отсылай", "отошли", "выполняй", "сделай", "пусть",
        "пусть так", "так и отправь", "отправляй так", "отправляй это", "отправляй его",
        "отправляй ее", "отправляй её", "давай отправляй", "давай отправь", "отправь давай",
        "ок давай", "да ок", "да давай", "годится", "сойдет", "сойдёт", "старт", "отправка",
        "подтверждаю отправку", "все верно", "всё верно", "неси", "давай же"
    )

    private val YES_PREFIX = listOf(
        "да ", "ок ", "ага ", "хорошо ", "конечно ", "валяй ", "отправляй ", "отправь ",
        "давай ", "подтверждаю", "согласен", "согласна", "точно", "именно", "пусть"
    )

    private val NO_WORDS = listOf(
        "нет", "не", "неа", "ноу", "no", "nein",
        "не надо", "не нужно", "не стоит", "не отправляй", "не отправь", "не отсылай",
        "отмена", "отмени", "отменяй", "отбой", "отставить", "отменяется",
        "стоп", "хватит", "достаточно", "забудь", "забей", "передумал", "передумала",
        "погоди", "подожди", "потом", "позже", "не сейчас", "посмотрим",
        "не то", "не так", "иначе", "заново", "по другому", "по-другому",
        "никак", "ни за что", "нет спасибо", "нет уж", "откажись", "откажи"
    )

    /** Ещё раз показать входящее (вопрос не расслышали). */
    private val REPEAT_WORDS = listOf(
        "что там", "что написал", "что написала", "прочитай", "прочти", "повтори",
        "еще раз", "ещё раз", "какое сообщение", "что за сообщение", "не расслышал",
        "не расслышала", "что пришло", "покажи сообщение",
        "что ответить", "что отвечать", "как ответить", "что мне ответить", "а спросил"
    )

    /** «Не отвечай вообще» — на стадии вопроса. */
    private val SKIP_WORDS = listOf(
        "не отвечай", "не пиши", "ничего не отвечай", "ничего не пиши", "пропусти",
        "пропусти это", "игнорируй", "забей", "не надо отвечать", "не надо писать",
        "сам отвечу", "я сам", "сам напишу", "потом", "позже", "отбой", "забудь",
        "молчи", "ничего", "стоп", "отмена", "отмени", "хватит", "не сейчас"
    )

    private fun clean(s: String): String = norm(s).trim('.', '!', '?', ',', ' ', '»', '«')

    /** Явное подтверждение отправки: «да», «ок», «отправляй», «подтверждаю». */
    fun isYes(s: String): Boolean {
        val t = clean(s)
        if (t.isEmpty()) return false
        if (t in YES_WORDS) return true
        // «да, отправляй», «ок давай» — короткая фраза, начинающаяся с согласия
        if (t.split(' ').size <= 3 && YES_PREFIX.any { t.startsWith(it) }) {
            // но не «да, только допиши …» — там явное указание, это правка
            val tail = t.substringAfter(' ', "").trim()
            if (tail.isEmpty() || tail in YES_WORDS || EDIT_MARKERS.none { t.contains(it) }) return true
        }
        return false
    }

    /** Отказ / отмена: «нет», «не надо», «отмена», «передумал». */
    fun isNo(s: String): Boolean {
        val t = clean(s)
        return t.isNotEmpty() && (t in NO_WORDS || NO_WORDS.any { w -> w.length >= 4 && t == w })
    }

    private val EDIT_MARKERS = listOf(
        "только", "но ", "лучше", "вместо", "добавь", "допиши", "убери", "короче",
        "подлиннее", "измени", "поменяй", "переделай", "напиши", "скажи", "ответь"
    )

    fun isRepeat(s: String): Boolean {
        val t = clean(s)
        return t.isNotEmpty() && REPEAT_WORDS.any { w -> t.contains(w) }
    }

    fun isSkip(s: String): Boolean {
        val t = clean(s)
        return t.isNotEmpty() && SKIP_WORDS.any { w -> t == w || t.startsWith("$w ") }
    }

    private val LEADING_YES = Regex(
        """^(да|ок|окей|ага|угу|хорошо|конечно|точно|валяй|давай|подтверждаю|отправляй|отправь)\b[\s,.:;!\-—]*(?=\S)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * «да, только добавь что задержусь» → «только добавь что задержусь»:
     * согласие в начале убираем, только если владелец реально что-то уточняет
     * («давай встретимся в семь» — это уже готовый текст, его не трогаем).
     */
    fun stripLeadingYes(s: String): String {
        val t = s.trim()
        val m = LEADING_YES.find(t) ?: return t
        val rest = t.substring(m.value.length).trim()
        if (rest.isEmpty()) return t
        val hasEdit = EDIT_MARKERS.any { norm(rest).contains(it) }
        return if (hasEdit) rest else t
    }

    /** Фраза — команда самому Джарвису, а не текст для собеседника. */
    private val SERVICE = Regex(
        """^(напомни|поставь таймер|будильник|открой|включи|выключи|поищи|найди|переведи|""" +
            """курс|какая погода|погода|сколько|который час|время|что ты умеешь|команды|""" +
            """сделай презентацию|позвони|набери номер|джарвис|спи)\b"""
    )

    fun isServicePhrase(s: String): Boolean = SERVICE.containsMatchIn(norm(s))

    /** Короткое «нет» без пояснений — на вопрос «что ответить?» это отказ. */
    private val LONE_REFUSAL = setOf("нет", "не надо", "не нужно", "не хочу", "никак")

    /** Что владелец хочет сказать своей репликой в диалоге про сообщение. */
    enum class ReplyIntent {
        /** Это текст ответа собеседнику (или правка черновика). */
        ANSWER,

        /** «да/ок/отправляй» — подтверждение отправки. */
        CONFIRM,

        /** «нет/отмена» — ничего не отправлять. */
        CANCEL,

        /** «не отвечай/пропусти» — закрыть запрос без ответа. */
        SKIP,

        /** «что там?» — повторить входящее или черновик. */
        REPEAT,

        /** Это команда самому Джарвису, а не сообщение. */
        SERVICE
    }

    /**
     * Разбор реплики владельца в диалоге «Мама пишет… — что ответить?».
     *
     * @param stage          на какой стадии сейчас запрос
     * @param hasSuggestion  Джарвис уже предложил готовый черновик (шаблон),
     *                       поэтому «да» означает «отправляй предложенное»
     */
    fun intentOf(stage: Stage, s: String, hasSuggestion: Boolean): ReplyIntent {
        val t = norm(s)
        if (t.isEmpty()) return ReplyIntent.ANSWER
        val yes = isYes(s)
        val no = isNo(s)
        val skip = isSkip(s)
        return when (stage) {
            Stage.ASK -> when {
                hasSuggestion && yes -> ReplyIntent.CONFIRM
                hasSuggestion && (no || skip) -> ReplyIntent.CANCEL
                skip -> ReplyIntent.SKIP
                // «нет» на вопрос «что ответить?» — это «ничего не пиши»
                !hasSuggestion && t in LONE_REFUSAL -> ReplyIntent.CANCEL
                isRepeat(s) -> ReplyIntent.REPEAT
                isServicePhrase(s) && !yes && !no -> ReplyIntent.SERVICE
                else -> ReplyIntent.ANSWER
            }
            Stage.CONFIRM -> when {
                yes -> ReplyIntent.CONFIRM
                no || skip -> ReplyIntent.CANCEL
                isRepeat(s) -> ReplyIntent.REPEAT
                isServicePhrase(s) -> ReplyIntent.SERVICE
                else -> ReplyIntent.ANSWER      // «нет, напиши иначе» — новая формулировка
            }
        }
    }

    /**
     * Проверка, что модель не уехала от смысла: не представилась, не раздула
     * ответ и не добавила чисел, которых не было в реплике владельца.
     */
    fun keepsMeaning(reply: String, answer: String): Boolean {
        val r = norm(reply)
        if (r.isBlank()) return false
        if (Regex("джарвис|jarvis|искусствен|я ии|бот|ассистент|нейросет|языковая модель")
                .containsMatchIn(r)
        ) return false
        if (reply.length > 300) return false
        val rw = reply.split(Regex("\\s+")).size
        val aw = answer.split(Regex("\\s+")).size
        if (rw > aw * 3 + 8) return false
        val digits = Regex("\\d+")
        val extra = digits.findAll(reply).map { it.value }.toList() -
            digits.findAll(answer).map { it.value }.toList()
        return extra.isEmpty()
    }

    // ======================================================== категории

    enum class Category(val id: String, val label: String, val synonyms: List<String>) {
        FOOD("еда", "еда / «ты покушал?»", listOf("поел", "покушал", "кушать", "ужин", "обед", "есть", "еды")),
        WHERE("где", "«где ты?»", listOf("локация", "местоположение", "адрес", "где")),
        WHEN("когда", "«когда будешь?»", listOf("время", "скоро", "придёшь", "приедешь", "задерживаешься")),
        PLANS("планы", "планы на день", listOf("вечер", "выходные", "собираешься", "планах")),
        MOOD("дела", "«как дела?»", listOf("настроение", "жизнь", "делаешь", "делах")),
        BUSY("занят", "«ты занят?»", listOf("занятость", "свободен", "время есть", "помощь")),
        CALL("перезвон", "«перезвони»", listOf("звонок", "позвони", "трубку", "набери", "перезвони")),
        MONEY("деньги", "деньги", listOf("перевод", "кинь", "зарплата", "долг", "займ", "денег", "деньгах")),
        HEALTH("здоровье", "здоровье", listOf("болезнь", "болеешь", "температура", "самочувствие", "здоровья")),
        THANKS("спасибо", "спасибо / поздравление", listOf("благодарность", "праздник", "поздравление", "спасиб")),
        OTHER("другое", "всё остальное", listOf("прочее", "разное"));

        companion object {
            fun byId(id: String): Category? = values().firstOrNull { it.id == id }

            /** Узнаём категорию по слову пользователя: «еда», «еды», «поел»… */
            fun from(text: String): Category? {
                val t = norm(text).trim()
                if (t.isEmpty()) return null
                values().forEach { c ->
                    val all = listOf(c.id, c.label.substringBefore(" ")) + c.synonyms
                    if (all.any { w -> norm(w) == t }) return c
                }
                values().forEach { c ->
                    val all = listOf(c.id) + c.synonyms
                    if (all.any { w -> val nw = norm(w); nw.length >= 3 && (t.startsWith(nw.take(3)) || nw.startsWith(t.take(3))) }) return c
                }
                return null
            }

            fun allIds(): List<String> = values().map { it.id }
        }
    }

    /** К какой «частной теме» относится входящее сообщение. */
    fun categorize(text: String): Category {
        val t = norm(text)
        fun has(vararg parts: String) = parts.any { t.contains(it) }
        return when {
            has("спасибо", "благодарю", "поздравляю", "с днем рождения", "с днём рождения",
                "с праздником", "с новым годом", "молодец") -> Category.THANKS
            has("перезвони", "позвони мне", "набери меня", "звякни", "возьми трубку",
                "трубку возьми", "сбрось") -> Category.CALL
            has("деньги", "кинь на карту", "переведи", "зарплат", "займ", "долг",
                "сколько денег", "оплати", "скинь денег") -> Category.MONEY
            has("покушал", "поел", "пообедал", "позавтракал", "поужинал", "кушал",
                "есть хочешь", "голодн", "поесть", "еда") -> Category.FOOD
            has("здоровье", "болеешь", "заболел", "температур", "болит", "самочувствие",
                "прививк", "больничн") -> Category.HEALTH
            has("где ты", "где вы", "ты где", "вы где", "ты куда", "куда ты", "далеко ли",
                "ты дома", "ты на работе", "дошел", "дошёл", "доехал", "локаци",
                "какой адрес") -> Category.WHERE
            has("когда", "во сколько", "скоро будешь", "сколько ждать", "придешь", "придёшь",
                "приедешь", "приедете", "задерживаешься", "ждешь", "ждёшь") -> Category.WHEN
            has("как дела", "как ты", "как вы", "как жизнь", "как настроение", "что делаешь",
                "чем занимаешься", "как поживаешь") -> Category.MOOD
            has("планы", "вечером", "на выходные", "собираешься", "будешь делать",
                "чем займешься", "чем займёшься") -> Category.PLANS
            has("занят", "есть время", "ты свободен", "свободна", "можешь помочь",
                "поможешь", "нужна помощь", "отвлеку") -> Category.BUSY
            else -> Category.OTHER
        }
    }

    // ========================================================= шаблоны

    /** Строки вида «еда = Да, мам, уже поел» → карта «категория → ответ». */
    fun parseTemplates(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in raw.lines()) {
            val l = line.trim()
            if (l.isBlank() || l.startsWith("#")) continue
            val m = Regex("""^([^=:]+?)\s*[=:]\s*(.+)$""").find(l) ?: continue
            val cat = Category.from(m.groupValues[1].trim()) ?: continue
            val body = m.groupValues[2].trim()
            if (body.isNotBlank()) out[cat.id] = body
        }
        return out
    }

    fun formatTemplates(map: Map<String, String>): String {
        val known = Category.values().mapNotNull { c -> map[c.id]?.let { c.id to it } }
        val rest = map.filter { (k, _) -> Category.byId(k) == null }.toList()
        return (known + rest).joinToString("\n") { (k, v) -> "$k = $v" }
    }

    fun templateFor(raw: String, c: Category): String? = parseTemplates(raw)[c.id]

    fun setTemplate(raw: String, categoryWord: String, text: String): Pair<String, Boolean> {
        val cat = Category.from(categoryWord) ?: return raw to false
        val body = text.trim()
        val map = parseTemplates(raw).toMutableMap()
        if (body.isEmpty()) map.remove(cat.id) else map[cat.id] = body
        return formatTemplates(map) to true
    }

    fun templateHint(): String = Category.values().joinToString(", ") { it.id }

    // ============================================ обучение на прежних ответах

    // Формат строки: «еда|3|Да, мам, уже поел» (категория|сколько раз|текст)
    fun parseLearned(raw: String): MutableMap<String, MutableList<Pair<Int, String>>> {
        val out = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        for (line in raw.lines()) {
            val l = line.trim()
            if (l.isBlank()) continue
            val parts = l.split('|', limit = 3)
            if (parts.size < 3) continue
            val cat = Category.byId(parts[0].trim()) ?: continue
            val count = parts[1].trim().toIntOrNull() ?: 1
            val text = parts[2].trim()
            if (text.isEmpty()) continue
            out.getOrPut(cat.id) { ArrayList() }.add(count to text)
        }
        return out
    }

    private fun formatLearned(m: Map<String, List<Pair<Int, String>>>): String {
        val sb = StringBuilder()
        for (c in Category.values()) {
            val list = m[c.id] ?: continue
            for ((count, text) in list) sb.append("${c.id}|$count|$text\n")
        }
        return sb.toString().trim('\n')
    }

    /** Запоминаем, как владелец ответил в этой категории. */
    fun learn(raw: String, c: Category, answer: String, keep: Int = 3): String {
        val a = answer.replace('|', ' ').trim()
        if (a.isBlank()) return raw
        val map: MutableMap<String, MutableList<Pair<Int, String>>> = parseLearned(raw)
        val list = map.getOrPut(c.id) { ArrayList() }
        val i = list.indexOfFirst { norm(it.second) == norm(a) }
        if (i >= 0) list[i] = (list[i].first + 1) to list[i].second
        else list.add(1 to a)
        map[c.id] = list.sortedByDescending { it.first }.take(keep).toMutableList()
        return formatLearned(map)
    }

    /** Самый частый прежний ответ в этой категории (null — ещё не учились). */
    fun bestLearned(raw: String, c: Category): String? =
        parseLearned(raw)[c.id]?.maxByOrNull { it.first }?.second

    /** Сколько раз владелец уже отвечал на такие сообщения. */
    fun learnedHits(raw: String, c: Category): Int =
        parseLearned(raw)[c.id]?.sumOf { it.first } ?: 0

    // ======================================================== стиль общения

    data class Style(
        val emoji: Boolean = false,
        val formal: Boolean = false,
        val noCaps: Boolean = false,
        val endPunct: Boolean = false,
        val avgWords: Int = 0
    ) {
        /** Короткое описание стиля для модели. */
        fun hint(): String = buildString {
            if (avgWords in 1..4) append("Пиши очень коротко, 1–5 слов. ")
            else if (avgWords >= 12) append("Можно развёрнуто, 2–3 предложения. ")
            else append("Пиши коротко, 1–2 фразы. ")
            if (emoji) append("Можно добавить 1 эмодзи в конце. ")
            else append("Без эмодзи. ")
            if (formal) append("Тон вежливый, нейтральный. ")
            else append("Тон свойский, разговорный. ")
            if (noCaps) append("Первое слово с маленькой буквы, как в чате. ")
            if (endPunct) append("Ставь точку или вопросительный знак в конце. ")
            else append("В конце знак препинания не обязателен. ")
        }.trim()
    }

    private val EMOJI = Regex("[\uD83C-\uDBFF][\uDC00-\uDFFF]|[\u2600-\u27BF]|\u2764|\u2714|\u2705")

    fun styleFrom(samples: List<String>): Style {
        val list = samples.map { it.trim() }.filter { it.isNotBlank() }
        if (list.isEmpty()) return Style()
        val emoji = list.count { EMOJI.containsMatchIn(it) } * 2 >= list.size
        val formal = list.count {
            val t = norm(it)
            t.contains("здравствуйте") || t.contains("добрый день") || t.contains("добрый вечер") ||
                t.contains(" благодарю") || Regex("""\bвы\b""").containsMatchIn(t) ||
                t.contains("пожалуйста,") && t.split(' ').size > 6
        } * 2 >= list.size
        val noCaps = list.count { it.first().isLowerCase() } * 2 > list.size
        val endPunct = list.count { it.last() in ".!?…" || EMOJI.containsMatchIn(it.takeLast(2)) } * 2 >= list.size
        val avg = list.sumOf { it.split(Regex("\\s+")).size } / list.size
        return Style(emoji, formal, noCaps, endPunct, avg)
    }

    /** Строки вида «Мама|да, мам, поел» — по одному прежнему ответу в строке. */
    fun parseStyles(raw: String): MutableMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (line in raw.lines()) {
            val l = line.trim()
            if (l.isBlank()) continue
            val i = l.indexOf('|')
            if (i <= 0) continue
            val who = l.substring(0, i).trim()
            val sample = l.substring(i + 1).trim()
            if (who.isEmpty() || sample.isEmpty()) continue
            out.getOrPut(who) { ArrayList() }.add(sample)
        }
        return out
    }

    fun addStyle(raw: String, title: String, sample: String, keep: Int = 8): String {
        val s = sample.replace('|', ' ').trim()
        if (s.isBlank() || title.isBlank()) return raw
        val map = parseStyles(raw)
        val list = map.getOrPut(title.trim()) { ArrayList() }
        list.add(s)
        map[title.trim()] = list.takeLast(keep).toMutableList()
        val sb = StringBuilder()
        for ((who, samples) in map) for (smp in samples) sb.append("$who|$smp\n")
        return sb.toString().trim('\n')
    }

    fun styleOf(raw: String, title: String): Style {
        val map = parseStyles(raw)
        val key = map.keys.firstOrNull { norm(it) == norm(title) } ?: return Style()
        return styleFrom(map[key].orEmpty())
    }

    // ================================================ обработка черновика

    private val INSTRUCTION_PREFIX = Regex(
        """^(?:напиши|напиши-ка|ответь|отвечай|скажи|передай|сообщи|отправь|отпиши|набери|молви)\b""" +
            """[\s,:;!.-]*""" +
            """(?:(?:ей|ему|им|мне|маме|папе|мам|пап|ей что|ему что|что)[\s,:;.-]*)?""" +
            """(?:(?:что|мол|дескать|типа|как-нибудь)[\s,:;.-]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val TYPOS = listOf(
        "спсбо" to "спасибо", "спасибки" to "спасибо", "спсб" to "спасибо", "спс" to "спасибо",
        "пасиб" to "спасибо", "пжлст" to "пожалуйста", "пжл" to "пожалуйста", "пажалуйста" to "пожалуйста",
        "щяс" to "сейчас", "щас" to "сейчас", "седня" to "сегодня", "сеодня" to "сегодня",
        "скоко" to "сколько", "када" to "когда", "кагда" to "когда", "ваще" to "вообще",
        "здрасте" to "здравствуйте", "здрасьте" to "здравствуйте", "прив" to "привет",
        "норм" to "нормально", "нормал" to "нормально", "обязательно " to "обязательно ",
        "договорились " to "договорились ", "хороше" to "хорошо", "лады" to "ладно"
    )

    private val SHORT_EXPAND = mapOf(
        "да" to "Да", "ага" to "Да", "угу" to "Да", "ок" to "Хорошо", "окей" to "Хорошо",
        "не" to "Нет", "неа" to "Нет", "нет" to "Нет", "ладно" to "Ладно", "хорошо" to "Хорошо",
        "спасибо" to "Спасибо", "конечно" to "Конечно", "привет" to "Привет", "пока" to "Пока"
    )

    private val SHORT_FORMAL = mapOf(
        "да" to "Да, конечно.", "ок" to "Хорошо, договорились.", "окей" to "Хорошо, договорились.",
        "ага" to "Да, конечно.", "угу" to "Да, конечно.", "нет" to "Нет, извините.",
        "не" to "Нет, извините.", "неа" to "Нет, извините.", "хорошо" to "Хорошо, договорились."
    )

    /**
     * Лёгкая обработка ответа владельца: убрать «напиши ей, что…», починить
     * очевидные опечатки, привести к обычному виду чата. Смысл не меняем:
     * ни одного нового факта, только форма.
     */
    fun polish(text: String, style: Style = Style()): String {
        var s = text.trim().trim('«', '»', '"', '\'', ' ')
        if (s.isEmpty()) return ""
        // «напиши ей, что я скоро буду» → «я скоро буду»
        val stripped = INSTRUCTION_PREFIX.replaceFirst(s, "").trim()
        if (stripped.length >= 2) s = stripped
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("\\s+([,.!?:;])"), "$1")
        s = s.replace(Regex("([.!?])\\1+"), "$1")
        s = s.replace(Regex("([,;:])\\1+"), "$1")

        // короткий ответ без знаков — превращаем в осмысленную реплику
        // («привет!!!» здесь не expanding: знаки уже сказали о настроении)
        val key = norm(s).trim('.', '!', '?', ' ', ',')
        if (key in SHORT_EXPAND && !s.contains(Regex("[.!?]")) && s.count { it == ' ' } == 0) {
            s = if (style.formal) (SHORT_FORMAL[key] ?: SHORT_EXPAND.getValue(key))
            else SHORT_EXPAND.getValue(key)
        } else {
            // опечатки — только целыми словами и только из короткого списка
            val words = s.split(' ')
            s = words.joinToString(" ") { w ->
                val k = norm(w)
                TYPOS.firstOrNull { it.first == k }?.second?.let { fix ->
                    if (w.first().isUpperCase()) fix.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) } else fix
                } ?: w
            }
        }

        if (s.isEmpty()) return ""
        s = when {
            style.noCaps -> s.replaceFirstChar { it.lowercase(java.util.Locale.ROOT) }
            else -> s.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
        }
        if (s.last() !in ".!?…" && !EMOJI.containsMatchIn(s.takeLast(2))) {
            if (style.endPunct) s += "."
        }
        return s.trim()
    }

    // ================================================= промпт для модели

    fun draftSystem(): String =
        "Ты — личный ассистент владельца телефона. По его короткой реплике ты " +
            "оформляешь ответ в мессенджере ОТ ИМЕНИ владельца. Не добавляй новых фактов, " +
            "не меняй смысл сказанного, не подписывайся, не представляйся, " +
            "не пиши, что ты ИИ или ассистент. Отвечай одним JSON без пояснений."

    /**
     * Задание модели: оформить ответ владельца в живое сообщение.
     *
     * @param answer     что владелец сказал Джарвису («скажи что скоро буду»)
     * @param styleHint  как этот человек обычно пишет (см. [Style.hint])
     * @param rules      личные правила владельца из настроек автоответчика
     */
    fun draftPrompt(
        title: String,
        incoming: String,
        answer: String,
        styleHint: String,
        rules: String,
        nowText: String
    ): String = buildString {
        append("Тебе пишут: «").append(title).append("». Входящее сообщение: «").append(incoming.trim()).append("».\n")
        append("Владелец телефона просит передать такой смысл: «").append(answer.trim()).append("».\n")
        append("Напиши ответ владельца собеседнику «").append(title).append("» по-русски, ")
        append("от первого лица, как живой человек в чате.\n")
        append("Правила:\n")
        append("- сохрани смысл ровно таким, каким его передал владелец: не выдумывай деталей, дат, обещаний;\n")
        append("- можно исправить описки, сделать фразу естественнее и вежливее, но не менять содержание;\n")
        append("- ").append(styleHint.ifBlank { "Пиши коротко, 1–2 фразы, без эмодзи." }).append("\n")
        append("- без канцелярита, списков, разметки, кавычек вокруг всего сообщения и подписей;\n")
        if (rules.isNotBlank()) append("- личные правила владельца: ").append(rules).append("\n")
        append("Сейчас: ").append(nowText).append(".\n")
        append("Ответь СТРОГО одним JSON: {\"reply\": \"текст сообщения\"}.")
    }

    // ============================== режим «автоответ по шаблону» и напоминания

    /** Решение: есть готовый черновик и нужно ли подтверждение. */
    data class AutoPlan(val draft: String?, val needConfirm: Boolean) {
        val hasDraft: Boolean get() = !draft.isNullOrBlank()
    }

    /**
     * В режиме шаблонов Джарвис подставляет типовой ответ, но ПЕРВУЮ отправку
     * в каждой категории всё равно показывает владельцу и ждёт «да»
     * (список подтверждённых категорий — в [approvedRaw]).
     */
    fun autoPlan(
        incoming: String,
        templatesRaw: String,
        learnedRaw: String,
        approvedRaw: String,
        enabled: Boolean
    ): AutoPlan {
        if (!enabled) return AutoPlan(null, true)
        val c = categorize(incoming)
        val draft = templateFor(templatesRaw, c) ?: bestLearned(learnedRaw, c)?.takeIf {
            learnedHits(learnedRaw, c) >= 2
        }
        if (draft.isNullOrBlank()) return AutoPlan(null, true)
        return AutoPlan(draft, needConfirm = !isApproved(approvedRaw, c))
    }

    fun parseApproved(raw: String): List<String> =
        raw.split(',', ';', '\n', ' ').map { it.trim() }.filter { it.isNotBlank() }

    fun isApproved(raw: String, c: Category): Boolean = parseApproved(raw).any { it == c.id }

    fun approve(raw: String, c: Category): String =
        (parseApproved(raw) + c.id).distinct().joinToString(",")

    fun resetApproved(): String = ""

    /** Пора ли мягко напомнить о неотвеченном сообщении. */
    fun shouldRemind(
        askedAt: Long,
        now: Long,
        delayMs: Long,
        reminded: Boolean,
        enabled: Boolean
    ): Boolean = enabled && !reminded && delayMs > 0 && now - askedAt >= delayMs

    /** Напоминать не чаще, чем раз в полчаса. */
    fun nextRemindDelayMs(minutes: Long): Long = (minutes.coerceIn(1, 240)) * 60_000L

    // ================================================ голосовые команды

    enum class AdminCmd {
        ADD, REMOVE, SHOW, ON, OFF, STATUS, TEMPLATE_SET, TEMPLATE_SHOW, TEMPLATE_CLEAR,
        APPROVE_RESET, HELP
    }

    private val ABOUT = Regex("приоритетн|важн[\\p{L}]*\\s+контакт|особые\\s+контакт|важных\\s+люд|" +
        "важных\\s+человек|близких\\s+контакт")

    fun isAboutPriority(s: String): Boolean = ABOUT.containsMatchIn(norm(s))

    /**
     * Фраза про шаблоны ОТВЕТОВ (не про любые шаблоны вообще, иначе Джарвис
     * перехватывал бы и «сделай шаблон презентации»).
     */
    fun isAboutTemplate(s: String): Boolean {
        val t = norm(s)
        if (!Regex("шаблон").containsMatchIn(t)) return false
        return isAboutPriority(t) ||
            Regex("ответ|отвеча|приоритетн").containsMatchIn(t) ||
            Regex("шаблон\\s*[:=]").containsMatchIn(s.lowercase(java.util.Locale.ROOT))
    }

    fun adminCommand(s: String): AdminCmd? {
        val t = norm(s)
        if (isAboutTemplate(s)) {
            return when {
                Regex("очисти|удали все|удали все шаблоны|сбрось|стереть|забудь").containsMatchIn(t) ->
                    AdminCmd.TEMPLATE_CLEAR
                Regex("""[=:]""").containsMatchIn(s) -> AdminCmd.TEMPLATE_SET
                Regex("покажи|какие|что за|список|прочитай").containsMatchIn(t) -> AdminCmd.TEMPLATE_SHOW
                Regex("добавь|запомни|запиши|поставь|пусть").containsMatchIn(t) -> AdminCmd.TEMPLATE_SET
                else -> AdminCmd.TEMPLATE_SHOW
            }
        }
        if (!isAboutPriority(t)) return null
        return when {
            Regex("добав|запомни|запиши|внес|включи в список|положи").containsMatchIn(t) -> AdminCmd.ADD
            Regex("удал|убери|исключи|выкинь|вычеркни").containsMatchIn(t) -> AdminCmd.REMOVE
            Regex("спрашивай заново|сбрось подтверждени|заново подтвер|обнули подтверждени")
                .containsMatchIn(t) -> AdminCmd.APPROVE_RESET
            Regex("статус|работает|включен|выключен|состояние|как сейчас").containsMatchIn(t) -> AdminCmd.STATUS
            Regex("покажи|какие|кто|список|перечисли|напомни").containsMatchIn(t) -> AdminCmd.SHOW
            Regex("включ|вруб|актив").containsMatchIn(t) -> AdminCmd.ON
            Regex("выключ|отключ|выруб|убер").containsMatchIn(t) -> AdminCmd.OFF
            else -> AdminCmd.HELP
        }
    }

    /** Имя контакта из «добавь приоритетный контакт Мама». */
    fun contactFromCommand(s: String): String? {
        var name = s.trim()
        // имя идёт после последнего ключевого слова: «… приоритетный контакт Мама»
        val key = Regex("""(?:приоритетн[\p{L}]*|контакт[\p{L}]*|список[\p{L}]*|важн[\p{L}]*)""")
        val m = key.findAll(name).lastOrNull() ?: return null
        name = name.substring(m.range.last + 1)
        name = Regex("""^[\s:;,\-—.]+""").replace(name, "")
        name = Regex("""^(?:с\s+именем|по\s+имени|с\s+названием)\s+""", RegexOption.IGNORE_CASE)
            .replace(name, "")
        name = Regex(
            """\s*(?:из\s+приоритетн[\p{L}]*|в\s+приоритетн[\p{L}]*|из\s+списка|в\s+список|уже|тоже)\s*$""",
            RegexOption.IGNORE_CASE
        ).replace(name, "")
        name = name.trim().trim('.', ',', '!', '?', ' ', ':', ';', '-', '—')
        val ban = setOf(
            "", "контакт", "контакты", "контактов", "контактах", "список", "списка", "списке",
            "приоритет", "приоритетные", "приоритетных", "приоритетным", "приоритеты",
            "важных", "важные", "важный"
        )
        return if (name.isEmpty() || norm(name) in ban) null else name
    }

    /** «шаблон: еда = Да, мам, поел» → («еда», «Да, мам, поел»). */
    fun templateFromCommand(s: String): Pair<String, String>? {
        val t = s.trim()
        val after = Regex("""шаблон[\p{L}]*\s*(?:для\s+([\p{L}]+))?\s*[:;\-—]?\s*(.+)$""", RegexOption.IGNORE_CASE)
            .find(t) ?: return null
        val catWord = after.groupValues[1].trim()
        var body = after.groupValues[2].trim().trimStart('=', ':', '-', '—').trim()
        if (catWord.isNotEmpty()) return catWord to body
        val m = Regex("""^([\p{L}]+)\s*[=:]\s*(.+)$""").find(body) ?: return null
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }
}
