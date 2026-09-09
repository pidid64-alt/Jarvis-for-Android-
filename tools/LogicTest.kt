package kz.jarvis.tools

import kz.jarvis.app.AssistantModes
import kz.jarvis.app.AutoReplyLogic
import kz.jarvis.app.ChatMedia
import kz.jarvis.app.DateFacts
import kz.jarvis.app.Deck
import kz.jarvis.app.LifeCalc
import kz.jarvis.app.LlmRequests
import kz.jarvis.app.Persona
import kz.jarvis.app.Providers
import kz.jarvis.app.MathEngine
import kz.jarvis.app.Randoms
import kz.jarvis.app.TextTools
import kz.jarvis.app.TimeParse
import kz.jarvis.app.Timezones
import kz.jarvis.app.Units
import kz.jarvis.app.ResearchPlan
import kz.jarvis.app.VoiceProfile
import kz.jarvis.app.WakeWords
import kz.jarvis.app.WebSearch
import java.util.Calendar
import kotlin.random.Random

/**
 * Проверка «чистой» логики (MathEngine, WakeWords) на обычной JVM.
 * Запуск: scripts/test-logic.sh — компилируются ТЕ ЖЕ файлы, что попадают в APK.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, expected: Any?, actual: Any?) {
    if (expected == actual) {
        passed++
    } else {
        failed++
        println("  FAIL  $name\n        ожидали: $expected\n        получили: $actual")
    }
}

private fun calc(phrase: String): String? {
    val r = MathEngine.calc(phrase)
    return if (r.ok) MathEngine.format(r.value!!) else r.error
}

fun main() {
    println("== MathEngine ==")
    check("12 умножить на 7", "84", calc("сколько будет 12 умножить на 7"))
    check("250 плюс 30", "280", calc("посчитай 250 плюс 30"))
    check("2+2*3", "8", calc("2+2*3"))
    check("дроби с запятой", "4", calc("1,5 плюс 2,5"))
    check("проценты от", "30", calc("20 процентов от 150"))
    check("сколько процентов", "10", calc("сколько процентов составляет 20 от 200"))
    check("корень", "12", calc("корень из 144"))
    check("деление с остатком", "2,5", calc("сколько будет 10 разделить на 4"))
    check("квадрат", "25", calc("5 в квадрате"))
    check("степень", "1024", calc("2 в степени 10"))
    check("кириллическое х", "12", calc("3 х 4"))
    check("скобки", "5", calc("(12+8)/4"))
    check("отрицательное", "5", calc("-5 плюс 10"))
    check("вычитание", "97", calc("отними 3 от 100"))
    check("цепочка", "33,3333", calc("раздели 100 на 3"))
    check("умножь X на Y", "84", calc("умножь 12 на 7"))
    check("прибавь X к Y", "15", calc("прибавь 5 к 10"))
    check("деление на ноль", "На ноль делить нельзя, сэр.", calc("10 разделить на 0"))
    check("не математика (привет)", null, calc("привет джарвис"))
    check("не математика (ютуб)", null, calc("открой ютуб"))
    check("не математика (фонарик)", null, calc("включи фонарик"))
    check("answer() оформляет", "12 * 7 = 84", MathEngine.answer("сколько будет 12 умножить на 7"))
    check("answer() не врёт про текст", null, MathEngine.answer("открой телеграм"))

    println("== WakeWords ==")
    check("точное слово", true, WakeWords.contains("Джарвис"))
    check("в середине фразы", true, WakeWords.contains("окей Джарвис который час"))
    check("английское", true, WakeWords.contains("jarvis turn on the light"))
    check("опечатка распознавателя", true, WakeWords.contains("Джервис, включи фонарик"))
    check("ложное срабатывание", false, WakeWords.contains("привет, как дела"))
    check("похожее слово не триггер", false, WakeWords.contains("джарвисон не при чём"))
    check("пусто", false, WakeWords.contains(""))
    check("strip в начале", "который час", WakeWords.strip("Джарвис, который час"))
    check("strip в конце", "включи фонарик", WakeWords.strip("включи фонарик, джарвис"))
    check("strip только слово", "", WakeWords.strip("Джарвис"))

    println("== TimeParse ==")
    check("таймер на 5 минут", 300, TimeParse.duration("таймер на 5 минут")?.seconds)
    check("человекочитаемо 5 минут", "5 мин.", TimeParse.duration("таймер на 5 минут")?.human)
    check("через 10 минут", 600, TimeParse.duration("через 10 минут")?.seconds)
    check("через 2 часа", 7200, TimeParse.duration("напомни через 2 часа")?.seconds)
    check("30 секунд", 30, TimeParse.duration("таймер на 30 секунд")?.seconds)
    // регрессия: «будильник на 7:30» НЕ должен превращаться в таймер на 7 минут
    check("будильник 7:30 не таймер", null, TimeParse.duration("будильник на 7:30"))
    check("будильник 7:30", "07:30", TimeParse.clock("будильник на 7:30")?.human)
    check("разбуди в 6 вечера", 18, TimeParse.clock("разбуди меня в 6 вечера")?.hour)
    check("будильник на 7", "07:00", TimeParse.clock("будильник на 7")?.human)
    check("таймер — не будильник", null, TimeParse.clock("таймер на 5 минут"))

    println("== Timezones ==")
    check("лондон (падеж)", "Europe/London", Timezones.find("лондоне"))
    check("питер с пробелом", "Europe/Moscow", Timezones.find("санкт петербурге"))
    check("нью-йорк с дефисом", "America/New_York", Timezones.find("нью-йорк"))
    check("алматы", "Asia/Almaty", Timezones.find("алматы"))
    check("неизвестный город", null, Timezones.find("марсианске"))

    println("== Units ==")
    check("5 км в милях", "5 км — это 3,1069 миль.", Units.convert("5 км в милях"))
    check("сколько метров в 5 километрах", "5 км — это 5000 м.", Units.convert("сколько метров в 5 километрах"))
    check("70 кг в фунтах", "70 кг — это 154,3237 фунтов.", Units.convert("70 кг в фунтах"))
    check("180 см в дюймах", "180 см — это 70,8661 дюймов.", Units.convert("180 см в дюймах"))
    check("2 гб в мб", "2 ГБ — это 2048 МБ.", Units.convert("2 гб в мб"))
    check("100 фаренгейта в цельсиях", "100 °F — это 37,7778 °C.",
        Units.convert("100 градусов фаренгейта в цельсиях"))
    check("0 кельвинов в цельсиях", "0 K — это -273,15 °C.", Units.convert("0 кельвинов в цельсиях"))
    check("3 дня в часах", "3 дн. — это 72 ч.", Units.convert("3 дня в часах"))
    check("100 км/ч в м/с", "100 км/ч — это 27,7778 м/с.", Units.convert("100 км в час в м/с"))
    check("1 гектар в акрах", "1 га — это 2,4711 акров.", Units.convert("1 гектар в акрах"))
    check("2 литра в галлонах", "2 л — это 0,5283 галлонов.", Units.convert("2 литра в галлонах"))
    check("5 фунтов в килограммах", "5 фунтов — это 2,268 кг.", Units.convert("5 фунтов в килограммах"))
    check("6 соток в м2", "6 соток — это 600 м².", Units.convert("6 соток в квадратных метрах"))
    check("100 по фаренгейту", "100 °F — это 37,7778 °C.", Units.temperatureOnly("100 по фаренгейту"))
    check("не конвертация (привет)", null, Units.convert("привет джарвис"))
    check("не конвертация (ютуб)", null, Units.convert("открой ютуб"))
    check("не конвертация (время в лондоне)", null, Units.convert("время в лондоне"))
    check("не конвертация (громкость)", null, Units.convert("громкость 40 процентов"))
    check("разные категории", null, Units.convert("5 км в килограммах"))

    println("== LifeCalc ==")
    check("ИМТ", "ИМТ 24,6914 — это норма. Нормальный вес для вашего роста: 59,94–80,676 кг.",
        LifeCalc.ask("мой вес 80 рост 180"))
    check("чаевые", "Чаевые 10% от 3500 — это 350. Итого 3850.",
        LifeCalc.ask("чаевые 10 процентов от 3500"))
    check("чаевые по умолчанию", "Чаевые 10% от 5000 — это 500. Итого 5500.",
        LifeCalc.ask("сколько чаевых от 5000"))
    check("скидка", "Со скидкой 20% — 4000, экономия 1000.", LifeCalc.ask("скидка 20 процентов с 5000"))
    check("разделить счёт", "На каждого из 3 — 3000.", LifeCalc.ask("раздели 9000 на троих"))
    check("разделить на людей", "На каждого из 4 — 2500.", LifeCalc.ask("раздели 10000 на 4 человек"))
    check("обычное деление — не счёт", null, LifeCalc.ask("раздели 100 на 4"))
    check("площадь круга", "Площадь круга — 78,5398 (радиус 5).", LifeCalc.ask("площадь круга радиус 5"))
    check("площадь комнаты", "Площадь — 12 м², периметр — 14 м.", LifeCalc.ask("площадь комнаты 3 на 4"))
    check("среднее", "Среднее из 3 чисел — 5.", LifeCalc.ask("среднее 4 5 6"))
    check("факториал", "Факториал 5 — 120.", LifeCalc.ask("факториал 5"))
    check("нод", "НОД(12, 18) = 6.", LifeCalc.ask("нод 12 и 18"))
    check("нок", "НОК(4, 6) = 12.", LifeCalc.ask("нок 4 и 6"))
    check("римскими", "1994 римскими — MCMXCIV.", LifeCalc.ask("1994 римскими"))
    check("из римского", "MCMXCIV — это 1994.", LifeCalc.ask("сколько будет MCMXCIV"))
    check("на сколько процентов", "120 больше 100 на 20%.",
        LifeCalc.ask("на сколько процентов 120 больше 100"))
    check("знак зодиака", "Знак зодиака — Телец.", LifeCalc.ask("какой знак зодиака 5 мая"))
    check("знак зодиака 1 января", "Знак зодиака — Козерог.", LifeCalc.ask("какой знак зодиака 1 января"))
    check("норма воды", "При весе 80 кг стоит пить около 2,64 литра воды в день — это 11 стаканов.",
        LifeCalc.ask("сколько воды пить при весе 80"))
    check("не бытовой расчёт", null, LifeCalc.ask("сколько будет 2 плюс 2"))

    println("== DateFacts ==")
    val now = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.SEPTEMBER, 7, 12, 0, 0)
    }.timeInMillis
    check("месяц по падежу (мая)", 4, DateFacts.monthIndex("мая"))
    check("месяц по падежу (марта)", 2, DateFacts.monthIndex("марта"))
    check("месяц по падежу (декабре)", 11, DateFacts.monthIndex("декабре"))
    check("не месяц (минут)", null, DateFacts.monthIndex("минут"))
    check("дней до 31 декабря", "До 31 декабря — 115 дн., сэр.", DateFacts.ask("сколько дней до 31 декабря", now))
    check("дней между датами", "Между этими датами 19 дн., сэр.",
        DateFacts.ask("сколько дней между 1 мая и 20 мая", now))
    check("день недели 2030", "1 января 2030 — это вторник, сэр.",
        DateFacts.ask("какой день недели 1 января 2030", now))
    check("високосный", "2028 — високосный, в нём 366 дней.", DateFacts.ask("високосный ли 2028 год", now))
    check("не високосный", "2027 — не високосный, в нём 365 дней.", DateFacts.ask("високосный ли 2027 год", now))
    check("дней в феврале", "В феврале 2026 года 28 дн., сэр.", DateFacts.ask("сколько дней в феврале", now))
    check("секунд в сутках", "В сутках 86400 секунд, сэр.", DateFacts.ask("сколько секунд в сутках", now))
    check("возраст в днях", "С этой даты прошло 13274 дн. — примерно 36,3422 года.",
        DateFacts.ask("сколько мне дней если я родился 5 мая 1990", now))
    check("30 лет в днях", "30 лет — это примерно 10957 дн., сэр.",
        DateFacts.ask("мне 30 лет сколько это дней", now))
    check("до конца года", "До конца года — 115 дн., сэр.", DateFacts.ask("сколько дней до конца года", now))
    check("не календарь", null, DateFacts.ask("привет джарвис", now))
    check("через 40 дней", true, DateFacts.ask("какая дата будет через 40 дней", now)?.contains("17 октября 2026"))
    check("завтра", true, DateFacts.ask("какое число завтра", now)?.contains("8 сентября 2026"))

    println("== TextTools ==")
    check("сколько слов", "В этой фразе 3 слова.", TextTools.ask("сколько слов в тексте раз два три"))
    check("сколько букв", "Всего символов: 6, из них букв: 6.", TextTools.ask("сколько букв в фразе привет"))
    check("переверни фразу", "«ирт авд зар»", TextTools.ask("переверни фразу раз два три"))
    check("переверни слово", "«тевирп»", TextTools.ask("переверни слово привет"))
    check("заглавными", "«ПРИВЕТ МИР»", TextTools.ask("заглавными привет мир"))
    check("морзе", "... --- ... /", TextTools.ask("морзе сос"))
    check("транслит", "«privet»", TextTools.ask("транслит привет"))
    check("порядок слов", "«три два раз»", TextTools.ask("поменяй порядок слов раз два три"))
    check("повтори за мной", "Привет мир.", TextTools.ask("повтори за мной привет мир"))
    check("не текст", null, TextTools.ask("включи фонарик"))
    check("склонение 1", "пункт", TextTools.plural(1, "пункт", "пункта", "пунктов"))
    check("склонение 3", "пункта", TextTools.plural(3, "пункт", "пункта", "пунктов"))
    check("склонение 5", "пунктов", TextTools.plural(5, "пункт", "пункта", "пунктов"))
    check("склонение 11", "пунктов", TextTools.plural(11, "пункт", "пункта", "пунктов"))
    check("склонение 21", "пункт", TextTools.plural(21, "пункт", "пункта", "пунктов"))

    println("== Randoms ==")
    check("выбор из «между … и …»", listOf("чаем", "кофе"), Randoms.options("выбери между чаем и кофе"))
    check("выбор из списка", listOf("чай", "кофе", "сок"), Randoms.options("выбери из: чай, кофе, сок"))
    check("монетка", true, Randoms.ask("подбрось монетку", Random(42)) in listOf("Орёл, сэр.", "Решка, сэр."))
    check("кубик", true, Randoms.ask("кинь кубик", Random(7))?.startsWith("Выпало "))
    check("три кубика", true,
        Regex("^Выпало: \\d \\+ \\d \\+ \\d = \\d+\\.$").matches(Randoms.ask("брось 3 кубика", Random(7)) ?: ""))
    check("случайное число", true,
        Regex("^Ваше число: \\d+\\.$").matches(Randoms.ask("случайное число от 1 до 100", Random(3)) ?: ""))
    check("да или нет", true, Randoms.ask("да или нет", Random(1)) in listOf("Да, сэр.", "Нет, сэр."))
    check("загадка", true, Randoms.ask("загадай загадку", Random(2))?.endsWith("если сдаётесь."))
    check("ответ на загадку", true, Randoms.ask("ответ на загадку", Random(2))?.startsWith("Ответ: "))
    check("пароль", true, Randoms.ask("придумай пароль на 8 символов", Random(5))?.length == "Пароль: ".length + 8)
    check("код", true, Regex("^Ваш код: \\d{6}\\.$").matches(Randoms.ask("случайный код из 6 цифр", Random(9)) ?: ""))
    check("анекдот", true, (Randoms.ask("расскажи анекдот", Random(11))?.length ?: 0) > 10)
    check("не развлечение", null, Randoms.ask("включи фонарик", Random(11)))

    println("== Провайдеры ИИ ==")
    check("по умолчанию — Gemini", "gemini", Providers.byId(null).id)
    check("неизвестный id — Gemini", "gemini", Providers.byId("такого-нет").id)
    check("Groq — формат OpenAI", Providers.Api.OPENAI, Providers.byId("groq").api)
    check("Claude — свой формат", Providers.Api.ANTHROPIC, Providers.byId("anthropic").api)
    check("Ollama — без ключа", true, Providers.byId("ollama").keyOptional)
    check("Ollama — локальный адрес", "http://127.0.0.1:11434/v1", Providers.byId("ollama").baseUrl)
    check("индекс найденного", Providers.ALL.indexOfFirst { it.id == "deepseek" }, Providers.indexOf("deepseek"))
    check("индекс неизвестного — 0", 0, Providers.indexOf("хм"))
    check("id уникальны", Providers.ALL.size, Providers.ALL.map { it.id }.distinct().size)
    check("имена уникальны", Providers.ALL.size, Providers.names().distinct().size)
    check("у всех есть адрес", true, Providers.ALL.all { it.baseUrl.startsWith("http") })
    check("источник ключа неизвестен только у своего сервера", listOf("custom"),
        Providers.ALL.filter { !it.keyOptional && it.keyUrl.isBlank() }.map { it.id })
    check("модель по умолчанию", "deepseek-chat", Providers.byId("deepseek").defaultModel)
    check("у своего сервера моделей нет", "", Providers.byId("custom").defaultModel)

    println("== Личность ==")
    check("системный промпт — про Джарвиса", true, Persona.SYSTEM_PROMPT.contains("Джарвис"))
    check("системный промпт — по-русски", true, Persona.SYSTEM_PROMPT.contains("по-русски"))
    check("промпт запрещает Markdown", true, Persona.SYSTEM_PROMPT.contains("Markdown"))

    println("== Запросы к модели ==")
    check("экранирование кавычки", "\"он сказал \\\"привет\\\"\"", LlmRequests.json("он сказал \"привет\""))
    check("экранирование переноса", "\"а\\nб\"", LlmRequests.json("а\nб"))
    check("экранирование слэша", "\"c:\\\\temp\"", LlmRequests.json("c:\\temp"))
    check("кириллица без \\u", "\"привет, сэр\"", LlmRequests.json("привет, сэр"))
    check("табуляция", "\"а\\tб\"", LlmRequests.json("а\tб"))

    check("адрес Gemini", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        LlmRequests.geminiUrl("https://generativelanguage.googleapis.com", "gemini-2.5-flash"))
    check("адрес Gemini со слэшем на конце",
        "https://api.example.com/v1beta/models/m1:generateContent",
        LlmRequests.geminiUrl("https://api.example.com/", " m1 "))
    check("адрес OpenAI", "https://api.openai.com/v1/chat/completions",
        LlmRequests.openAiUrl("https://api.openai.com/v1/"))
    check("адрес Anthropic", "https://api.anthropic.com/v1/messages",
        LlmRequests.anthropicUrl("https://api.anthropic.com/v1"))

    val hist = listOf(true to "привет", false to "здравствуй, сэр")
    check("тело OpenAI",
        "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"system\",\"content\":\"Ты — Джарвис\"}," +
            "{\"role\":\"user\",\"content\":\"привет\"},{\"role\":\"assistant\",\"content\":\"здравствуй, сэр\"}," +
            "{\"role\":\"user\",\"content\":\"как дела?\"}],\"temperature\":0.7,\"max_tokens\":10}",
        LlmRequests.openAiBody("gpt-4o-mini", "Ты — Джарвис", hist, "как дела?", 10))
    check("тело Anthropic",
        "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":10,\"temperature\":0.7," +
            "\"system\":\"Ты — Джарвис\",\"messages\":[{\"role\":\"user\",\"content\":\"привет\"}," +
            "{\"role\":\"assistant\",\"content\":\"здравствуй, сэр\"},{\"role\":\"user\",\"content\":\"как дела?\"}]}",
        LlmRequests.anthropicBody("claude-sonnet-4-5", "Ты — Джарвис", hist, "как дела?", 10))
    check("тело Gemini",
        "{\"systemInstruction\":{\"parts\":[{\"text\":\"Ты — Джарвис\"}]},\"contents\":[" +
            "{\"role\":\"user\",\"parts\":[{\"text\":\"привет\"}]}," +
            "{\"role\":\"model\",\"parts\":[{\"text\":\"здравствуй, сэр\"}]}," +
            "{\"role\":\"user\",\"parts\":[{\"text\":\"как дела?\"}]}" +
            "],\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":10}}",
        LlmRequests.geminiBody("Ты — Джарвис", hist, "как дела?", 10))

    val long = (1..30).map { (it % 2 == 1) to "реплика $it" }
    check("история OpenAI ограничена", 14,
        LlmRequests.openAiBody("m", "s", long, "вопрос").split("\"content\"").size - 1)
    check("история Anthropic ограничена", 13,
        LlmRequests.anthropicBody("m", "s", long, "вопрос").split("\"content\"").size - 1)
    check("история Gemini ограничена", 14,
        LlmRequests.geminiBody("s", long, "вопрос").split("\"parts\"").size - 1)
    check("последняя реплика — от пользователя", true,
        LlmRequests.openAiBody("m", "s", long, "вопрос").endsWith(
            "{\"role\":\"user\",\"content\":\"вопрос\"}],\"temperature\":0.7,\"max_tokens\":600}"))


    // =========================================================== голос Джарвиса
    println("== Голос ==")
    check("профиль по умолчанию", "jarvis", VoiceProfile.byId(null).id)
    check("профиль джарвиса ниже обычного", true, VoiceProfile.byId("jarvis").pitch < 1f)
    check("неизвестный профиль → джарвис", "jarvis", VoiceProfile.byId("нет такого").id)
    check("профилей пять", 5, VoiceProfile.ALL.size)
    check("границы тона снизу", VoiceProfile.PITCH_MIN, VoiceProfile.clampPitch(0.1f))
    check("границы тона сверху", VoiceProfile.PITCH_MAX, VoiceProfile.clampPitch(9f))
    check("границы скорости", VoiceProfile.RATE_MAX, VoiceProfile.clampRate(5f))

    check("мужской голос по имени", true, VoiceProfile.looksMasculine("ru-ru-x-rum-local"))
    check("женский голос по имени", true, VoiceProfile.looksFeminine("ru-RU-Standard-A#female_1"))
    check("женский не считается мужским", false, VoiceProfile.looksMasculine("ru-ru-x-female-local"))

    val voices = listOf(
        VoiceProfile.VoiceInfo("ru-ru-x-ruf-local", "ru_RU", 400, false),
        VoiceProfile.VoiceInfo("ru-ru-x-rum-local", "ru_RU", 400, false),
        VoiceProfile.VoiceInfo("en-us-x-sfg-local", "en_US", 500, false)
    )
    check("для Джарвиса выбираем мужской", "ru-ru-x-rum-local",
        VoiceProfile.bestVoice(voices, VoiceProfile.byId("jarvis")))
    check("для Пятницы выбираем женский", "ru-ru-x-ruf-local",
        VoiceProfile.bestVoice(voices, VoiceProfile.byId("friday")))
    check("английский голос не годится", true,
        VoiceProfile.scoreVoice("en-us-x-sfg-local", "en_US", 500, false,
            VoiceProfile.byId("jarvis")) < -100)
    check("офлайн-голос лучше сетевого", true,
        VoiceProfile.scoreVoice("ru-ru-x-rum-local", "ru_RU", 300, false, VoiceProfile.byId("jarvis")) >
            VoiceProfile.scoreVoice("ru-ru-x-rum-network", "ru_RU", 300, true, VoiceProfile.byId("jarvis")))

    fun voiceCmd(s: String): String = when (val c = VoiceProfile.parse(s)) {
        null -> "нет"
        is VoiceProfile.Cmd.Set -> "профиль:${c.id}"
        is VoiceProfile.Cmd.Pitch -> if (c.delta < 0) "тон:ниже" else "тон:выше"
        is VoiceProfile.Cmd.Rate -> if (c.delta < 0) "темп:медленнее" else "темп:быстрее"
        VoiceProfile.Cmd.Reset -> "сброс"
        VoiceProfile.Cmd.Test -> "проверка"
        VoiceProfile.Cmd.Info -> "инфо"
        VoiceProfile.Cmd.Settings -> "настройки"
    }
    check("голос джарвиса", "профиль:jarvis", voiceCmd("включи голос джарвиса"))
    check("голос брони", "профиль:stark", voiceCmd("голос брони"))
    check("деловой голос", "профиль:agent", voiceCmd("деловой голос"))
    check("женский голос", "профиль:friday", voiceCmd("женский голос"))
    check("обычный голос", "профиль:classic", voiceCmd("обычный голос"))
    check("говори ниже", "тон:ниже", voiceCmd("говори ниже"))
    check("говори быстрее", "темп:быстрее", voiceCmd("говори быстрее"))
    check("говори медленнее", "темп:медленнее", voiceCmd("говори медленнее"))
    check("сброс голоса", "сброс", voiceCmd("сбрось голос"))
    check("проверь голос", "проверка", voiceCmd("проверь голос"))
    check("какой у тебя голос", "инфо", voiceCmd("какой у тебя голос"))
    check("настройки голоса", "настройки", voiceCmd("смени голос"))
    check("не про голос", "нет", voiceCmd("включи фонарик"))
    check("громче — это громкость, не голос", "нет", voiceCmd("сделай громче"))

    // ============================================== поиск, Клод и презентации
    println("== Поиск и презентации ==")
    fun plan(s: String): String {
        val p = ResearchPlan.parse(s) ?: return "нет"
        return "${p.kind}|${p.topic}|${p.slides}|${p.viaClaudeApp}"
    }
    check("поищи инфу и презентация", "PRESENTATION|квантовые компьютеры|8|false",
        plan("поищи инфу про квантовые компьютеры и сделай презентацию"))
    check("сделай презентацию на 12 слайдов", "PRESENTATION|марс|12|false",
        plan("сделай презентацию про марс на 12 слайдов"))
    check("доклад", "PRESENTATION|нейросети в медицине|8|false",
        plan("подготовь доклад о нейросетях в медицине").replace("нейросетях в медицине", "нейросети в медицине"))
    check("поиск без презентации", "RESEARCH|рынок кофе в казахстане|8|false",
        plan("поищи информацию про рынок кофе в казахстане"))
    check("презентация через клод", true,
        (ResearchPlan.parse("сделай презентацию про марс через клод")?.viaClaudeApp) == true)
    check("вопрос клоду", "CLAUDE|как настроить роутер|8|false",
        plan("спроси у клода как настроить роутер"))
    check("открой презентацию", "OPEN_LAST||8|false", plan("открой презентацию"))
    check("обычный поиск не трогаем", "нет", plan("найди пиццу"))
    check("команда телефона не трогается", "нет", plan("включи фонарик"))
    check("погода не трогается", "нет", plan("какая погода в алматы"))

    check("число слайдов по умолчанию", 8, ResearchPlan.slidesCount("сделай презентацию про марс"))
    check("число слайдов из фразы", 15, ResearchPlan.slidesCount("презентация на 15 слайдов"))
    check("слайдов не больше максимума", ResearchPlan.MAX_SLIDES,
        ResearchPlan.slidesCount("презентация на 90 слайдов"))
    check("тема без мусора", "искусственный интеллект",
        ResearchPlan.cleanTopic("поищи мне инфу про искусственный интеллект и сделай презентацию"))
    check("тема без хвоста про слайды", "древний рим",
        ResearchPlan.cleanTopic("сделай презентацию про древний рим на 10 слайдов"))
    check("запросов к поиску три", 3, ResearchPlan.searchQueries("марс").size)
    check("в промпте есть тема и формат", true,
        ResearchPlan.deckPrompt("марс", "выжимка", 7).contains("СЛАЙД:") &&
            ResearchPlan.deckPrompt("марс", "выжимка", 7).contains("марс"))
    check("сообщение для приложения Клода", true,
        ResearchPlan.claudeAppMessage("марс", "выжимка", 7, true).contains("презентацию на 7 слайдов"))

    // ------------------------------------------------------------- слайды
    val modelAnswer = """
        ЗАГОЛОВОК: Квантовые компьютеры
        ПОДЗАГОЛОВОК: Что это и зачем нужно
        СЛАЙД: Что это такое
        - Считают на кубитах, а не на битах
        - Кубит хранит суперпозицию состояний
        ЗАМЕТКА: Начните с бытовой аналогии.
        СЛАЙД: Где применяют
        - Химия и материалы
        - Криптография
        - Логистика
        ИТОГ: Технология молодая, но уже меняет расчёты.
    """.trimIndent()
    val deck = Deck.parse("квантовые компьютеры", modelAnswer,
        listOf(Deck.Source("Википедия", "https://ru.wikipedia.org/wiki/Тест")))
    check("слайдов разобрано", 2, deck.slides.size)
    check("заголовок презентации", "Квантовые компьютеры", deck.title)
    check("подзаголовок", "Что это и зачем нужно", deck.subtitle)
    check("название первого слайда", "Что это такое", deck.slides[0].title)
    check("пунктов на первом слайде", 2, deck.slides[0].bullets.size)
    check("заметка докладчику", "Начните с бытовой аналогии.", deck.slides[0].note)
    check("пунктов на втором слайде", 3, deck.slides[1].bullets.size)
    check("итог", "Технология молодая, но уже меняет расчёты.", deck.summary)
    check("презентация валидна", true, deck.ok)

    val markdownDeck = Deck.parse("тест", "## Первый слайд\n* пункт один\n* пункт два\n## Второй\n- ещё пункт")
    check("markdown-формат тоже понимаем", 2, markdownDeck.slides.size)
    check("звёздочки как пункты", 2, markdownDeck.slides[0].bullets.size)

    val html = Deck.html(deck)
    check("html самодостаточен", true, html.startsWith("<!DOCTYPE html>") && html.contains("</html>"))
    check("html содержит слайды", true, html.contains("Что это такое") && html.contains("Где применяют"))
    check("html экранирует опасное", "&lt;b&gt;x&lt;/b&gt;", Deck.esc("<b>x</b>"))
    check("markdown содержит источники", true, Deck.markdown(deck).contains("## Источники"))
    check("озвучка короткая и по делу", true,
        Deck.speech(deck).startsWith("Готово, сэр.") && Deck.speech(deck).contains("2 слайдов"))
    check("имя файла транслитом", "kvantovye-kompyutery", Deck.slug("Квантовые компьютеры"))
    check("имя файла без пустоты", "prezentaciya", Deck.slug("!!!"))

    // ------------------------------------------------------- разбор выдачи
    println("== Разбор поисковой выдачи ==")
    val wikiJson = """{"query":{"pages":{"42":{"pageid":42,"title":"Марс",
        "extract":"Марс — четвёртая планета от Солнца."}}}}""".trimIndent()
    val wiki = WebSearch.parseWikipedia(wikiJson)
    check("википедия: одна статья", 1, wiki.size)
    check("википедия: заголовок", "Марс", wiki[0].title)
    check("википедия: текст", true, wiki[0].snippet.startsWith("Марс — четвёртая"))
    check("википедия: ссылка", true, wiki[0].url.startsWith("https://ru.wikipedia.org/wiki/"))

    val duckJson = """{"Heading":"Mars","AbstractText":"Mars is the fourth planet.",
        "AbstractURL":"https://en.wikipedia.org/wiki/Mars","AbstractSource":"Wikipedia",
        "RelatedTopics":[{"FirstURL":"https://duckduckgo.com/Phobos","Text":"Phobos - moon of Mars"}]}""".trimIndent()
    val duck = WebSearch.parseDuckJson(duckJson)
    check("duckduckgo: справка + связанное", 2, duck.size)
    check("duckduckgo: заголовок", "Mars", duck[0].title)

    val duckHtml = """<div class="result"><a rel="nofollow" class="result__a"
        href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fmars&amp;rut=x">Марс &amp; спутники</a>
        <a class="result__snippet" href="#">Планета <b>Марс</b> и её спутники.</a></div>""".trimIndent()
    val hits = WebSearch.parseDuckHtml(duckHtml)
    check("html-выдача: одна находка", 1, hits.size)
    check("html-выдача: заголовок без тегов", "Марс & спутники", hits[0].title)
    check("html-выдача: настоящая ссылка", "https://example.com/mars", hits[0].url)
    check("html-выдача: домен как источник", "example.com", hits[0].source)
    check("html-выдача: описание", "Планета Марс и её спутники.", hits[0].snippet)

    check("редирект duckduckgo разворачивается", "https://ya.ru/x",
        WebSearch.realUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fya.ru%2Fx&amp;rut=1"))
    check("html-сущности", "«тест» — да", WebSearch.unescapeHtml("&laquo;тест&raquo; &mdash; да"))
    check("теги вырезаются", "жирный текст", WebSearch.stripTags("<b>жирный</b>  текст"))
    check("json-экранирование", "строка\nдальше", WebSearch.jsonUnescape("строка\\nдальше"))

    val digest = WebSearch.digest("марс", hits, "8 сентября 2025")
    check("выжимка содержит тему", true, digest.contains("Тема запроса: марс"))
    check("выжимка содержит ссылку", true, digest.contains("https://example.com/mars"))
    check("выжимка ограничена по размеру", true, WebSearch.digest("t", hits, "", 40).length <= 200)

    val draft = WebSearch.draft("марс", listOf(
        WebSearch.Hit("Марс", "Марс — четвёртая планета Солнечной системы и вторая по малости. " +
            "Атмосфера очень разрежена и состоит в основном из углекислого газа.",
            "https://ru.wikipedia.org/wiki/Марс", "Википедия"),
        WebSearch.Hit("Миссии", "К Марсу отправлено более сорока аппаратов разных стран мира. " +
            "Успешными оказались меньше половины запусков за всю историю.",
            "https://example.com/missions", "example.com")
    ))
    check("черновик собирается без модели", true, draft.ok)
    check("в черновике есть источники", 2, draft.sources.size)

    println("== Непрерывный диалог ==")
    check("включи непрерывный диалог", true, AssistantModes.dialogRequest("включи непрерывный диалог"))
    check("выключи непрерывный диалог", false, AssistantModes.dialogRequest("выключи непрерывный диалог"))
    check("вопрос о состоянии — не приказ", null,
        AssistantModes.dialogRequest("непрерывный диалог включен?"))
    check("не про диалог", null, AssistantModes.dialogRequest("расскажи анекдот"))
    check("говорить подряд — про диалог", true,
        AssistantModes.dialogRequest("включи говорить подряд"))
    check("после ответа слушай без джарвис", true,
        AssistantModes.dialogRequest("после ответа продолжай слушать без джарвис"))

    println("== Автоответчик: разбор команд ==")
    check("включи автоответ", AssistantModes.AutoReplyCommand.ON,
        AssistantModes.autoReplyCommand("включи автоответ"))
    check("выключи автоответ", AssistantModes.AutoReplyCommand.OFF,
        AssistantModes.autoReplyCommand("выключи автоответ"))
    check("выключи автоответчик", AssistantModes.AutoReplyCommand.OFF,
        AssistantModes.autoReplyCommand("выключи автоответчик"))
    check("статус-вопрос", AssistantModes.AutoReplyCommand.STATUS,
        AssistantModes.autoReplyCommand("автоответчик работает?"))
    check("что такое автоответчик — статус", AssistantModes.AutoReplyCommand.STATUS,
        AssistantModes.autoReplyCommand("что такое автоответчик"))
    check("отвечай за меня — включить", AssistantModes.AutoReplyCommand.ON,
        AssistantModes.autoReplyCommand("отвечай за меня в мессенджерах"))
    check("добавь правило", AssistantModes.AutoReplyCommand.RULES_ADD,
        AssistantModes.autoReplyCommand("добавь правило: если зовут гулять — отвечай, что я занят"))
    check("какие правила", AssistantModes.AutoReplyCommand.RULES_SHOW,
        AssistantModes.autoReplyCommand("какие правила автоответа"))
    check("очисти правила", AssistantModes.AutoReplyCommand.RULES_CLEAR,
        AssistantModes.autoReplyCommand("очисти правила автоответа"))
    check("не про автоответ (погода)", null,
        AssistantModes.autoReplyCommand("какая погода"))
    check("не про автоответ (правила без слова)", null,
        AssistantModes.autoReplyCommand("покажи правила"))
    check("проверь автоответчик — диагностика", AssistantModes.AutoReplyCommand.DIAG,
        AssistantModes.autoReplyCommand("проверь автоответчик"))
    check("почему не работает — диагностика", AssistantModes.AutoReplyCommand.DIAG,
        AssistantModes.autoReplyCommand("почему не работает автоответчик"))
    check("жалоба, что молчит — диагностика", AssistantModes.AutoReplyCommand.DIAG,
        AssistantModes.autoReplyCommand("автоответчик не срабатывает"))
    check("проверь мессенджеры — диагностика", AssistantModes.AutoReplyCommand.DIAG,
        AssistantModes.autoReplyCommand("проверь, почему автоответ не отвечает в вацапе"))
    check("статус «работает?» — не диагностика", AssistantModes.AutoReplyCommand.STATUS,
        AssistantModes.autoReplyCommand("автоответчик работает?"))

    println("== Автоответчик: правила и мессенджеры ==")
    check("правило после двоеточия", "если зовут гулять — отвечай, что я занят",
        AssistantModes.autoRuleText("добавь правило: если зовут гулять — отвечай, что я занят"))
    check("правило после тире", "не отвечай маме после 22 часов",
        AssistantModes.autoRuleText("добавь правило автоответа — не отвечай маме после 22 часов"))
    check("правило без знака", "не соглашайся на встречи",
        AssistantModes.autoRuleText("запомни правило не соглашайся на встречи"))
    check("whatsapp в списке", "WhatsApp", AutoReplyLogic.messengerLabel("com.whatsapp"))
    check("telegram в списке", "Telegram", AutoReplyLogic.messengerLabel("org.telegram.messenger"))
    check("посторонний пакет", null, AutoReplyLogic.messengerLabel("com.example.app"))
    check("контакт «Мама» найден", true,
        AutoReplyLogic.isKnownPerson("Мама", listOf("Мама (дом)", "Папа")))
    check("номер — личный чат", true,
        AutoReplyLogic.isKnownPerson("+7 912 345-67-89", emptyList()))
    check("группа не совпадает с контактами", false,
        AutoReplyLogic.isKnownPerson("Семья и друзья", listOf("Мама")))
    check("служебный мусор пропускаем", true,
        AutoReplyLogic.shouldSkip("Мама", "🔒 Сообщения защищены сквозным шифрованием."))
    check("обычный вопрос не мусор", false, AutoReplyLogic.shouldSkip("Мама", "ты где?"))
    check("вопрос «где ты» распознан", true, AutoReplyLogic.asksLocation("мама пишет: ты где?"))
    check("не-вопрос про место", false, AutoReplyLogic.asksLocation("пришли документы"))

    println("== Автоответчик: ответ модели и лимиты ==")
    check("json-ответ разобран", "Я скоро буду дома",
        AutoReplyLogic.parseReply("{\"reply\": \"Я скоро буду дома\"}"))
    check("json с пустым ответом", "", AutoReplyLogic.parseReply("{\"reply\": \"\"}"))
    check("json в код-блоке", "нет", AutoReplyLogic.parseReply("```json\n{\"reply\": \"нет\"}\n```"))
    check("ответ без json", "Конечно", AutoReplyLogic.parseReply("Конечно"))
    check("экранированные кавычки в ответе", "он сказал \"ок\"",
        AutoReplyLogic.parseReply("{\"reply\": \"он сказал \\\"ок\\\"\"}"))
    check("отбивка мусора после json", "", AutoReplyLogic.parseReply("Подумаю. {\"reply\": \"\"} Надеюсь помог"))
    check("чистка ответа убирает переносы", "да конечно",
        AutoReplyLogic.cleanReply("да\n  конечно  "))
    val prompt = AutoReplyLogic.decisionPrompt(
        "Мама", listOf("ты дома?"), "если зовут гулять — говори, что занят",
        "Местоположение владельца: Астана", "8 сентября 2025")
    check("промпт знает собеседника", true, prompt.contains("Мама"))
    check("промпт знает правила", true, prompt.contains("говори, что занят"))
    check("промпт знает геолокацию", true, prompt.contains("Астана"))
    check("промпт требует json", true, prompt.contains("\"reply\""))
    val nowMs = System.currentTimeMillis()
    check("лимит: пусто можно", true, AutoReplyLogic.allowed(emptyList(), nowMs))
    check("лимит: 3 из 4 можно", true,
        AutoReplyLogic.allowed(listOf(nowMs - 10_000, nowMs - 20_000, nowMs - 30_000), nowMs))
    check("лимит: 4 из 4 нельзя", false,
        AutoReplyLogic.allowed(listOf(nowMs - 1_000, nowMs - 2_000, nowMs - 3_000, nowMs - 4_000), nowMs))
    check("лимит: старые не считаются", true,
        AutoReplyLogic.allowed(listOf(nowMs - 120_000, nowMs - 61_000), nowMs))

    println("== WhatsApp: разбор «напиши … в вацап» ==")
    fun wa(name: String, msg: ChatMedia.WaMsg?, expName: String, expBody: String) {
        check(name, expName, msg?.name)
        check(name + " (текст)", expBody, msg?.body)
    }
    check("это про вацап", true, ChatMedia.isWaPhrase("напиши маме в вацап: привет"))
    check("это про ватсап", true, ChatMedia.isWaPhrase("отправь в ватсап папе привет"))
    check("это про whatsapp", true, ChatMedia.isWaPhrase("напиши маме в whatsapp привет"))
    check("смс — не вацап", false, ChatMedia.isWaPhrase("напиши маме смс привет"))
    check("телеграм — не вацап", false, ChatMedia.isWaPhrase("напиши маме в телеграм привет"))
    wa("текст после двоеточия", ChatMedia.parseWa("напиши маме в вацап: я уже еду"), "маме", "я уже еду")
    wa("текст после «что»", ChatMedia.parseWa("напиши маме в вацап что я занят"), "маме", "я занят")
    wa("вацап перед именем", ChatMedia.parseWa("отправь в вацап папе что я люблю его"), "папе", "я люблю его")
    wa("текст сразу после имени", ChatMedia.parseWa("скажи маме в вацапе привет как дела"), "маме", "привет как дела")
    wa("по вацапу", ChatMedia.parseWa("напиши маме по вацапу: скоро буду"), "маме", "скоро буду")
    check("без текста — не разбор", null, ChatMedia.parseWa("напиши маме в вацап"))

    println("== YouTube и Spotify: чистый запрос ==")
    check("ютуб: песня", "shape of you", ChatMedia.youtubeQuery("включи песню shape of you на ютубе"))
    check("ютуб: видео после платформы", "как собрать стол",
        ChatMedia.youtubeQuery("включи на ютубе видео как собрать стол"))
    check("ютуб: клип", "despacito", ChatMedia.youtubeQuery("поставь клип despacito на ютубе"))
    check("ютуб: просто открыть", "", ChatMedia.youtubeQuery("включи ютуб"))
    check("не ютуб", null, ChatMedia.youtubeQuery("включи музыку"))
    check("спотифай: песня", "джаз", ChatMedia.spotifyQuery("включи джаз в спотифае"))
    check("спотифай: открыть", "", ChatMedia.spotifyQuery("открой спотифай"))
    check("спотифай: нет платформы", null, ChatMedia.spotifyQuery("включи песню на ютубе"))

    println("== WhatsApp: номер в международный вид ==")
    check("8 771 234-56-78", "77712345678", ChatMedia.phoneForWa("8 771 234-56-78"))
    check("+7 771 234 56 78", "77712345678", ChatMedia.phoneForWa("+7 771 234 56 78"))
    check("местный 10 цифр", "77712345678", ChatMedia.phoneForWa("7712345678"))
    check("сломанный номер", null, ChatMedia.phoneForWa("номер не найден"))
    check("пусто", null, ChatMedia.phoneForWa(""))

    println("")
    println("пройдено: $passed, провалено: $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
