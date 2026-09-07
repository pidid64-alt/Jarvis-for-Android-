package kz.jarvis.tools

import kz.jarvis.app.DateFacts
import kz.jarvis.app.LifeCalc
import kz.jarvis.app.MathEngine
import kz.jarvis.app.Randoms
import kz.jarvis.app.TextTools
import kz.jarvis.app.TimeParse
import kz.jarvis.app.Timezones
import kz.jarvis.app.Units
import kz.jarvis.app.WakeWords
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

    println("")
    println("пройдено: $passed, провалено: $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
