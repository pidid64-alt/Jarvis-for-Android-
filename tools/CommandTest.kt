package kz.jarvis.tools

import android.content.Context
import kz.jarvis.app.CommandEngine

/**
 * Интеграционная проверка диспетчера команд: вызывается НАСТОЯЩИЙ
 * CommandEngine.execute(), поэтому тест ловит конфликты между разделами
 * («раздели 100 на 4» — арифметика, а «раздели 9000 на троих» — счёт в кафе).
 *
 * Команды, которым нужна система (звук, фонарик, окна), здесь не дёргаются:
 * Context — «пустышка», любое обращение к системе падает, и это сразу
 * видно в отчёте как FAIL с исключением.
 *
 * Запуск: scripts/test-commands.sh — компилируется весь код приложения.
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

/**
 * Context-«пустышка»: android.jar — это заглушки, конструкторы бросают
 * «Stub!», поэтому объект создаём в обход конструктора (ReflectionFactory).
 * Сам объект существует, но любое обращение к системе (звук, фонарик, окна)
 * падает — такие команды тест не дёргает.
 */
private fun fakeContext(): Context {
    val rfClass = Class.forName("sun.reflect.ReflectionFactory")
    val rf = rfClass.getMethod("getReflectionFactory").invoke(null)
    val target = Class.forName("android.content.ContextWrapper")
    val ctor = rfClass.getMethod(
        "newConstructorForSerialization", Class::class.java, java.lang.reflect.Constructor::class.java
    ).invoke(rf, target, Any::class.java.getDeclaredConstructor()) as java.lang.reflect.Constructor<*>
    return ctor.newInstance() as Context
}

