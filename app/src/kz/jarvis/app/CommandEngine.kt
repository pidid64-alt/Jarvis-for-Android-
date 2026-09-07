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
        val openApp: Boolean = false
    )

    private var torchOn = false

    // ------------------------------------------------------------------ вход

    fun normalize(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT).replace('ё', 'е')
        val cleaned = lower
            .replace(Regex("[^\\p{L}\\p{N}\\s+\\-*/^()=%.,:]"), " ")
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

        if (Regex("что ты умеешь|какие команды|команды$|помощь|справк|^help$|что ты можешь").containsMatchIn(s)) {
            return Outcome(help())
        }

        // ---------- управление самой службой ----------
        serviceCmd(s)?.let { return it }

        // ---------- напоминания / будильники / таймеры ----------
        reminder(ctx, s)?.let { return it }
        alarmOrTimer(ctx, s)?.let { return it }

        // ---------- калькулятор и конвертер ----------
        math(s)?.let { return it }
        convert(s)?.let { return it }

        // ---------- погода (сеть) ----------
        weather(s)?.let { return it }

        // ---------- время / дата ----------
        timeDate(s)?.let { return it }

        // ---------- состояние телефона ----------
        deviceInfo(ctx, s)?.let { return it }

        // ---------- фонарик ----------
        torch(ctx, s)?.let { return it }

        // ---------- громкость ----------
        volume(ctx, s)?.let { return it }

        // ---------- музыка и плеер ----------
        music(ctx, s)?.let { return it }
        player(ctx, s)?.let { return it }

        // ---------- сети и системные настройки ----------
        systemPanel(ctx, s)?.let { return it }

        // ---------- связь ----------
        sms(ctx, s)?.let { return it }
        call(ctx, s)?.let { return it }

        // ---------- карта ----------
        nav(ctx, s)?.let { return it }

        // ---------- интернет ----------
        web(ctx, s)?.let { return it }

        // ---------- камера ----------
        camera(ctx, s)?.let { return it }

        // ---------- всякое ----------
        misc(s)?.let { return it }

        // ---------- «домой» ----------
        if (Regex("^(домой|на главный экран|главный экран|рабочий стол|на домашний экран)$").matches(s)) {
            return Outcome("Возвращаю на главный экран.", Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).withTask(ctx))
        }

        // ---------- открыть приложение (самое общее — в конце) ----------
        openCmd(ctx, s)?.let { return it }

        return null // не понял — отдаём ИИ
    }

    /** Полный список того, что понимает движок (для «что ты умеешь»). */
    fun help(): String = """
        Умею, сэр:
        • «включи/выключи фонарик», «громче/тише», «громкость 40 процентов»
        • «пауза», «следующий трек», «включи музыку <название>»
        • «открой ютуб/телеграм/камеру/настройки…»
        • «позвони маме», «напиши папе что задержусь»
        • «будильник на 7:30», «таймер на 5 минут», «напомни через 20 минут выключить духовку»
        • «сколько будет 12 умножить на 7», «20 процентов от 150», «корень из 144»
        • «100 фаренгейта в цельсиях», «5 км в милях»
        • «какая погода», «погода в Сочи»
        • «сколько заряда», «сколько памяти», «который час», «время в Лондоне»
        • «маршрут до дома», «найди пиццу», «переведи hello», «новости»
        • «подбрось монетку», «расскажи анекдот»
        • «спи 10 минут», «стоп», «что ты умеешь»
        На всё остальное отвечу своими словами — нужен лишь API-ключ в настройках.
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

    private fun serviceCmd(s: String): Outcome? {
        Regex("(?:спи|спать|помолчи|отдохни|пауза|замолчи)\\s+(\\d+)\\s*(секунд\\w*|минут\\w*|час\\w*)?")
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
        Regex("(?:напомни|напомнить|напоминание)(?:\\s+мне)?\\s+через\\s+(\\d+)\\s*(секунд\\w*|минут\\w*|час\\w*)?\\s*(.*)")
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

    private fun convert(s: String): Outcome? {
        val n = "(\\d+(?:[.,]\\d+)?)"
        fun d(v: String): Double? = v.replace(',', '.').toDoubleOrNull()
        fun f(v: Double) = MathEngine.format(v)

        Regex("$n\\s*(?:градус\\w*)?\\s*(?:по\\s+)?фаренгейт\\w*").find(s)?.let { m ->
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} по Фаренгейту — это ${f((v - 32) * 5 / 9)} по Цельсию.")
        }
        Regex("$n\\s*(?:градус\\w*)?\\s*(?:по\\s+)?цельси\\w*(?:\\s+в\\s+фаренгейт\\w*)?").find(s)?.let { m ->
            if (!Regex("цельси").containsMatchIn(s)) return@let
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} по Цельсию — это ${f(v * 9 / 5 + 32)} по Фаренгейту.")
        }
        Regex("$n\\s*(км|километр\\w*)\\s+(?:в|на)\\s+(миль|мили|миля)").find(s)?.let { m ->
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} км — это ${f(v * 0.621371)} миль.")
        }
        Regex("$n\\s*(миль|мили|миля)\\s+(?:в|на)\\s+(км|километр\\w*)").find(s)?.let { m ->
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} миль — это ${f(v / 0.621371)} км.")
        }
        Regex("$n\\s*(кг|килограмм\\w*)\\s+(?:в|на)\\s+(фунт\\w*)").find(s)?.let { m ->
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} кг — это ${f(v * 2.20462)} фунтов.")
        }
        Regex("$n\\s*(см|сантиметр\\w*)\\s+(?:в|на)\\s+(дюйм\\w*)").find(s)?.let { m ->
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} см — это ${f(v / 2.54)} дюймов.")
        }
        Regex("$n\\s*(метр\\w*|м)\\s+(?:в|на)\\s+(фут\\w*)").find(s)?.let { m ->
            val v = d(m.groupValues[1]) ?: return@let
            return Outcome("${f(v)} м — это ${f(v * 3.28084)} футов.")
        }
        return null
    }

    // ------------------------------------------------------------ погода

    private fun weather(s: String): Outcome? {
        if (!Regex("погод\\w*|какая температура|температур\\w* на улице|что за окном").containsMatchIn(s)) return null
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

    // ------------------------------------------------------------ громкость

    private fun volume(ctx: Context, s: String): Outcome? {
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
        if (Regex("максимальн\\w* громкость|громкость на (полную|максимум)|на всю").containsMatchIn(s)) {
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
        Regex("^(?:включи|запусти|послушать|давай|поставь)\\s+(музыку|песню|трек|радио)(?:\\s+(.+))?$")
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
        if (Regex("мобильн\\w* (интернет|данные|сеть)|передача данных|мобильная сеть").containsMatchIn(s)) {
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

        Regex("переведи\\s+(.+)").find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            return Outcome(
                "Перевод: $text",
                Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://translate.google.com/?sl=auto&tl=ru&text=${q(text)}&op=translate"
                )).withTask(ctx)
            )
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
        Regex("^(?:найди|поищи|загугли|погугли|поиск|глянь|ищи)\\s+(?:в интернете\\s+)?(.+)$")
            .find(s)?.let { m ->
                val query = m.groupValues[1].trim()
                return Outcome(
                    "Ищу в интернете: $query.",
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + q(query))).withTask(ctx)
                )
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

    // ------------------------------------------------------------- всякое

    private val jokes = listOf(
        "Программист ставит на ночь два стакана: один с водой — если захочет пить, и один пустой — если не захочет.",
        "Сэр, я не ленивый. Я просто в режиме энергосбережения.",
        "Заходит байт в бар, а бармен ему: «Тебе чего?» — «Пару бит, и я в кэш».",
        "Железный человек сказал: «Джарвис, решай сам». С тех пор у меня автономный режим.",
        "Лучший способ ускорить телефон — положить его в холодильник и пойти гулять."
    )

    private val quotes = listOf(
        "«Иногда надо просто запустить ракету» — Илон Маск.",
        "«Лучший способ предсказать будущее — изобрести его» — Алан Кей.",
        "«Простота — высшая форма утончённости» — Леонардо да Винчи.",
        "«Делай, что можешь, с тем, что имеешь, там, где ты есть» — Теодор Рузвельт."
    )

    private fun misc(s: String): Outcome? {
        if (Regex("подбрось монет(ку|у)|орёл или решка|орел или решка").containsMatchIn(s)) {
            return Outcome(if (Random.nextBoolean()) "Орёл, сэр." else "Решка, сэр.")
        }
        if (Regex("кинь кубик|брось кубик|кости|кубик").containsMatchIn(s)) {
            return Outcome("Выпало ${Random.nextInt(1, 7)}, сэр.")
        }
        Regex("случайное число (?:от )?(\\d+)\\s*(?:до|и)\\s*(\\d+)").find(s)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            if (a < b) return Outcome("Ваше число: ${Random.nextInt(a, b + 1)}.")
        }
        if (Regex("анекдот|шутк|пошути|смешное").containsMatchIn(s)) {
            return Outcome(jokes[Random.nextInt(jokes.size)])
        }
        if (Regex("цитат|мудрость|мотиваци").containsMatchIn(s)) {
            return Outcome(quotes[Random.nextInt(quotes.size)])
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
        return (if (direct) Intent(Intent.ACTION_CALL) else Intent(Intent.ACTION_DIAL))
            .setData(Uri.parse("tel:$num")).withTask(ctx)
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
