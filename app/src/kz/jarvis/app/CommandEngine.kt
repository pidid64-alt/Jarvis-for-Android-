package kz.jarvis.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Офлайн-движок команд: парсит фразу и управляет телефоном.
 * Возвращает null, если команда не распознана — тогда фраза уходит в Gemini.
 *
 * Работает от обычного [Context], поэтому команды выполняет и MainActivity,
 * и фоновая WakeWordService (когда приложение закрыто).
 */
object CommandEngine {

    /**
     * @param reply          что ответить (или плашка «Думаю…», если задан [async])
     * @param launch         Intent, который нужно запустить (приложению или службе)
     * @param stopTts        прервать озвучку
     * @param silent         не озвучивать ответ
     * @param async          долгая операция (сеть): (контекст, колбэк с готовым ответом)
     * @param sleepMinutes   усыпить фоновое прослушивание на N минут
     * @param stopWake       выключить фоновое прослушивание
     * @param openApp        открыть окно приложения
     */
    data class Outcome(
        val reply: String,
        val launch: Intent? = null,
        val stopTts: Boolean = false,
        val silent: Boolean = false,
        val async: ((Context, (String) -> Unit) -> Unit)? = null,
        val sleepMinutes: Int = 0,
        val stopWake: Boolean = false,
        val openApp: Boolean = false,
        val endDialog: Boolean = false
    )

    private var torchOn = false

    // ------------------------------------------------------------------ вход