fun main() {
    val ctx = fakeContext()

    fun exec(phrase: String): CommandEngine.Outcome? = try {
        CommandEngine.execute(ctx, phrase)
    } catch (e: Throwable) {
        failed++
        println("  FAIL  «$phrase» — исключение ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    fun reply(phrase: String): String? = exec(phrase)?.reply

    println("== Обращение и служебные ==")
    check("приветствие", "Здравствуйте, сэр. Чем могу помочь?", reply("джарвис привет"))
    check("стоп", "Слушаюсь, сэр.", reply("стоп"))
    check("спасибо", "Всегда пожалуйста, сэр.", reply("спасибо"))
    check("кто ты", true, reply("кто ты")?.startsWith("Я — Джарвис"))
    check("помощь", true, reply("что ты умеешь")?.startsWith("Умею, сэр"))

    println("== Помощь по темам ==")
    check("тема «конвертер»", true, reply("команды конвертера")?.startsWith("Конвертер понимает"))
    check("тема «калькулятор»", true, reply("какие команды для расчётов")?.startsWith("Считать умею"))
    check("тема «телефон»", true, reply("что умеешь по телефону")?.startsWith("Команды для телефона"))
    check("тема «календарь»", true, reply("какие команды про календарь и время")?.startsWith("Календарь и время"))
    check("тема «развлечения»", true, reply("какие команды для развлечений")?.startsWith("Развлечения"))
    check("тема «текст»", true, reply("какие команды для текста")?.startsWith("Команды для текста"))
    check("тема «списки»", true, reply("какие команды для списков")?.startsWith("Списки и заметки"))
    check("тема «валюты»", true, reply("какие команды про курсы валют")?.startsWith("Курсы валют"))

    println("== Считать ==")
    check("калькулятор", "Будет 84.", reply("сколько будет 12 умножить на 7"))
    check("проценты", "Будет 30.", reply("20 процентов от 150"))
    check("корень", "Будет 12.", reply("корень из 144"))
    check("чаевые раньше арифметики", "Чаевые 10% от 3500 — это 350. Итого 3850.",
        reply("чаевые 10 процентов от 3500"))
    check("счёт на троих", "На каждого из 3 — 3000.", reply("раздели 9000 на троих"))
    check("обычное деление осталось арифметикой", "Будет 25.", reply("раздели 100 на 4"))
    check("скидка", "Со скидкой 20% — 4000, экономия 1000.", reply("скидка 20 процентов с 5000"))
    check("римские", "1994 римскими — MCMXCIV.", reply("1994 римскими"))
    check("среднее", "Среднее из 3 чисел — 5.", reply("среднее 4 5 6"))

    println("== Конвертер ==")
    check("км в мили", "5 км — это 3,1069 миль.", reply("5 км в милях"))
    check("сколько метров в 5 км", "5 км — это 5000 м.", reply("сколько метров в 5 километрах"))
    check("температура", "100 °F — это 37,7778 °C.", reply("100 градусов фаренгейта в цельсиях"))
    check("данные", "2 ГБ — это 2048 МБ.", reply("2 гб в мб"))
    check("время", "3 дн. — это 72 ч.", reply("3 дня в часах"))

    println("== Календарь и время ==")
    check("високосный", "2028 — високосный, в нём 366 дней.", reply("високосный ли 2028 год"))
    check("секунды в сутках", "В сутках 86400 секунд, сэр.", reply("сколько секунд в сутках"))
    check("30 лет в днях", "30 лет — это примерно 10957 дн., сэр.", reply("мне 30 лет сколько это дней"))
    check("до конца года", true, reply("сколько дней до конца года")?.startsWith("До конца года"))
    check("до даты", true, reply("сколько дней до 31 декабря")?.startsWith("До 31 декабря"))
    check("день недели", "1 января 2030 — это вторник, сэр.", reply("какой день недели 1 января 2030"))

    println("== Текст и развлечения ==")
    check("слова", "В этой фразе 3 слова.", reply("сколько слов в тексте раз два три"))
    check("морзе", "... --- ... /", reply("морзе сос"))
    check("транслит", "«privet»", reply("транслит привет"))
    check("переверни", "«тевирп»", reply("переверни слово привет"))
    check("монетка", true, reply("подбрось монетку") in listOf("Орёл, сэр.", "Решка, сэр."))
    check("выбор", true, reply("выбери между чаем и кофе")?.startsWith("Выбираю: "))
    check("загадка", true, reply("загадай загадку")?.endsWith("если сдаётесь."))
    check("ответ на загадку", true, reply("ответ на загадку")?.startsWith("Ответ: "))
    check("пароль", true, reply("придумай пароль на 8 символов")?.length == "Пароль: ".length + 8)

    println("== Сетевые команды дают плашку ожидания ==")
    check("курс валют", "Смотрю курс, сэр…", reply("курс доллара"))
    check("курс с суммой", true, exec("100 долларов в тенге")?.async != null)
    check("погода", "Смотрю прогноз, сэр…", reply("какая погода"))
    check("погода с городом", true, exec("погода в Сочи")?.async != null)

    println("== Провайдер ИИ ==")
    // «какой у тебя провайдер» и «смени провайдера» трогают Prefs и Intent —
    // на JVM это «Stub!», поэтому проверяем только то, что реально исполняется:
    // справку и то, что новый раздел не перехватил чужие команды.
    check("справка знает про провайдера", true,
        reply("что ты умеешь")?.contains("смени провайдера"))
    check("«какая модель телефона» — про устройство", true,
        reply("какая модель телефона")?.contains("Android"))

    println("== Шумоподавление (чистый разбор команд) ==")
    // Сама команда трогает Prefs и аудиоэффекты (на JVM — «Stub!»), поэтому
    // тестируем разбор фразы; он должен отличать приказ от вопроса и не
    // перехватывать чужие команды.
    check("включить", true, CommandEngine.noiseRequest("включи шумоподавление"))
    check("выключить", false, CommandEngine.noiseRequest("выключи шумоподавление"))
    check("врубить (разговорное)", true, CommandEngine.noiseRequest("вруби шумодав"))
    check("вопрос о состоянии — не приказ", null, CommandEngine.noiseRequest("шумоподавление включено"))
    check("чужая команда не перехвачена", null, CommandEngine.noiseRequest("включи музыку"))
    check("команда службы не перехвачена", null, CommandEngine.noiseRequest("выключи прослушивание"))
    check("тост-подсказка знает про шумоподавление", true,
        CommandEngine.help("system")?.contains("шумоподавление"))

    println("== Прочее ==")
    // Команды, которые возвращают Intent (перевод, поиск, запуск окон), здесь
    // не проверить: android.jar — заглушка, конструктор Intent бросает «Stub!».
    // Их покрывает сборка APK: scripts/build-apk.sh.
    check("не команда — уйдёт в Gemini", null, exec("расскажи про квантовую гравитацию"))
    check("болтовня — уйдёт в Gemini", null, exec("что думаешь о будущем человечества"))
    // регрессия: «скачай видео» не должно улетать в Play Market
    check("маркет не перехватывает медиа", null, exec("скачай видео"))

    println("")
    println("пройдено: $passed, провалено: $failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
