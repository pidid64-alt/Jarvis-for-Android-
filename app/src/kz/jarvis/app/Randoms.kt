package kz.jarvis.app

import java.util.Locale
import kotlin.random.Random

/**
 * Команды-развлечения и генераторы: монетка, кубики, выбор из списка,
 * «да или нет», камень-ножницы-бумага, магический шар, анекдот, факт,
 * скороговорка, загадка, комплимент, пароль и случайный код.
 *
 * Генератор случайных чисел передаётся параметром, поэтому
 * scripts/test-logic.sh проверяет ответы детерминированно.
 */
object Randoms {

    /** Ответ на фразу либо null. */
    fun ask(raw: String, rnd: Random = Random.Default): String? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        if (s.isEmpty()) return null

        if (Regex("монет|орел или решка|орёл или решка").containsMatchIn(s)) {
            return if (rnd.nextBoolean()) "Орёл, сэр." else "Решка, сэр."
        }

        Regex("(\\d+)\\s*(кубик[а-яa-z0-9]*|кост[а-яa-z0-9]*)|кубик[а-яa-z0-9]*\\s*(\\d+)|(?:брось|кинь|кинуть)\\s+(\\d+)").find(s)?.let { m ->
            val n = m.groupValues.filterIndexed { i, _ -> i > 0 }.firstOrNull { it.isNotBlank() }
                ?.toIntOrNull() ?: 1
            return dice(n.coerceIn(1, 20), 6, rnd)
        }
        Regex("кубик\\D{0,20}(\\d+)\\s*гран|гран[а-яa-z0-9]+\\s*(\\d+)|d(\\d{1,3})\\b").find(s)?.let { m ->
            val sides = m.groupValues.filterIndexed { i, _ -> i > 0 }.firstOrNull { it.isNotBlank() }
                ?.toIntOrNull() ?: 6
            return dice(1, sides.coerceIn(2, 1000), rnd)
        }
        if (Regex("кубик|кост[а-яa-z0-9]*|кости").containsMatchIn(s)) return dice(1, 6, rnd)