    fun normalize(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT).replace('ё', 'е')
        val cleaned = lower
            .replace(Regex("[^\\p{L}\\p{N}\\s+\\-*/^()=%.,:@]"), " ")
            // диктовка адреса: «почта вася собака mail.ru»
            .replace(Regex("\\s+собака\\s+"), "@")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '!', '?', ',', ' ')
        val noWake = WakeWords.removeFromText(cleaned)
        return noWake.ifBlank { cleaned }
    }

    fun execute(ctx: Context, raw: String): Outcome? {
        val s = normalize(raw)
        if (s.isEmpty()) return null

        // ---------- стоп / тишина ----------
        if (Regex("^(стоп|тихо|замолчи|отмена|хватит|прекрати|отстань|достаточно|тишина)$").matches(s)) {
            return Outcome("Слушаюсь, сэр.", stopTts = true, silent = true)
        }

        greeting(s)?.let { return it }

        // «что ты умеешь», «помощь», «команды конвертера», «что умеешь по телефону»…
        if (Regex("что (?:ты )?умеешь|что (?:ты )?можешь|что можно сказать|помощь|справк|^help$|команд[а-я]*")
                .containsMatchIn(s)
        ) {
            return Outcome(help(topic(s)))
        }

        // ---------- настройки ИИ: провайдер, ключ, модель ----------
        aiSettings(ctx, s)?.let { return it }

        // ---------- управление самой службой ----------
        serviceCmd(ctx, s)?.let { return it }

        // ---------- голос Джарвиса ----------
        voice(ctx, s)?.let { return it }

        // ---------- изучение темы: сначала уточняем формат ----------
        study(ctx, s)?.let { return it }

        // ---------- поиск в интернете, Клод и презентации ----------
        research(ctx, s)?.let { return it }

        // ---------- напоминания / будильники / таймеры ----------
        reminder(ctx, s)?.let { return it }
        alarmOrTimer(ctx, s)?.let { return it }

        // ---------- бытовые расчёты (до арифметики: там свои глаголы) ----------
        life(s)?.let { return it }

        // ---------- конвертер и калькулятор ----------
        convert(s)?.let { return it }
        math(s)?.let { return it }

        // ---------- курсы валют и погода (сеть) ----------
        rates(s)?.let { return it }
        weather(s)?.let { return it }

        // ---------- календарь, время и дата ----------
        dateFacts(s)?.let { return it }
        timeDate(s)?.let { return it }

        // ---------- состояние телефона ----------
        deviceInfo(ctx, s)?.let { return it }
        SystemCommands.deviceFacts(ctx, s)?.let { return it }

        // ---------- фонарик ----------
        torch(ctx, s)?.let { return it }

        // ---------- громкость, звук, плеер, экран ----------
        volume(ctx, s)?.let { return it }
        SystemCommands.media(ctx, s)?.let { return it }
        SystemCommands.screen(ctx, s)?.let { return it }
        mediaApp(ctx, s)?.let { return it }
        music(ctx, s)?.let { return it }
        player(ctx, s)?.let { return it }

        // ---------- сети и системные настройки ----------
        SystemCommands.panels(ctx, s)?.let { return it }
        systemPanel(ctx, s)?.let { return it }

        // ---------- магазины приложений ----------
        SystemCommands.market(ctx, s)?.let { return it }

        // ---------- списки покупок и заметки ----------
        lists(ctx, s)?.let { return it }

        // ---------- связь ----------
        waSend(ctx, s)?.let { return it }
        sms(ctx, s)?.let { return it }
        SystemCommands.share(ctx, s)?.let { return it }
        call(ctx, s)?.let { return it }
        SystemCommands.callLog(ctx, s)?.let { return it }

        // ---------- карта ----------
        nav(ctx, s)?.let { return it }

        // ---------- интернет ----------
        web(ctx, s)?.let { return it }

        // ---------- камера ----------
        camera(ctx, s)?.let { return it }

        // ---------- работа с текстом ----------
        TextTools.ask(s)?.let { return Outcome(it) }

        // ---------- всякое: монетка, кубик, анекдот, пароль… ----------
        Randoms.ask(s)?.let { return Outcome(it) }

        // ---------- «домой» ----------
        if (Regex("^(домой|на главный экран|главный экран|рабочий стол|на домашний экран)$").matches(s)) {
            return Outcome("Возвращаю на главный экран.", Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).withTask(ctx))
        }

        // ---------- открыть приложение (самое общее — в конце) ----------
        openCmd(ctx, s)?.let { return it }

        return null // не понял — отдаём ИИ
    }

    // ------------------------------------------------------------ голос Джарвиса

    /** «голос брони», «говори ниже», «проверь голос», «какие есть голоса». */
    private fun voice(ctx: Context, s: String): Outcome? {
        val cmd = VoiceProfile.parse(s) ?: return null
        val reply = Voice.run(ctx, cmd)
        if (cmd === VoiceProfile.Cmd.Settings) {
            return Outcome(reply, Intent(ctx, SettingsActivity::class.java).withTask(ctx))
        }
        return Outcome(reply)
    }

    // ------------------------------------------ изучение темы

    /**
     * «Помоги изучить тему X» — не запускает длинный ответ вслепую: сначала
     * предлагает рассказ или видео. Выбор хранится, поэтому следующую фразу
     * («расскажи»/«включи видео») можно сказать голосом без повторения темы.
     */
    private fun study(ctx: Context, s: String): Outcome? {
        val pending = Prefs.pendingStudyTopic(ctx)
        if (pending.isNotBlank()) {
            val video = Regex("видео|ролик|ютуб|youtube|посмотрим|покажи").containsMatchIn(s)
            val tell = Regex("расскаж|объясн|текст|устно|сам").containsMatchIn(s)
            if (video || tell) {
                Prefs.clearPendingStudyTopic(ctx)
                if (video) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + q(pending)))
                        .withTask(ctx)
                    return Outcome("Включаю видео по теме «$pending».", intent, endDialog = true)
                }
                val plan = ResearchPlan.Plan(ResearchPlan.Kind.RESEARCH, pending)
                return Outcome("Готовлю понятное объяснение темы «$pending».", async = { c, done ->
                    done(Research.handle(c, plan))
                })
            }
        }
        val m = Regex("^(?:помоги|давай)\\s+(?:мне\\s+)?(?:изучить|выучить|разобраться\\s+в)\\s+(?:тему\\s+)?(.+)$")
            .find(s) ?: return null
        val topic = m.groupValues[1].trim().trim('.', ',', '!', '?')
        if (topic.isBlank()) return Outcome("Какую тему изучаем, сэр?")
        Prefs.setPendingStudyTopic(ctx, topic)
        return Outcome("Конечно. По теме «$topic» рассказать или включить видео?")
    }

    // ------------------------------------------ поиск в интернете и презентации

    /**
     * «Поищи инфу про X и сделай презентацию», «сделай доклад про Y на 10 слайдов»,
     * «спроси у Клода Z», «открой презентацию».
     *
     * Долгая часть (поиск + Клод + файл) уходит в [Outcome.async]; пока она идёт,
     * Джарвис проговаривает первую фразу — [Outcome.reply].
     */
    private fun research(ctx: Context, s: String): Outcome? {
        val plan = ResearchPlan.parse(s) ?: return null

        if (plan.kind == ResearchPlan.Kind.OPEN_LAST) {
            return Outcome(DeckFile.openLast(ctx))
        }
        if (plan.topic.isBlank() && plan.kind == ResearchPlan.Kind.PRESENTATION) {
            return Outcome(
                "Про что делать презентацию, сэр? Скажите: «поищи инфу про Марс и сделай презентацию»."
            )
        }
        if (plan.topic.isBlank() && plan.kind == ResearchPlan.Kind.RESEARCH) {
            return Outcome("Что именно поискать, сэр?")
        }

        val hello = when (plan.kind) {
            ResearchPlan.Kind.PRESENTATION ->
                "Принято, сэр. Ищу материал по теме «${plan.topic}», отдаю Клоду и собираю " +
                    "${plan.slides} слайдов. Это займёт около минуты."
            ResearchPlan.Kind.RESEARCH ->
                "Ищу в интернете: «${plan.topic}», сэр. Минуту."
            ResearchPlan.Kind.CLAUDE ->
                if (plan.topic.isBlank()) "Открываю Клод, сэр." else "Передаю Клоду, сэр."
            else -> "Работаю, сэр."
        }
        return Outcome(hello, async = { c, done -> done(Research.handle(c, plan)) })
    }

    /** Определяет тему справки: «команды конвертера», «что умеешь по телефону»… */
    private fun topic(s: String): String? = when {
        Regex("конверт|перевод величин|единиц").containsMatchIn(s) -> "units"
        Regex("валют|курс").containsMatchIn(s) -> "rates"
        Regex("посч|калькуля|математ|расч|арифметик").containsMatchIn(s) -> "math"
        Regex("телефон|устройств|системн|настро").containsMatchIn(s) -> "system"
        Regex("календар|дата|время|дн").containsMatchIn(s) -> "dates"
        Regex("развлеч|шутк|игр|случа|анекдот").containsMatchIn(s) -> "fun"
        Regex("текст|слов|букв|морзе").containsMatchIn(s) -> "text"
        Regex("списк|покупк|заметк").containsMatchIn(s) -> "lists"
        Regex("голос|тембр|озвуч").containsMatchIn(s) -> "voice"
        Regex("презентац|доклад|реферат|поиск инфы|клод|исследован").containsMatchIn(s) -> "research"
        else -> null
    }

    /** Полный список того, что понимает движок (для «что ты умеешь»). */
    fun help(t: String? = null): String = when (t) {
        "units" -> Units.help()
        "rates" -> Rates.help()
        "math" -> mathHelp()
        "system" -> SystemCommands.help()
        "dates" -> DateFacts.help()
        "fun" -> Randoms.help()
        "text" -> TextTools.help()
        "lists" -> listsHelp()
        "voice" -> voiceHelp()
        "research" -> researchHelp()
        else -> """
            Умею, сэр:
            • «включи/выключи фонарик», «фонарик на 10 секунд», «громче/тише», «громкость 40 процентов»
            • «яркость 30 процентов», «сделай ярче», «громкость звонка 50 процентов»
            • «пауза», «следующий трек», «перемотай вперёд», «включи музыку <название>»
            • «открой ютуб/телеграм/камеру/настройки…», «установи приложение шахматы», «удали приложение …»
            • «позвони маме», «напиши папе что задержусь», «отправь письмо на …», «кто звонил последним»
            • «будильник на 7:30», «таймер на 5 минут», «напомни через 20 минут выключить духовку»
            • «добавь в список покупок молоко», «что в списке покупок», «запиши заметку …»
            • «сколько будет 12 умножить на 7», «20 процентов от 150», «корень из 144»
            • «5 км в милях», «100 фаренгейта в цельсиях», «2 гб в мб», «чаевые 10 процентов от 3500»
            • «курс доллара», «100 долларов в тенге», «какая погода», «погода в Сочи»
            • «сколько дней до 31 декабря», «какой день недели 1 января 2030», «который час», «время в Лондоне»
            • «сколько заряда», «сколько памяти», «какой у меня ip», «какой оператор», «разрешение экрана»
            • «включи/выключи шумоподавление», «шумоподавление включено?»
            • «маршрут до дома», «найди пиццу», «переведи hello», «новости», «википедия физика»
            • «сколько слов в тексте …», «переверни фразу …», «морзе привет», «транслит привет»
            • «подбрось монетку», «выбери между чаем и кофе», «загадай загадку», «придумай пароль»
            • «поищи инфу про Марс и сделай презентацию», «сделай доклад про ИИ на 10 слайдов»
            • «спроси у Клода как настроить роутер», «отправь это в Клод», «открой презентацию»
            • «голос Джарвиса», «голос брони», «женский голос», «говори ниже», «проверь голос»
            • «какой у тебя провайдер», «смени провайдера», «настройки Джарвиса»
            • «включи непрерывный диалог» — говорить подряд, без повторного «Джарвис»
            • «включи автоответ» — сам отвечу в WhatsApp/Telegram; «добавь правило: никогда не отвечай маме»
            • «спи 10 минут», «стоп», «что ты умеешь»
            Подробности по темам: «команды конвертера», «команды телефона», «календарные команды»,
            «команды для текста», «команды-развлечения», «команды списков», «курсы валют»,
            «команды голоса», «команды презентаций».
            На всё остальное отвечу своими словами — провайдера ИИ (Gemini, OpenAI,
            OpenRouter, DeepSeek, Groq, Mistral, Claude или локальную Ollama) выбираете
            сами: «смени провайдера».
        """.trimIndent()
    }

    private fun mathHelp(): String = """
        Считать умею, сэр:
        • «сколько будет 12 умножить на 7», «2+2*3», «(12+8)/4»
        • «20 процентов от 150», «сколько процентов составляет 20 от 200»
        • «корень из 144», «5 в квадрате», «2 в степени 10»
        • «отними 3 от 100», «прибавь 5 к 10», «раздели 100 на 3»
        • «мой вес 80 рост 180» (ИМТ), «чаевые 10 процентов от 3500»
        • «раздели 9000 на троих», «скидка 20 процентов с 5000»
        • «площадь комнаты 3 на 4», «площадь круга радиус 5», «среднее 4 5 6»
        • «факториал 5», «нод 12 и 18», «1994 римскими»
    """.trimIndent()

    private fun voiceHelp(): String = """
        Голос, сэр:
        • «голос Джарвиса» — низкий и спокойный (стоит по умолчанию)
        • «голос брони» — самый глубокий, с эхом; «деловой голос» — быстрый доклад
        • «женский голос» — Пятница; «обычный голос» — системный, без обработки
        • «говори ниже», «говори выше», «говори быстрее», «говори медленнее»
        • «сбрось голос» — вернуть настройки профиля
        • «проверь голос» — произнесу пробную фразу; «какой у тебя голос» — расскажу
        • «настройки голоса» — открою экран, где можно выбрать конкретный голос телефона
    """.trimIndent()

    private fun researchHelp(): String = """
        Поиск и презентации, сэр:
        • «поищи инфу про квантовые компьютеры и сделай презентацию»
        • «сделай презентацию про Марс на 12 слайдов», «подготовь доклад о нейросетях»
        • «поищи информацию про рынок кофе» — найду и перескажу голосом
        • «спроси у Клода как починить кран», «отправь это в Клод»
        • «открой презентацию» — покажу последнюю
        Как это работает: ищу в интернете (Википедия и DuckDuckGo, без ключей),
        собираю выжимку с источниками, отдаю её Клоду (по ключу Anthropic — сразу
        через API; без ключа — открываю приложение Claude и вставляю материал),
        а слайды сохраняю в «Загрузки/Jarvis» файлами .html и .md.
    """.trimIndent()

    private fun listsHelp(): String = """
        Списки и заметки (работают без интернета), сэр:
        • «добавь в список покупок молоко, хлеб»
        • «что в списке покупок», «список покупок»
        • «удали молоко из списка покупок», «очисти список покупок»
        • «запиши заметку купить подарок», «мои заметки»
        • «удали заметку подарок», «очисти заметки»
    """.trimIndent()


    // =========================================================== разделы

    private fun greeting(s: String): Outcome? = when {
        Regex("^(привет|приветик|здравствуй|здравствуйте|хай|здорово|доброе утро|добрый день|добрый вечер|доброе утро сэр)$")
            .matches(s) -> Outcome("Здравствуйте, сэр. Чем могу помочь?")
        Regex("^(как дела|как ты|как жизнь|как сам|как настроение)$")
            .matches(s) -> Outcome("Все системы в норме, сэр. Готов к работе.")
        Regex("^(спасибо|благодарю|спс|мерси)$")
            .matches(s) -> Outcome("Всегда пожалуйста, сэр.")
        Regex("^(пока|до свидания|прощай|отбой|до встречи)$")
            .matches(s) -> Outcome("До связи, сэр.")
        Regex("^(кто ты|кто ты такой|кто тебя создал|что ты за|ты кто|представься)$")
            .matches(s) -> Outcome("Я — Джарвис, ваш голосовой ассистент. Живу прямо в телефоне, сэр.")
        Regex("^(молодец|отлично|хорошо|круто|супер)$")
            .matches(s) -> Outcome("Стараюсь, сэр.")
        else -> null
    }

    // ------------------------------------------------ настройки провайдера ИИ

    /**
     * «какой у тебя провайдер», «какая модель», «смени провайдера»,
     * «открой настройки Джарвиса». Работает офлайн — ничего не качает.
     */
    private fun aiSettings(ctx: Context, s: String): Outcome? {
        // «открой настройки Джарвиса», «смени провайдера», «настройки приложения»
        val wantOpen = Regex(
            "настройк[а-яa-z0-9]*\\s*(?:джарвиса|джарвис|приложения|джарвиса ии)|" +
                "(?:смени|поменяй|выбери|измени|подключи|настрой)\\s+(?:нового\\s+)?(?:ии[-\\s]?)?провайдера|" +
                "провайдера\\s+(?:ии|для\\s+джарвиса)|ключ\\s+(?:джарвиса|ии)"
        ).containsMatchIn(s)
        if (wantOpen) {
            return Outcome(
                "Открываю настройки, сэр. Там можно выбрать провайдера ИИ, вписать ключ и модель.",
                Intent(ctx, SettingsActivity::class.java).withTask(ctx)
            )
        }

        // «какой у тебя провайдер», «какая у тебя модель», «кто отвечает на вопросы»
        // «модель» — только про ИИ: «какая модель телефона» остаётся устройством
        val wantInfo = Regex(
            "(?:какой|кто)\\s+(?:у\\s+тебя\\s+)?(?:сейчас\\s+)?(?:ии[-\\s]?)?провайдера?|" +
                "какая\\s+(?:у\\s+тебя\\s+)?(?:сейчас\\s+)?модель" +
                "(?:\\s+(?:ии|нейросет[а-яa-z0-9]*|джарвиса))?\\s*$|" +
                "кто\\s+отвечает\\s+на\\s+вопросы|" +
                "через\\s+(?:какой|какого)\\s+(?:ии|сервис|провайдер)"
        ).containsMatchIn(s)
        if (wantInfo) {
            val p = Prefs.provider(ctx)
            val model = Prefs.model(ctx, p)
            val url = Prefs.baseUrl(ctx, p)
            val key = if (p.keyOptional) "ключ не требуется"
            else if (Prefs.apiKey(ctx, p).isEmpty()) "ключа пока нет — впишите его в настройках"
            else "ключ указан"
            return Outcome("Сейчас на вопросы отвечает «${p.name}», модель $model, $key. Адрес: $url.")
        }
        return null
    }

    private fun serviceCmd(ctx: Context, s: String): Outcome? {
        noiseCmd(ctx, s)?.let { return it }
        dialogCmd(ctx, s)?.let { return it }
        autoReplyCmd(ctx, s)?.let { return it }
        autoSendCmd(ctx, s)?.let { return it }

        Regex("(?:спи|спать|помолчи|отдохни|пауза|замолчи)\\s+(\\d+)\\s*(секунд[а-яa-z0-9]*|минут[а-яa-z0-9]*|час[а-яa-z0-9]*)?")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@let
                val minutes = when {
                    m.groupValues[2].startsWith("час") -> n * 60
                    m.groupValues[2].startsWith("секунд") -> 1
                    else -> n
                }
                val until = SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(Date(System.currentTimeMillis() + minutes * 60_000L))
                return Outcome(
                    "Хорошо, сэр. Не слушаю до $until.",
                    sleepMinutes = minutes.coerceIn(1, 480)
                )
            }
        if (Regex("отключи голосовую активацию|выключи прослушивание|перестань слушать|выключи джарвиса|отключись совсем")
                .containsMatchIn(s)
        ) {
            return Outcome(
                "Прослушивание выключено, сэр. Включить можно кнопкой микрофона в приложении.",
                stopWake = true, silent = true
            )
        }
        return null
    }

    // ------------------------------------------------------ шумоподавление

    /**
     * Чистый разбор команды про шумоподавление (без Android — тестируется на JVM):
     * true — «включи», false — «выключи», null — фраза не про шумоподавление
     * или это вопрос о состоянии, а не приказ.
     */
    fun noiseRequest(s: String): Boolean? {
        if (!Regex("шумоподавл|шумоподавитель|шумодав").containsMatchIn(s)) return null
        // «шумоподавление включено?», «какое шумоподавление» — вопрос, не приказ
        if (Regex("включен|выключен|статус|состояние|работает|какое|какая|как сейчас").containsMatchIn(s)) {
            return null
        }
        val on = Regex("включ|вруб|актив|поставь").containsMatchIn(s)
        val off = Regex("выключ|отключ|выруб|убер|сними").containsMatchIn(s)
        if (on == off) return null
        return on
    }

    /** Голосовое управление шумоподавлением микрофона. */
    private fun noiseCmd(ctx: Context, s: String): Outcome? {
        if (!Regex("шумоподавл|шумоподавитель|шумодав").containsMatchIn(s)) return null

        noiseRequest(s)?.let { want ->
            Prefs.setNoiseSuppress(ctx, want)
            AudioFx.sync(ctx)
            return Outcome(
                when {
                    want && AudioFx.activeNames().isNotEmpty() ->
                        "Шумоподавление микрофона включено, сэр. Работают: " +
                            AudioFx.activeNames().joinToString(", ") + "."
                    want ->
                        "Включил, сэр, но прошивка не отдаёт аппаратное шумоподавление — " +
                            "на этом телефоне его нет."
                    else -> "Шумоподавление микрофона выключено, сэр."
                }
            )
        }

        // вопрос о состоянии
        if (Regex("включен|выключен|статус|состояние|работает|какое|какая|как сейчас").containsMatchIn(s)) {
            return Outcome(
                if (Prefs.noiseSuppress(ctx)) "Шумоподавление сейчас включено, сэр."
                else "Шумоподавление сейчас выключено, сэр."
            )
        }

        // «что такое шумоподавление» — пусть отвечает ИИ
        if (Regex("что такое|расскажи про|объясни|почему").containsMatchIn(s)) return null

        return Outcome("Скажите «включи шумоподавление» или «выключи шумоподавление», сэр.")
    }

    // --------------------------------------------------------- непрерывный диалог

    /** «Включи/выключи непрерывный диалог», «непрерывный диалог включен?» */
    private fun dialogCmd(ctx: Context, s: String): Outcome? {
        if (!AssistantModes.isAboutDialog(s)) return null

        AssistantModes.dialogRequest(s)?.let { want ->
            Prefs.setFollowUp(ctx, want)
            return Outcome(
                if (want) {
                    "Непрерывный диалог включён, сэр. После ответа я продолжаю слушать — " +
                        "следующую фразу можно говорить без «Джарвис». " +
                        "Вернусь к ожиданию слова после паузы в разговоре."
                } else {
                    "Непрерывный диалог выключен, сэр. Каждая команда теперь начинается со слова «Джарвис»."
                }
            )
        }

        if (Regex("включен|выключен|статус|состояние|работает|как сейчас|активен").containsMatchIn(s)) {
            return Outcome(
                if (Prefs.followUp(ctx)) {
                    "Непрерывный диалог включён, сэр: после ответа скажите следующую фразу без «Джарвис»."
                } else {
                    "Непрерывный диалог выключен, сэр. Скажите «включи непрерывный диалог», чтобы говорить подряд."
                }
            )
        }
        // «что такое непрерывный диалог» — пусть отвечает ИИ
        if (Regex("что такое|зачем|объясни|почему|расскажи про").containsMatchIn(s)) return null

        return Outcome(
            "Управление: «включи непрерывный диалог» или «выключи непрерывный диалог». " +
                "В непрерывном диалоге после моего ответа можно сразу говорить следующее, без «Джарвис»."
        )
    }

    // ---------------------------------------------------------- автоответчик

    /** «Включи автоответ», «автоответчик работает?», «добавь правило автоответа …» */
    private fun autoReplyCmd(ctx: Context, s: String): Outcome? {
        if (!AssistantModes.isAboutAutoReply(s) && !AssistantModes.isRuleCommand(s)) return null
        when (AssistantModes.autoReplyCommand(s)) {
            AssistantModes.AutoReplyCommand.ON -> {
                Prefs.setAutoReply(ctx, true)
                return Outcome(autoReplyOnReply(ctx))
            }
            AssistantModes.AutoReplyCommand.OFF -> {
                Prefs.setAutoReply(ctx, false)
                return Outcome(
                    "Автоответчик выключен, сэр. Входящие сообщения я больше не трогаю — " +
                        "отвечайте сами."
                )
            }
            AssistantModes.AutoReplyCommand.STATUS -> {
                return Outcome(autoReplyStatusText(ctx))
            }
            AssistantModes.AutoReplyCommand.DIAG -> {
                return Outcome(AutoReplyService.health(ctx))
            }
            AssistantModes.AutoReplyCommand.RULES_ADD -> {
                val rule = AssistantModes.autoRuleText(s)
                if (rule.isNullOrBlank()) {
                    return Outcome(
                        "Какую фразу запомнить, сэр? Скажите, например: «добавь правило: " +
                            "если зовут гулять — отвечай, что я занят»."
                    )
                }
                Prefs.addAutoReplyRule(ctx, rule)
                return Outcome("Правило записано, сэр: «$rule».")
            }
            AssistantModes.AutoReplyCommand.RULES_SHOW -> {
                val rules = Prefs.autoReplyRules(ctx)
                return Outcome(
                    if (rules.isBlank()) "Правил для автоответа пока нет, сэр."
                    else "Правила автоответа, сэр: $rules"
                )
            }
            AssistantModes.AutoReplyCommand.RULES_CLEAR -> {
                Prefs.setAutoReplyRules(ctx, "")
                return Outcome("Все правила автоответа удалены, сэр.")
            }
            null -> return null
        }
    }

    private fun autoReplyOnReply(ctx: Context): String {
        val extra = buildString {
            if (!Llm.ready(ctx)) {
                append(" Но для автоответов нужен настроенный провайдер ИИ — откройте «настройки Джарвиса» и впишите ключ.")
            }
            if (!AutoReplyService.isGranted(ctx)) {
                append(" Дайте доступ к уведомлениям: «настройки Джарвиса» → «Автоответчик» → кнопка «Доступ к уведомлениям».")
            }
            if (!AutoReplyService.contactsGranted(ctx)) {
                append(" Дайте доступ к контактам («настройки Джарвиса» → кнопка «Контакты») — иначе я не отличу своих от чужих.")
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                append(" Разрешите Джарвису «Уведомления» в системных настройках — иначе не покажу, что ответил.")
            }
            if (!Prefs.autoReplyScreenOff(ctx)) {
                append(" Отвечаю даже при включённом экране.")
            }
        }
        return "Автоответчик включён, сэр. В мессенджерах я сам отвечаю на сообщения личных контактов" +
            (if (Prefs.autoReplyScreenOff(ctx)) " (когда экран выключен)" else "") +
            ", пока не скажете «выключи автоответ»." + extra
    }

    private fun autoReplyStatusText(ctx: Context): String {
        if (!Prefs.autoReply(ctx)) {
            return "Автоответчик сейчас выключен, сэр. Скажите «включи автоответ» — и я буду " +
                "отвечать за вас в WhatsApp и Telegram, когда экран выключен."
        }
        val problems = ArrayList<String>()
        if (!Llm.ready(ctx)) problems.add("нет ключа ИИ — впишите в «настройках Джарвиса»")
        if (!AutoReplyService.isGranted(ctx)) {
            problems.add("не выдан «доступ к уведомлениям» — кнопка в настройках Джарвиса")
        }
        val rules = Prefs.autoReplyRules(ctx)
        return buildString {
            append("Автоответчик включён, сэр.")
            if (Prefs.autoReplyScreenOff(ctx)) append(" Отвечаю, когда экран выключен.")
            else append(" Отвечаю и при включённом экране.")
            if (problems.isNotEmpty()) append(" Но: ").append(problems.joinToString("; ")).append(".")
            if (rules.isNotBlank()) append(" Правила: ").append(rules).append(".")
        }
    }

    /** «Включи автоотправку в вацап», «автоотправка работает?» — сервис спец. возможностей. */
    private fun autoSendCmd(ctx: Context, s: String): Outcome? {
        val about = Regex("автоотправк|сам(?:ая)? отправк|отправк[а-я]* (?:в|через|по) (?:вацап|whatsapp|ватсап|вотсап)|отправля(?:й|ть) само", RegexOption.IGNORE_CASE)
        if (!about.containsMatchIn(s)) return null
        val status = if (AutoSend.enabled(ctx)) "работает" else "выключена"
        if (Regex("работает|включен|выключен|статус|проверь|проверить|как там").containsMatchIn(s)) {
            return Outcome(
                if (AutoSend.enabled(ctx)) "Автоотправка в WhatsApp включена, сэр: «напиши маме в вацап: привет» уйдёт само."
                else "Автоотправка в WhatsApp выключена, сэр. Скажите «включи автоотправку» — и включите «Джарвис — автоотправку» в системном списке."
            )
        }
        if (Regex("включ|вруб|настрой|как включить|хочу").containsMatchIn(s) &&
            !Regex("выключ|отключ|не надо").containsMatchIn(s)
        ) {
            return if (AutoSend.enabled(ctx)) {
                Outcome("Автоотправка в WhatsApp уже включена, сэр.")
            } else {
                Outcome(
                    "Открываю список специальных возможностей. Включите там «Джарвис — автоотправка» — и сообщения по команде будут уходить сами.",
                    AutoSend.openSettings(ctx)
                )
            }
        }
        if (Regex("выключ|отключ").containsMatchIn(s)) {
            return if (!AutoSend.enabled(ctx)) {
                Outcome("Автоотправка и так выключена, сэр.")
            } else {
                Outcome("Чтобы выключить автоотправку, выключите «Джарвис — автоотправку» в списке специальных возможностей (открываю).",
                    AutoSend.openSettings(ctx))
            }
        }
        return Outcome("Автоотправка в WhatsApp сейчас $status, сэр. «Включи автоотправку» — открою нужные настройки.")
    }

    // ------------------------------------------------------ напоминания

    private fun reminder(ctx: Context, s: String): Outcome? {
        if (Regex("список напоминаний|какие напоминания|покажи напоминания").containsMatchIn(s)) {
            return Outcome(ReminderScheduler.list(ctx))
        }
        if (Regex("отмени (все )?напоминания|удали (все )?напоминания|сними напоминания").containsMatchIn(s)) {
            val n = ReminderScheduler.cancelAll(ctx)
            return Outcome(if (n > 0) "Отменил напоминаний: $n, сэр." else "Напоминаний нет, сэр.")
        }

        // «напомни через 20 минут выключить духовку»
        Regex("(?:напомни|напомнить|напоминание)(?:\\s+мне)?\\s+через\\s+(\\d+)\\s*(секунд[а-яa-z0-9]*|минут[а-яa-z0-9]*|час[а-яa-z0-9]*)?\\s*(.*)")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@let
                val mult: Double = when {
                    m.groupValues[2].startsWith("час") -> 60.0
                    m.groupValues[2].startsWith("секунд") -> 1.0 / 60.0
                    else -> 1.0
                }
                val minutes = (n * mult).roundToInt().coerceAtLeast(1)
                val text = m.groupValues[3].trim()
                if (text.isBlank()) return null // без текста — пусть будет обычный таймер
                return schedule(ctx, System.currentTimeMillis() + minutes * 60_000L, text, "через $minutes мин.")
            }

        // «напомни в 18:30 позвонить маме»
        Regex("(?:напомни|напомнить|напоминание)(?:\\s+мне)?\\s+в\\s+(\\d{1,2})[:.\\s](\\d{2})\\s*(.*)")
            .find(s)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: return@let
                val min = m.groupValues[2].toIntOrNull() ?: return@let
                val text = m.groupValues[3].trim()
                if (text.isBlank() || h !in 0..23 || min !in 0..59) return null
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                return schedule(ctx, cal.timeInMillis, text, "в %02d:%02d".format(h, min))
            }
        return null
    }

    private fun schedule(ctx: Context, at: Long, text: String, human: String): Outcome {
        return if (ReminderScheduler.schedule(ctx, at, text)) {
            Outcome("Записал, сэр: напомню $human — «$text».")
        } else {
            Outcome("Не смог поставить напоминание, сэр.")
        }
    }

    // ------------------------------------------------ будильник / таймер

    private fun alarmOrTimer(ctx: Context, s: String): Outcome? {
        if (Regex("покажи будильники|открой будильник|мои будильники").containsMatchIn(s)) {
            return Outcome("Открываю будильники.", Intent(AlarmClock.ACTION_SHOW_ALARMS).withTask(ctx))
        }
        if (Regex("покажи таймеры|мои таймеры").containsMatchIn(s)) {
            return Outcome("Открываю таймеры.", Intent(AlarmClock.ACTION_SHOW_TIMERS).withTask(ctx))
        }

        // 1) длительность: «таймер на 5 минут», «через 10 минут»
        TimeParse.duration(s)?.let { d ->
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, d.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .withTask(ctx)
            return Outcome("Ставлю таймер на ${d.human}.", i)
        }

        // 2) момент дня: «будильник на 7:30», «разбуди меня в 6 вечера»
        TimeParse.clock(s)?.let { c ->
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, c.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .withTask(ctx)
            return Outcome("Будильник на ${c.human}, сэр.", i)
        }

        if (Regex("^будильник|^разбуди|поставь будильник").containsMatchIn(s)) {
            return Outcome("Не разобрал время. Скажите, например: «будильник на 7:30».")
        }
        return null
    }

    // --------------------------------------------------------- калькулятор

    private fun math(s: String): Outcome? {
        val looksLikeMath = Regex(
            "сколько будет|сколько получится|посчитай|посчитать|вычисли|калькулятор|" +
                "процент|корень|плюс|минус|умножь|умножить|помножь|раздели|разделить|подели|" +
                "отними|вычти|прибавь|в квадрате|в кубе|в степени|сумма|^\\(?\\d"
        ).containsMatchIn(s)
        if (!looksLikeMath) return null
        val r = MathEngine.calc(s)
        return when {
            r.ok -> Outcome("Будет ${MathEngine.format(r.value!!)}.")
            r.error != null -> Outcome(r.error)
            else -> null // не математика — отдаём дальше
        }
    }

    // ---------------------------------------------------------- конвертер

    /** Единицы измерения: «5 км в милях», «100 фаренгейта в цельсиях», «2 гб в мб». */
    private fun convert(s: String): Outcome? {
        Units.convert(s)?.let { return Outcome(it) }
        Units.temperatureOnly(s)?.let { return Outcome(it) }
        return null
    }

    // --------------------------------------------------- бытовые расчёты

    /** ИМТ, чаевые, скидка, площадь, факториал, римские числа, зодиак… */
    private fun life(s: String): Outcome? = LifeCalc.ask(s)?.let { Outcome(it) }

    // ------------------------------------------------------ курсы валют

    /** «курс доллара», «100 долларов в тенге» — курсы приходят из сети. */
    private fun rates(s: String): Outcome? {
        val q = Rates.parse(s) ?: return null
        return Outcome(
            reply = "Смотрю курс, сэр…",
            async = { _, done -> done(Rates.answer(q)) }
        )
    }

    // --------------------------------------------------------- календарь

    /** «сколько дней до 31 декабря», «какой день недели 1 января 2030»… */
    private fun dateFacts(s: String): Outcome? = DateFacts.ask(s)?.let { Outcome(it) }

    // ------------------------------------------------- списки и заметки

    /** Офлайн-списки покупок и заметки — хранятся на телефоне. */
    private fun lists(ctx: Context, s: String): Outcome? {
        val shop = Regex("списк[а-яa-z0-9]*\\s+покупок|покупк|что купить|в магазин").containsMatchIn(s)
        val notes = Regex("заметк").containsMatchIn(s)
        if (!shop && !notes) return null
        val key = if (notes) Lists.NOTES else Lists.SHOP

        Regex("(?:удали|убери|вычеркни)\\s+(.+?)\\s+из\\s+(?:списка|покупок|заметок)")
            .find(s)?.let { m ->
                val item = m.groupValues[1].trim()
                if (item.length >= 2) return Outcome(Lists.remove(ctx, key, item))
            }
        Regex("(?:удали|убери)\\s+заметку\\s+(.+)").find(s)?.let { m ->
            return Outcome(Lists.remove(ctx, Lists.NOTES, m.groupValues[1].trim()))
        }
        if (Regex("очисти|очистить|удали все|удалить все|снеси|обнули").containsMatchIn(s)) {
            return Outcome(Lists.clear(ctx, key))
        }
        if (Regex("добавь|добавить|запиши|записать|внеси|напомнить купить|нужно купить").containsMatchIn(s)) {
            val body = Regex("(?:добавь|добавить|запиши|записать|внеси)\\s+" +
                "(?:в\\s+список\\s+покупок\\s+|в\\s+список\\s+|в\\s+заметки\\s+|заметку\\s+)?(.+)")
                .find(s)?.groupValues?.get(1)?.trim().orEmpty()
            val clean = body.removeSuffix("в список покупок").removeSuffix("в список").trim()
            if (clean.length >= 2) return Outcome(Lists.add(ctx, key, clean))
        }
        if (Regex("что в списке|список покупок|мои заметки|покажи заметки|покажи список|что купить|" +
                "список заметок|^заметки$|^покупки$").containsMatchIn(s)
        ) {
            return Outcome(Lists.show(ctx, key))
        }
        return null
    }

    // ------------------------------------------------------------ погода

    private fun weather(s: String): Outcome? {
        if (!Regex("погод[а-яa-z0-9]*|какая температура|температур[а-яa-z0-9]* на улице|что за окном").containsMatchIn(s)) return null
        val city = Regex("(?:в|на)\\s+([\\p{L}][\\p{L}\\s-]{2,30})\\s*$").find(s)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
        return Outcome(
            reply = "Смотрю прогноз, сэр…",
            async = { _, done -> done(Weather.fetch(city)) }
        )
    }

    // -------------------------------------------------------- время / дата

    private fun timeDate(s: String): Outcome? {
        // время в другом часовом поясе
        Regex("время в ([\\p{L}\\s-]{3,30})|который час в ([\\p{L}\\s-]{3,30})").find(s)?.let { m ->
            val place = (m.groupValues[1] + m.groupValues[2]).trim()
            val zone = Timezones.find(place)
            return if (zone != null) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                fmt.timeZone = java.util.TimeZone.getTimeZone(zone)
                Outcome("В городе ${place.replaceFirstChar { it.uppercase(Locale.getDefault()) }} сейчас ${fmt.format(Date())}.")
            } else {
                Outcome("Не знаю часовой пояс «$place», сэр.")
            }
        }
        if (Regex("который час|сколько времени|сколько сейчас времени|^время$|текущее время|точное время").containsMatchIn(s)) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            return Outcome("Сейчас $t, сэр.")
        }
        if (Regex("какое (сегодня )?(число|дата)|какая (сегодня )?дата|какой (сегодня )?день|^дата$").containsMatchIn(s)) {
            val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())
            return Outcome("Сегодня ${d.replaceFirstChar { it.uppercase(Locale.getDefault()) }}.")
        }
        if (Regex("какой день недели|день недели").containsMatchIn(s)) {
            val d = SimpleDateFormat("EEEE", Locale("ru")).format(Date())
            return Outcome("Сегодня ${d.replaceFirstChar { it.uppercase(Locale.getDefault()) }}.")
        }
        if (Regex("сколько дней до нового года|до нового года").containsMatchIn(s)) {
            val now = Calendar.getInstance()
            val ny = Calendar.getInstance().apply {
                set(now.get(Calendar.YEAR) + 1, Calendar.JANUARY, 1, 0, 0, 0)
            }
            val days = ((ny.timeInMillis - now.timeInMillis) / 86_400_000L)
            return Outcome("До Нового года $days дн., сэр.")
        }
        return null
    }

    // ------------------------------------------------------- состояние телефона

    private fun deviceInfo(ctx: Context, s: String): Outcome? {
        if (Regex("заряд|батаре|аккум|питание").containsMatchIn(s)) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = try {
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) { -1 }
            val fi = try {
                ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } catch (e: Exception) { null }
            val status = fi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val temp = (fi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val tempTxt = if (temp > 1f) ", температура ${MathEngine.format(temp.toDouble())}°" else ""
            return Outcome(
                when {
                    pct < 0 -> "Не смог прочитать заряд, сэр."
                    charging -> "Заряд — $pct%$tempTxt, на зарядке."
                    else -> "Заряд — $pct%$tempTxt."
                }
            )
        }
        if (Regex("сколько (свободной )?памяти|свободное место|место на (телефоне|диске)|хранилище").containsMatchIn(s)) {
            return try {
                val st = StatFs(Environment.getDataDirectory().path)
                val free = st.availableBytes / (1024L * 1024 * 1024)
                val total = st.totalBytes / (1024L * 1024 * 1024)
                Outcome("Свободно $free ГБ из $total ГБ, сэр.")
            } catch (e: Exception) {
                Outcome("Не смог прочитать хранилище: ${e.message?.take(80)}")
            }
        }
        if (Regex("модель (телефона|устройства)|какой у меня телефон|версия андроид|информация о (телефоне|устройстве)|о системе")
                .containsMatchIn(s)
        ) {
            return Outcome(
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."
            )
        }
        return null
    }

    // ------------------------------------------------------------ фонарик

    private fun torch(ctx: Context, s: String): Outcome? {
        if (!Regex("фонарик|фонарь|вспышк").containsMatchIn(s)) return null

        // «фонарик на 10 секунд» — включаем и сами гасим
        Regex("на\\s+(\\d+)\\s*(секунд[а-яa-z0-9]*|минут[а-яa-z0-9]*|час[а-яa-z0-9]*)?").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val minutes = when {
                m.groupValues[2].startsWith("минут") -> n
                m.groupValues[2].startsWith("час") -> n * 60
                else -> 0
            }
            val ms = if (minutes > 0) minutes * 60_000L else n * 1000L
            if (ms in 1000..3_600_000L) return torchFor(ctx, ms)
        }

        // «мигни фонариком», «сигнал фонариком»
        if (Regex("мигни|мигать|моргн|сигнал|sos|моргни").containsMatchIn(s)) {
            return try {
                setTorch(ctx, true); torchOn = true
                Thread {
                    repeat(3) {
                        try {
                            setTorch(ctx, false); Thread.sleep(250)
                            setTorch(ctx, true); Thread.sleep(250)
                        } catch (e: Exception) { return@repeat }
                    }
                    try { setTorch(ctx, false); torchOn = false } catch (e: Exception) { }
                }.start()
                Outcome("Мигаю фонариком, сэр.")
            } catch (e: Exception) {
                Outcome("Не смог управлять фонариком: ${e.message?.take(120)}")
            }
        }

        val on = when {
            Regex("включи|зажги|давай|свети").containsMatchIn(s) -> true
            Regex("выключи|погаси|убери|потуши").containsMatchIn(s) -> false
            else -> !torchOn
        }
        return try {
            setTorch(ctx, on)
            torchOn = on
            Outcome(if (on) "Фонарик включён." else "Фонарик выключен.")
        } catch (e: Exception) {
            Outcome("Не смог управлять фонариком: ${e.message?.take(120)}")
        }
    }

    /** Включает фонарик и гасит его через [ms] миллисекунд. */
    private fun torchFor(ctx: Context, ms: Long): Outcome = try {
        setTorch(ctx, true)
        torchOn = true
        Thread {
            try { Thread.sleep(ms) } catch (e: Exception) { }
            try { setTorch(ctx, false); torchOn = false } catch (e: Exception) { }
        }.start()
        val human = if (ms % 60_000L == 0L) "${ms / 60_000L} мин." else "${ms / 1000L} сек."
        Outcome("Фонарик включён на $human, сэр.")
    } catch (e: Exception) {
        Outcome("Не смог включить фонарик: ${e.message?.take(120)}")
    }

    // ------------------------------------------------------------ громкость

    private fun volume(ctx: Context, s: String): Outcome? {
        // не трогаем аудиосистему, пока фраза не похожа на команду о звуке
        if (!Regex("громкост|громче|тише|звук|вибро|на всю|мьют|без звука").containsMatchIn(s)) return null
        val am = audio(ctx)
        val max = try { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 15 }
        val cur = try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 0 }

        Regex("громкость (?:на )?(\\d{1,3})\\s*(?:процентов|процента|процент|%)?").find(s)?.let { m ->
            val p = m.groupValues[1].toIntOrNull() ?: return@let
            if (p in 0..100) {
                val v = (max * p / 100f).roundToInt().coerceIn(0, max)
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
                    return Outcome("Громкость $p процентов, сэр.")
                } catch (e: Exception) {
                    return Outcome("Не смог изменить громкость: ${e.message?.take(80)}")
                }
            }
        }
        if (Regex("максимальн[а-яa-z0-9]* громкость|громкость на (полную|максимум)|на всю").containsMatchIn(s)) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                return Outcome("Громкость на максимум, сэр.")
            } catch (e: Exception) { /* ниже */ }
        }
        if (Regex("сколько сейчас громкость|какая громкость").containsMatchIn(s)) {
            val p = if (max > 0) cur * 100 / max else 0
            return Outcome("Громкость $p процентов.")
        }
        if (Regex("громче|сделай громк|добавь звук|прибавь звук").containsMatchIn(s)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return Outcome("Делаю громче, сэр.")
        }
        if (Regex("тише|сделай тих|убавь звук").containsMatchIn(s)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return Outcome("Делаю тише.")
        }
        if (Regex("(выключи|убери|отключи) звук|без звука|режим без звука|мьют").containsMatchIn(s)) {
            try { am.ringerMode = AudioManager.RINGER_MODE_SILENT } catch (e: Exception) { }
            return Outcome("Звук выключен.")
        }
        if (Regex("включи звук|верни звук|включи звонок").containsMatchIn(s)) {
            try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { }
            return Outcome("Звук включён.")
        }
        if (Regex("вибро|вибрац").containsMatchIn(s)) {
            return try {
                am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                Outcome("Включил вибрацию.")
            } catch (e: Exception) {
                Outcome("Не смог переключить режим: откройте настройки звука.", Intent(Settings.ACTION_SOUND_SETTINGS).withTask(ctx))
            }
        }
        return null
    }

    // ------------------------------------------------------------- плеер

    private fun player(ctx: Context, s: String): Outcome? {
        // то же самое: сначала убеждаемся, что речь о плеере
        if (!Regex("пауз|стоп музыка|останови музык|следующ|предыдущ|продолжи музык|возобнови|играй дальше|" +
                "включи плеер|сними с паузы|плей|что .*играет|какая песня|музыка играет")
                .containsMatchIn(s)
        ) return null
        val am = audio(ctx)
        return when {
            Regex("^(пауза|стоп музыка|останови музыку|поставь на паузу|останови плеер|выключи музыку|музыку на паузу)$")
                .containsMatchIn(s) || Regex("поставь (музыку|трек|плеер) на паузу").containsMatchIn(s) -> {
                mediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
                Outcome("Ставлю на паузу.")
            }
            Regex("следующ(ий|ая) (трек|песн)|переключи на следующий|дальше трек|перемотай вперёд|включи следующ").containsMatchIn(s) -> {
                mediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                Outcome("Переключаю на следующий трек.")
            }
            Regex("предыдущ(ий|ая) (трек|песн)|переключи на предыдущ|верни предыдущ|назад трек").containsMatchIn(s) -> {
                mediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                Outcome("Возвращаю предыдущий трек.")
            }
            Regex("продолжи музыку|возобнови музыку|играй дальше|включи плеер|сними с паузы|^плей$").containsMatchIn(s) -> {
                mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY)
                Outcome("Продолжаю воспроизведение.")
            }
            Regex("что (сейчас )?играет|какая песня играет|музыка играет").containsMatchIn(s) -> {
                val on = try { am.isMusicActive } catch (e: Exception) { false }
                Outcome(if (on) "Сейчас что-то играет, сэр." else "Плеер сейчас молчит, сэр.")
            }
            else -> null
        }
    }

    // -------------------------------------------------------- музыка / поиск

    private fun music(ctx: Context, s: String): Outcome? {
        Regex("^(?:включи|запусти|послушать|давай|поставь|скачай)\\s+(музыку|песню|трек|радио)(?:\\s+(.+))?$")
            .find(s)?.let { m ->
                val q = m.groupValues[2]?.trim().orEmpty()
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra("query", q)
                    .withTask(ctx)
                return Outcome(
                    if (q.isEmpty()) "Включаю музыку." else "Ищу «$q» в вашем плеере.",
                    i
                )
            }
        return null
    }

    /**
     * «Включи песню X на ютубе», «включи X в спотифае», «включи ютуб»…
     * YouTube: сразу запускаем первое найденное видео (поиск по названию).
     * Spotify: открываем поиск в приложении (автозапуск трека требует API-ключа).
     */
    private fun mediaApp(ctx: Context, s: String): Outcome? {
        val yt = ChatMedia.youtubeQuery(s)
        if (yt != null) {
            if (yt.isEmpty()) return openMediaApp(ctx, "youtube")
            return Outcome(
                "Секунду, ищу на YouTube: $yt.",
                endDialog = true,
                async = { c, cb ->
                    var url: String? = null
                    try {
                        url = youtubeFirstResult(yt)
                    } catch (e: Throwable) { url = null }
                    launchView(
                        c,
                        url ?: "https://www.youtube.com/results?search_query=" + q(yt)
                    )
                    cb("Включаю «$yt» на YouTube.")
                }
            )
        }
        val sp = ChatMedia.spotifyQuery(s)
        if (sp != null) {
            if (sp.isEmpty()) return openMediaApp(ctx, "spotify")
            val enc = q(sp)
            val inApp = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(sp)}"))
                .withTask(ctx)
            return if (resolves(ctx, inApp)) {
                Outcome("Открываю в Spotify поиск: «$sp».", inApp)
            } else {
                Outcome(
                    "Spotify не установлен — открываю веб-версию.",
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$enc"))
                        .withTask(ctx)
                )
            }
        }
        return null
    }

    /** Открыть само приложение («включи ютуб» / «включи спотифай» без названия). */
    private fun openMediaApp(ctx: Context, which: String): Outcome {
        val pkg = when (which) {
            "youtube" -> "com.google.android.youtube"
            else -> "com.spotify.music"
        }
        val launch = try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Exception) {
            null
        }
        val name = if (which == "youtube") "YouTube" else "Spotify"
        return if (launch != null) {
            Outcome("Открываю $name.", launch.withTask(ctx))
        } else {
            Outcome(
                "$name не установлен. " +
                    if (which == "youtube") "Открываю YouTube в браузере."
                    else "Открываю Spotify в браузере.",
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(if (which == "youtube") "https://www.youtube.com" else "https://open.spotify.com")
                ).withTask(ctx)
            )
        }
    }

    /** Достаёт ссылку на первое найденное видео (без ключей API). */
    private fun youtubeFirstResult(queryText: String): String? {
        val url = java.net.URL("https://www.youtube.com/results?search_query=" + q(queryText))
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val m = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(body) ?: return null
            return "https://www.youtube.com/watch?v=${m.groupValues[1]}"
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun resolves(ctx: Context, i: Intent): Boolean = try {
        i.resolveActivity(ctx.packageManager) != null
    } catch (e: Exception) {
        false
    }

    /** Открыть ссылку из фонового потока (всегда в новой задаче). */
    private fun launchView(c: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.applicationContext.startActivity(i)
        } catch (e: Exception) {
            // если нет ни приложения, ни браузера — молча
        }
    }

    // ------------------------------------------------- сеть и системные настройки

    private fun systemPanel(ctx: Context, s: String): Outcome? {
        fun panel(reply: String, intent: Intent) = Outcome(reply, intent.withTask(ctx))

        if (Regex("вай ?фай|wifi|wi-fi|вайфай").containsMatchIn(s)) {
            return if (Build.VERSION.SDK_INT >= 29)
                panel("Wi-Fi переключается вручную на новых Android — открыл панель сетей, сэр.",
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            else panel("Открываю настройки Wi-Fi.", Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        if (Regex("блютус|bluetooth|синий зуб|блютуз").containsMatchIn(s)) {
            return panel("Открываю настройки Bluetooth.", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        if (Regex("авиарежим|режим полета|режим полёта|самолет|самолёт").containsMatchIn(s)) {
            return panel("Открываю настройки сетей.", Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        if (Regex("точка доступа|режим модема|раздать интернет").containsMatchIn(s)) {
            return panel("Открываю настройки точки доступа.", Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        if (Regex("мобильн[а-яa-z0-9]* (интернет|данные|сеть)|передача данных|мобильная сеть").containsMatchIn(s)) {
            return panel("Открываю настройки мобильной сети.", Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))
        }
        if (Regex("nfc|нфс|энфс").containsMatchIn(s)) {
            return panel("Открываю настройки NFC.", Intent(Settings.ACTION_NFC_SETTINGS))
        }
        if (Regex("яркост").containsMatchIn(s) || Regex("тёмная тема|темная тема|ночной режим").containsMatchIn(s)) {
            return panel("Открываю настройки экрана.", Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        if (Regex("настройки звука|звуковые настройки|мелодия звонка|рингтон").containsMatchIn(s)) {
            return panel("Открываю настройки звука.", Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        if (Regex("не беспокоить|режим сна|тихий режим|днд").containsMatchIn(s)) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return try {
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    Outcome("Режим «Не беспокоить» включён, сэр.")
                } else panel("Нужно разрешение на режим «Не беспокоить» — откройте настройки.",
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } catch (e: Exception) {
                panel("Открываю настройки уведомлений.", Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        if (Regex("энергосбережени|режим энергосбережения|экономия (заряда|батареи)").containsMatchIn(s)) {
            return panel("Открываю настройки энергосбережения.", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
        if (Regex("настройки (приложений|программ)|установленные приложения").containsMatchIn(s)) {
            return panel("Открываю список приложений.", Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
        if (Regex("настройки (памяти|хранилища)").containsMatchIn(s)) {
            return panel("Открываю настройки хранилища.", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }
        if (Regex("настройки (даты|времени|языка)").containsMatchIn(s)) {
            val i = if (Regex("языка").containsMatchIn(s)) Settings.ACTION_LOCALE_SETTINGS else Settings.ACTION_DATE_SETTINGS
            return panel("Открываю настройки.", Intent(i))
        }
        if (Regex("настройки (безопасности|парол)").containsMatchIn(s)) {
            return panel("Открываю настройки безопасности.", Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        if (Regex("^настройки$|открой настройки|системные настройки").containsMatchIn(s)) {
            return panel("Открываю настройки.", Intent(Settings.ACTION_SETTINGS))
        }
        return null
    }

    // --------------------------------------------------------- интернет

    private fun web(ctx: Context, s: String): Outcome? {
        fun q(text: String) = URLEncoder.encode(text, "UTF-8")

        // «переведи на английский hello», «переведи hello»
        Regex("переведи\\s+(.+?)(?:\\s+на\\s+(английск[а-яa-z0-9]*|русск[а-яa-z0-9]*|казахск[а-яa-z0-9]*|немецк[а-яa-z0-9]*|французск[а-яa-z0-9]*|испанск[а-яa-z0-9]*|турецк[а-яa-z0-9]*|китайск[а-яa-z0-9]*|украинск[а-яa-z0-9]*))?$|переведи\\s+(.+?)\\s+как\\s+будет")
            .find(s)?.let { m ->
                val text = (m.groupValues[1] + m.groupValues[3]).trim()
                if (text.isNotEmpty()) {
                    val tl = when {
                        m.groupValues[2].startsWith("английск") -> "en"
                        m.groupValues[2].startsWith("казахск") -> "kk"
                        m.groupValues[2].startsWith("немецк") -> "de"
                        m.groupValues[2].startsWith("французск") -> "fr"
                        m.groupValues[2].startsWith("испанск") -> "es"
                        m.groupValues[2].startsWith("турецк") -> "tr"
                        m.groupValues[2].startsWith("китайск") -> "zh-CN"
                        m.groupValues[2].startsWith("украинск") -> "uk"
                        else -> "ru"
                    }
                    return Outcome(
                        "Перевод: $text",
                        Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://translate.google.com/?sl=auto&tl=$tl&text=${q(text)}&op=translate"
                        )).withTask(ctx)
                    )
                }
            }
        Regex("(?:картинки|фото|изображения)\\s+(.+)").find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            return Outcome("Ищу картинки: $text.", Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?tbm=isch&q=${q(text)}")).withTask(ctx))
        }
        Regex("новости(?:\\s+(.+))?").find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            val url = if (text.isEmpty()) "https://news.google.com"
            else "https://news.google.com/search?q=${q(text)}"
            return Outcome(if (text.isEmpty()) "Открываю новости." else "Новости: $text.",
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).withTask(ctx))
        }
        Regex("(?:википедия|вики)\\s+(.+)").find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            return Outcome("Ищу в Википедии: $text.", Intent(Intent.ACTION_VIEW,
                Uri.parse("https://ru.wikipedia.org/w/index.php?search=${q(text)}")).withTask(ctx))
        }
        Regex("(?:включи|запусти|поставь|посмотрим?|найди|поищи|ищи)\\s+(?:мне\\s+)?(?:музыку|песню|клип|видео|ролик)?\\s*(.*?)\\s*(?:на ютубе|в ютубе|ютуб|youtube)")
            .find(s)?.let { m ->
                val query = m.groupValues[1].trim()
                val url = if (query.isEmpty()) "https://www.youtube.com"
                else "https://www.youtube.com/results?search_query=" + q(query)
                return Outcome(
                    if (query.isEmpty()) "Открываю YouTube." else "Ищу на YouTube: $query.",
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).withTask(ctx)
                )
            }
        // «открой сайт example.com», «зайди на сайт …»
        Regex("(?:открой|зайди на|открыть)\\s+(?:сайт|страницу|страничку|ссылку)\\s+(\\S+)").find(s)?.let { m ->
            val host = m.groupValues[1].trim().trim('.', ',')
            val url = if (host.startsWith("http")) host else "https://$host"
            return Outcome("Открываю сайт $host.",
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).withTask(ctx))
        }
        // магазин приложений разбирается отдельно — не превращаем его в поиск
        if (!Regex("плей ?маркет|магазин|приложение|игру").containsMatchIn(s)) {
            Regex("^(?:найди|поищи|загугли|погугли|поиск|глянь|ищи)\\s+(?:в интернете\\s+)?(.+)$")
                .find(s)?.let { m ->
                    val query = m.groupValues[1].trim()
                    return Outcome(
                        "Ищу в интернете: $query.",
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + q(query))).withTask(ctx)
                    )
                }
        }
        return null
    }

    // --------------------------------------------------------------- камера

    private fun camera(ctx: Context, s: String): Outcome? {
        if (Regex("сними видео|запиши видео|видеокамер").containsMatchIn(s)) {
            return Outcome("Включаю камеру.", Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).withTask(ctx))
        }
        if (Regex("сделай фото|сфотографируй|фотографируй|сними фото").containsMatchIn(s)) {
            return Outcome("Включаю камеру.", Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).withTask(ctx))
        }
        return null
    }

    // ------------------------------------------------------- запуск приложений

    private fun openCmd(ctx: Context, s: String): Outcome? {
        Regex("^(?:открой|запусти|включи|входи в|зайди в)\\s+(.+)$").find(s)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.length >= 2) return openApp(ctx, target)
        }
        return null
    }

    private val appAliases: Map<String, Pair<String, String>> = mapOf(
        "ютуб" to Pair("youtube", "https://www.youtube.com"),
        "ютюб" to Pair("youtube", "https://www.youtube.com"),
        "youtube" to Pair("youtube", "https://www.youtube.com"),
        "телеграм" to Pair("telegram", "https://web.telegram.org"),
        "телеграмм" to Pair("telegram", "https://web.telegram.org"),
        "telegram" to Pair("telegram", "https://web.telegram.org"),
        "вконтакте" to Pair("vk", "https://vk.com"),
        "вк" to Pair("vk", "https://vk.com"),
        "инстаграм" to Pair("instagram", "https://www.instagram.com"),
        "инстаграмм" to Pair("instagram", "https://www.instagram.com"),
        "вотсап" to Pair("whatsapp", "https://web.whatsapp.com"),
        "ватсап" to Pair("whatsapp", "https://web.whatsapp.com"),
        "вотсапп" to Pair("whatsapp", "https://web.whatsapp.com"),
        "whatsapp" to Pair("whatsapp", "https://web.whatsapp.com"),
        "тикток" to Pair("tiktok", "https://www.tiktok.com"),
        "тик ток" to Pair("tiktok", "https://www.tiktok.com"),
        "твиттер" to Pair("twitter", "https://twitter.com"),
        "твитер" to Pair("twitter", "https://twitter.com"),
        "фейсбук" to Pair("facebook", "https://www.facebook.com"),
        "одноклассники" to Pair("ok.ru", "https://ok.ru"),
        "нетфликс" to Pair("netflix", "https://www.netflix.com"),
        "spotify" to Pair("spotify", "https://open.spotify.com"),
        "спотифай" to Pair("spotify", "https://open.spotify.com"),
        "карты" to Pair("maps", "https://maps.google.com"),
        "гугл карты" to Pair("maps", "https://maps.google.com"),
        "2гис" to Pair("2gis", "https://2gis.ru"),
        "калькулятор" to Pair("calc", "https://calculator.apps.url"),
        "камера" to Pair("camera", "https://camera.apps.url"),
        "почта" to Pair("gmail", "https://mail.google.com"),
        "gmail" to Pair("gmail", "https://mail.google.com"),
        "гугл" to Pair("google", "https://www.google.com"),
        "хром" to Pair("chrome", "https://www.google.com"),
        "chrome" to Pair("chrome", "https://www.google.com"),
        "браузер" to Pair("chrome", "https://www.google.com"),
        "галерея" to Pair("gallery", "https://photos.google.com"),
        "фото" to Pair("gallery", "https://photos.google.com"),
        "часы" to Pair("clock", "https://clock.apps.url"),
        "будильник" to Pair("clock", "https://clock.apps.url"),
        "диктофон" to Pair("recorder", "https://recorder.apps.url"),
        "заметки" to Pair("notes", "https://keep.google.com"),
        "keep" to Pair("keep", "https://keep.google.com"),
        "календарь" to Pair("calendar", "https://calendar.google.com"),
        "диск" to Pair("drive", "https://drive.google.com"),
        "контакты" to Pair("contacts", "https://contacts.google.com"),
        "телефон" to Pair("phone", "https://phone.apps.url"),
        "сообщения" to Pair("messages", "https://messages.apps.url"),
        "настройки" to Pair("settings", "https://settings.apps.url"),
        "плей маркет" to Pair("play", "https://play.google.com"),
        "гугл плей" to Pair("play", "https://play.google.com"),
        "переводчик" to Pair("translate", "https://translate.google.com"),
        "яндекс" to Pair("yandex", "https://yandex.ru"),
        "kaspi" to Pair("kaspi", "https://kaspi.kz"),
        "каспи" to Pair("kaspi", "https://kaspi.kz")
    )

    private fun openApp(ctx: Context, target: String): Outcome {
        val t = target.trim().trim('.', '!', '?', ',')
        if (Regex("настройки").containsMatchIn(t)) {
            return Outcome("Открываю настройки.", Intent(Settings.ACTION_SETTINGS).withTask(ctx))
        }
        if (Regex("плей маркет|гугл плей|play market").containsMatchIn(t)) {
            return Outcome(
                "Открываю Play Market.",
                Intent(Intent.ACTION_VIEW, Uri.parse("market://search")).withTask(ctx)
            )
        }
        val key = appAliases.keys.firstOrNull { t == it || t.startsWith("$it ") || t.startsWith(it) }
        val expected = key?.let { appAliases[it]!!.first }
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(main, 0)
        } catch (e: Exception) {
            emptyList()
        }

        if (expected != null) {
            for (ri in apps) {
                val label = try {
                    ri.loadLabel(pm).toString().lowercase(Locale.getDefault())
                } catch (e: Exception) { "" }
                val pkg = ri.activityInfo.packageName.lowercase(Locale.getDefault())
                if (label.contains(expected) || pkg.contains(expected)) {
                    return Outcome(
                        "Открываю ${ri.loadLabel(pm)}.",
                        pm.getLaunchIntentForPackage(ri.activityInfo.packageName)?.withTask(ctx)
                    )
                }
            }
            val url = key?.let { appAliases[it]!!.second }
            if (url != null && url.startsWith("http")) {
                return Outcome("Приложение не установлено — открываю веб-версию.",
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).withTask(ctx))
            }
            return Outcome("Не нашёл «$t», сэр.")
        }

        if (t.length >= 2) {
            for (ri in apps) {
                val label = try {
                    ri.loadLabel(pm).toString().lowercase(Locale.getDefault())
                } catch (e: Exception) { "" }
                if (label == t || label.startsWith(t) || (label.contains(t) && t.length >= 4)) {
                    return Outcome(
                        "Открываю ${ri.loadLabel(pm)}.",
                        pm.getLaunchIntentForPackage(ri.activityInfo.packageName)?.withTask(ctx)
                    )
                }
            }
        }
        if (Regex("интернет|браузер|броузер").containsMatchIn(t)) {
            return Outcome("Открываю браузер.", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).withTask(ctx))
        }
        return Outcome("Не нашёл приложение «$t», сэр.")
    }

    // ------------------------------------------------------- связь: звонок / смс

    private fun call(ctx: Context, s: String): Outcome? {
        Regex("позвони (?:по )?(?:номеру\\s+)?([+\\d][\\d\\s()-]{5,})").find(s)?.let { m ->
            val num = m.groupValues[1].trim()
            val i = dialIntent(ctx, num)
            return Outcome("Набираю $num.", i)
        }
        Regex("^(?:позвони|набери|вызови|позвонить)(?:\\s+(?:на|по|в))?\\s+(.+)$")
            .find(s)?.let { m ->
                val name = m.groupValues[1].trim()
                return callContact(ctx, name)
            }
        return null
    }

    private fun dialIntent(ctx: Context, num: String): Intent {
        val direct = ctx is Activity ||
            ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val i = (if (direct) Intent(Intent.ACTION_CALL) else Intent(Intent.ACTION_DIAL))
            .setData(Uri.parse("tel:$num")).withTask(ctx)
        if (direct && i.action == Intent.ACTION_CALL && Prefs.callSimFirst(ctx)) {
            preferSimFirst(ctx, i)
        }
        return i
    }

    /**
     * Двух SIM: добавляет в интент звонка «звонить первой SIM», чтобы система
     * не спрашивала «какой картой звонить» каждый раз.
     */
    private fun preferSimFirst(ctx: Context, i: Intent) {
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = tm.callCapablePhoneAccounts
            if (accounts.isNullOrEmpty()) return
            i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[0])
            // подстраховка для прошивок, которые смотрят старые ключи слота
            i.putExtra("com.android.phone.extra.slot", 0)
            i.putExtra("com.android.phone.force.slot", true)
        } catch (e: SecurityException) {
            // список SIM без разрешения не отдать: просим его один раз
            if (ctx is Activity &&
                ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    ctx.requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 43)
                } catch (e2: Exception) { }
            }
        } catch (e: Exception) {
            // нет телефонии/одна SIM — звонок пойдёт как обычно
        }
    }

    private fun callContact(ctx: Context, name: String): Outcome {
        if (!hasContacts(ctx)) return contactsNeeded(ctx)
        val num = contactNumber(ctx, name)
        if (num == null) {
            return Outcome(
                "Контакт «$name» не найден. Открываю номеронабиратель.",
                Intent(Intent.ACTION_DIAL).withTask(ctx)
            )
        }
        val i = dialIntent(ctx, num)
        val verb = if (i.action == Intent.ACTION_CALL) "Звоню" else "Номер готов — жмите вызов"
        return Outcome("$verb: $name.", i)
    }

    private fun sms(ctx: Context, s: String): Outcome? {
        Regex("^(?:напиши|отправь|сообщи)(?:\\s+смс|\\s+сообщение|\\s+сэмс)?\\s+(?:к\\s+|в\\s+|на\\s+)?(\\S+)\\s*(.*)$")
            .find(s)?.let { m ->
                val name = m.groupValues[1].trim()
                if (name.length < 2) return null
                val body = m.groupValues[2]
                    .removePrefix("что ").removePrefix("текст ").removePrefix("сообщение ")
                    .trim().trim(',', ':')
                if (!hasContacts(ctx)) return contactsNeeded(ctx)
                val num = contactNumber(ctx, name)
                if (num == null) {
                    return Outcome("Контакт «$name» не найден, сэр.",
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).withTask(ctx))
                }
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).apply {
                    if (body.isNotBlank()) putExtra("sms_body", body)
                }.withTask(ctx)
                return Outcome(
                    if (body.isBlank()) "Открываю сообщение для $name."
                    else "Сообщение для $name готово — подтвердите отправку.",
                    i
                )
            }
        return null
    }

    private fun hasContacts(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun contactsNeeded(ctx: Context): Outcome =
        if (ctx is Activity) {
            ctx.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 41)
            Outcome("Дайте доступ к контактам (появился запрос) и повторите команду, сэр.")
        } else {
            Outcome("Нужен доступ к контактам, сэр. Откройте приложение и выдайте разрешение.", openApp = true)
        }

    private fun contactNumber(ctx: Context, name: String): String? {
        if (!hasContacts(ctx)) return null
        val variants = linkedSetOf(name, name.dropLast(1), name.dropLast(2)).filter { it.length >= 2 }
        for (v in variants) {
            val cur = try {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$v%"),
                    null
                )
            } catch (e: Exception) { null }
            cur?.use { c ->
                if (c.moveToFirst()) {
                    val num = c.getString(0)
                    if (!num.isNullOrBlank()) return num
                }
            }
        }
        return null
    }

    // --------------------------------------------- отправка в WhatsApp голосом

    /**
     * «Напиши маме в вацап: я уже еду», «отправь в вацап папе что я занят»…
     * Открывает чат WhatsApp с набранным текстом; если включена автоотправка
     * (наш сервис спец. возможностей) — сообщение уходит само.
     */
    private fun waSend(ctx: Context, s: String): Outcome? {
        if (!ChatMedia.isWaPhrase(s)) return null
        val msg = ChatMedia.parseWa(s)
        if (msg == null) {
            return Outcome(
                "Кому и что написать в WhatsApp, сэр? Например: «напиши маме в вацап: я уже еду»."
            )
        }
        val name = msg.name
        if (!hasContacts(ctx)) return contactsNeeded(ctx)
        val raw = contactNumber(ctx, name)
        if (raw == null) {
            return Outcome(
                "Контакт «$name» не найден, сэр. Скажите имя, как оно записано в телефонной книге."
            )
        }
        val num = ChatMedia.phoneForWa(raw)
        if (num == null) {
            return Outcome("Не разобрал номер «$name» для WhatsApp, сэр (нужен номер с кодом страны).")
        }
        val text = msg.body
        val pkg = installedWa(ctx)
        val auto = pkg != null && AutoSend.enabled(ctx)

        if (pkg != null) {
            AutoSend.arm(pkg, text)
            val chat = waChatIntent(num, text, pkg)
            return if (auto) {
                Outcome(
                    "Открываю WhatsApp и отправляю «$text».",
                    chat,
                    endDialog = true,
                    async = { _, cb ->
                        val ok = AutoSend.waitResult()
                        cb(
                            if (ok) "Готово, сэр: сообщение «$text» ушло в WhatsApp."
                            else "Не смог нажать «Отправить» сам — текст набран в чате, нажмите отправку, сэр."
                        )
                    }
                )
            } else {
                Outcome(
                    "Открываю чат «$name» в WhatsApp — текст набран. Нажмите «Отправить», сэр. " +
                        "Чтобы сообщения уходили сами, скажите «включи автоотправку».",
                    chat
                )
            }
        }
        // WhatsApp не установлен — веб-версия wa.me (откроется в браузере)
        return Outcome(
            "WhatsApp не установлен — открываю wa.me с готовым текстом, сэр.",
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$num?text=${Uri.encode(text)}")
            ).withTask(ctx)
        )
    }

    /** Установлен ли WhatsApp (обычный или Business); null — нет. */
    private fun installedWa(ctx: Context): String? =
        ChatMedia.WA_PACKAGES.firstOrNull { (pkg, _) ->
            try {
                ctx.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }
        }?.first

    /** Интент «открыть чат WhatsApp с набранным текстом» (jid — прямой путь к чату). */
    private fun waChatIntent(num: String, text: String, pkg: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra("jid", "$num@s.whatsapp.net")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    // -------------------------------------------------------------- карта

    private fun nav(ctx: Context, s: String): Outcome? {
        Regex("^(?:маршрут(?:\\s+до)?|проложи(?:\\s+маршрут)?|как доехать(?:\\s+до)?|как добраться(?:\\s+до)?|веди(?:\\s+до)?|проведи(?:\\s+до)?|довези(?:\\s+до)?)\\s+(.+)$")
            .find(s)?.let { m ->
                val place = m.groupValues[1].trim()
                val q = URLEncoder.encode(place, "UTF-8")
                var i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).withTask(ctx)
                if (i.resolveActivity(ctx.packageManager) == null) {
                    i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).withTask(ctx)
                }
                return Outcome("Прокладываю маршрут: $place.", i)
            }
        Regex("^(?:где находится|покажи на карте|найди на карте|карта)\\s+(.+)$").find(s)?.let { m ->
            val place = m.groupValues[1].trim()
            val q = URLEncoder.encode(place, "UTF-8")
            return Outcome("Ищу на карте: $place.",
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).withTask(ctx))
        }
        return null
    }

    // ======================================================= вспомогательные

    /** Добавляет NEW_TASK, если контекст — не Activity (вызов из службы). */
    private fun Intent.withTask(ctx: Context): Intent = apply {
        if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** URL-кодирование запроса. */
    private fun q(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun audio(c: Context): AudioManager =
        c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Отправляет медиа-клавишу в систему — работает и в фоне, без UI. */
    private fun mediaKey(am: AudioManager, code: Int) {
        val t = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Exception) { /* плеер может не поддерживать */ }
    }

    private fun setTorch(c: Context, on: Boolean) {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: throw IllegalStateException("камера не найдена")
        cm.setTorchMode(id, on)
    }
}
