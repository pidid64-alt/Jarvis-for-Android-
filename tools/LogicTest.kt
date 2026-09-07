package kz.jarvis.tools

import kz.jarvis.app.MathEngine
import kz.jarvis.app.TimeParse
import kz.jarvis.app.Timezones
import kz.jarvis.app.WakeWords

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

    println("")
    println("пройдено: $passed, провалено: $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