        Regex("(?:случайное|рандомное)\\s+число\\D{0,10}(\\d+)\\D{0,6}(\\d+)").find(s)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            if (a < b) return "Ваше число: ${rnd.nextInt(a, b + 1)}."
        }

        if (Regex("правда или ложь|ложь или правда").containsMatchIn(s)) {
            return if (rnd.nextBoolean()) "Правда, сэр." else "Ложь, сэр."
        }
        if (Regex("да или нет|ответь да или нет").containsMatchIn(s)) {
            return if (rnd.nextBoolean()) "Да, сэр." else "Нет, сэр."
        }
        if (Regex("камень.{0,3}ножниц|ножниц.{0,3}бумаг").containsMatchIn(s)) {
            return when (rnd.nextInt(3)) {
                0 -> "Камень, сэр."
                1 -> "Ножницы, сэр."
                else -> "Бумага, сэр."
            }
        }
        if (Regex("магическ[а-яa-z0-9]+ шар|предсказан|что меня жд").containsMatchIn(s)) {
            return ball[rnd.nextInt(ball.size)]
        }

        if (Regex("анекдот|шутк|пошути|смешн").containsMatchIn(s)) {
            return jokes[rnd.nextInt(jokes.size)]
        }
        if (Regex("цитат|мудрост|мотиваци|подбодр|вдохнов").containsMatchIn(s)) {
            return quotes[rnd.nextInt(quotes.size)]
        }
        if (Regex("комплимент|похвали|что-нибудь приятное|скажи приятное").containsMatchIn(s)) {
            return compliments[rnd.nextInt(compliments.size)]
        }
        if (Regex("факт|интересн[а-яa-z0-9]+ (?:это|знать)|знал ли ты").containsMatchIn(s)) {
            return facts[rnd.nextInt(facts.size)]
        }
        if (Regex("скороговорк").containsMatchIn(s)) {
            return tongues[rnd.nextInt(tongues.size)]
        }
        if (Regex("ответ на загадку|какой ответ|отгадка|ответ загадки").containsMatchIn(s)) {
            return if (lastRiddle < 0) "Сначала загадайте загадку, сэр."
            else "Ответ: ${riddles[lastRiddle].second}."
        }
        if (Regex("загадк|загадай").containsMatchIn(s)) {
            lastRiddle = rnd.nextInt(riddles.size)
            return riddles[lastRiddle].first + " Скажите «ответ на загадку», если сдаётесь."
        }

        if (Regex("пароль|пассворд").containsMatchIn(s)) {
            val len = Regex("(\\d+)\\s*символ").find(s)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(6, 32) ?: 12
            return "Пароль: " + password(len, rnd)
        }
        if (Regex("(?:случайный|случайные)\\s+код|код из (\\d+)|пин-?код").containsMatchIn(s)) {
            val len = Regex("код из (\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(3, 8) ?: 4
            var out = ""
            repeat(len) { out += rnd.nextInt(10) }
            return "Ваш код: $out."
        }

        if (Regex("выбери|выбрать|кого выбрать|что выбрать|жребий|тяни").containsMatchIn(s)) {
            val list = options(s)
            if (list.size >= 2) return "Выбираю: ${list[rnd.nextInt(list.size)]}."
        }

        return null
    }

    /** Разбирает «выбери между чаем и кофе», «выбери из: чай, кофе, сок». */
    fun options(s: String): List<String> {
        var body = s
        Regex("^(?:джарвис\\s+)?(?:ну-ка\\s+)?(?:выбери|выбрать|тяни жребий|жребий)(?:\\s+мне)?" +
            "(?:\\s+между|\\s+из|\\s+одно из|\\s+кого|\\s+что)?\\s*:?\\s*").find(body)?.let {
            body = body.substring(it.range.last + 1)
        }
        body = body.replace("либо", ",")
        return body.split(Regex("[,;]|\\s+и\\s+|\\s+или\\s+"))
            .map { it.trim().trim('.', ',', ' ', ':') }
            .filter { it.length >= 2 }
    }

    fun dice(count: Int, sides: Int, rnd: Random): String {
        val rolls = List(count.coerceIn(1, 20)) { rnd.nextInt(1, sides + 1) }
        return if (rolls.size == 1) {
            "Выпало ${rolls[0]}, сэр."
        } else {
            "Выпало: ${rolls.joinToString(" + ")} = ${rolls.sum()}."
        }
    }

    private fun password(len: Int, rnd: Random): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#\$%&*"
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private var lastRiddle = -1

    private val jokes = listOf(
        "Программист ставит на ночь два стакана: один с водой — если захочет пить, и один пустой — если не захочет.",
        "Сэр, я не ленивый. Я просто в режиме энергосбережения.",
        "Заходит байт в бар, а бармен ему: «Тебе чего?» — «Пару бит, и я в кэш».",
        "Железный человек сказал: «Джарвис, решай сам». С тех пор у меня автономный режим.",
        "Лучший способ ускорить телефон — положить его в холодильник и пойти гулять.",
        "— Сколько программистов нужно, чтобы вкрутить лампочку? — Ни одного, это аппаратная проблема.",
        "Мой код работает, и я не знаю почему. Мой код не работает, и я не знаю почему. Баланс, сэр."
    )

    private val quotes = listOf(
        "«Иногда надо просто запустить ракету» — Илон Маск.",
        "«Лучший способ предсказать будущее — изобрести его» — Алан Кей.",
        "«Простота — высшая форма утончённости» — Леонардо да Винчи.",
        "«Делай, что можешь, с тем, что имеешь, там, где ты есть» — Теодор Рузвельт.",
        "«Гений — это один процент вдохновения и девяносто девять пота» — Томас Эдисон.",
        "«Единственный способ делать великие дела — любить то, что делаешь» — Стив Джобс."
    )

    private val compliments = listOf(
        "Сэр, с вами даже мой процессор работает быстрее.",
        "Вы разбираетесь в командах лучше, чем я — в людях.",
        "Отличный выбор ассистента, сэр. И отличный вы.",
        "Ваша продуктивность зашкаливает — я еле успеваю."
    )

    private val facts = listOf(
        "Знаете ли вы: осьминог имеет три сердца и голубую кровь.",
        "Мёд практически не портится: в египетских гробницах находили съедобный мёд возрастом три тысячи лет.",
        "Свет от Солнца доходит до Земли примерно за 8 минут 20 секунд.",
        "В среднем человек делает около двадцати тысяч вдохов в сутки.",
        "Первый компьютерный «баг» — настоящий жучок, извлечённый из реле в 1947 году.",
        "Скорость чихания — примерно 160 километров в час."
    )

    private val tongues = listOf(
        "Шла Саша по шоссе и сосала сушку.",
        "Ехал Грека через реку, видит Грека — в реке рак.",
        "На дворе трава, на траве дрова — не руби дрова на траве двора.",
        "Карл у Клары украл кораллы, а Клара у Карла украла кларнет."
    )

    private val ball = listOf(
        "Определённо да, сэр.",
        "Звёзды говорят — да.",
        "Скорее нет, чем да.",
        "Спросите позже, сейчас данные противоречивы.",
        "Все признаки указывают на да.",
        "Мой прогноз — нет, но решение за вами.",
        "Да, но не сегодня."
    )

    private val riddles = listOf(
        "Висит груша — нельзя скушать. Что это?" to "Лампочка",
        "Чем больше из неё берёшь, тем больше она становится. Что это?" to "Яма",
        "Всегда идёт, а с места не тронется. Что это?" to "Часы",
        "Что можно приготовить, но нельзя съесть?" to "Уроки",
        "Без рук, без топора построен мост. Что это?" to "Лёд на реке"
    )

    fun help(): String = """
        Развлечения и генераторы, сэр:
        • «подбрось монетку», «кинь кубик», «брось 3 кубика», «кубик на 20 граней»
        • «случайное число от 1 до 100», «да или нет», «правда или ложь»
        • «выбери между чаем и кофе», «камень ножницы бумага», «магический шар»
        • «расскажи анекдот», «цитата», «интересный факт», «скороговорка»
        • «загадай загадку» → «ответ на загадку»
        • «комплимент», «придумай пароль», «случайный код из 6 цифр»
    """.trimIndent()
}
