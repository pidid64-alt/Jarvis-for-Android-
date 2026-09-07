package kz.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Офлайн-движок команд: парсит фразу и управляет телефоном.
 * Возвращает null, если команда не распознана — тогда фраза уходит в Gemini.
 */
object CommandEngine {

    data class Outcome(
        val reply: String,
        val launch: Intent? = null,
        val stopTts: Boolean = false,
        val silent: Boolean = false
    )

    private var torchOn = false

    fun normalize(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[\\s]+"), " ")
            .trim()
            .removeSuffix(".")
            .removeSuffix("!")
            .removeSuffix("?")
            .trim()

    fun execute(activity: Activity, raw: String): Outcome? {
        val s = normalize(raw)
        if (s.isEmpty()) return null

        // ---------- стоп / тишина ----------
        if (Regex("^(стоп|тихо|замолчи|отмена|хватит|прекрати|отстань)$").matches(s)) {
            return Outcome("Слушаюсь, сэр.", stopTts = true, silent = true)
        }

        // ---------- будильник / таймер ----------
        alarmOrTimer(s)?.let { return it }

        // ---------- громкость ----------
        if (Regex("громче|сделай громк|добавь звук").containsMatchIn(s)) {
            audio(activity).adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            return Outcome("Делаю громче, сэр.")
        }
        if (Regex("тише|сделай тих|убавь звук").containsMatchIn(s)) {
            audio(activity).adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            return Outcome("Делаю тише.")
        }
        if (Regex("(выключи|убери) звук|без звука|тишина$").containsMatchIn(s)) {
            audio(activity).ringerMode = AudioManager.RINGER_MODE_SILENT
            return Outcome("Звук выключен.")
        }
        if (Regex("включи звук|верни звук").containsMatchIn(s)) {
            audio(activity).ringerMode = AudioManager.RINGER_MODE_NORMAL
            return Outcome("Звук включён.")
        }

        // ---------- батарея ----------
        if (Regex("заряд|батаре|аккум|питание").containsMatchIn(s)) {
            val bm = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val fi = activity.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = fi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            return Outcome(
                if (charging) "Заряд — $pct%, на зарядке."
                else "Заряд — $pct%."
            )
        }

        // ---------- время / дата ----------
        if (Regex("который час|сколько времени|время$|^время").containsMatchIn(s)) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            return Outcome("Сейчас $t, сэр.")
        }
        if (Regex("какое (сегодня )?(число|дата)|какой (сегодня )?день|дата$").containsMatchIn(s)) {
            val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru")).format(Date())
            return Outcome("Сегодня ${d.replaceFirstChar { it.uppercase(Locale.getDefault()) }}.")
        }

        // ---------- фонарик ----------
        if (Regex("фонарик|фонарь").containsMatchIn(s)) {
            val on = when {
                Regex("включи|зажги|давай").containsMatchIn(s) -> true
                Regex("выключи|погаси|убери").containsMatchIn(s) -> false
                else -> !torchOn
            }
            return try {
                setTorch(activity, on)
                torchOn = on
                Outcome(if (on) "Фонарик включён." else "Фонарик выключен.")
            } catch (e: Exception) {
                Outcome("Не смог управлять фонариком: ${e.message?.take(120)}")
            }
        }

        // ---------- Wi-Fi / Bluetooth / сети / яркость ----------
        if (Regex("вай ?фай|wifi|wi-fi|вайфай").containsMatchIn(s)) {
            val i = if (android.os.Build.VERSION.SDK_INT >= 29)
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
            return Outcome(
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    "Wi-Fi переключается вручную на новых Android — открыл панель сетей, сэр."
                else "Открываю настройки Wi-Fi.",
                i
            )
        }
        if (Regex("блютус|bluetooth|синий зуб|блютуз").containsMatchIn(s)) {
            return Outcome("Открываю настройки Bluetooth.", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        if (Regex("авиарежим|режим полета|самолет").containsMatchIn(s)) {
            return Outcome("Открываю настройки сетей.", Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        if (Regex("яркость|яркост").containsMatchIn(s)) {
            return Outcome("Открываю настройки экрана.", Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }

        // ---------- SMS ----------
        Regex("^(?:напиши|отправь)(?:\\s+смс|\\s+сообщение|\\s+сэмс)?\\s+(?:к\\s+)?(.+)$")
            .find(s)?.let { m ->
                val name = m.groupValues[1].trim()
                return sms(activity, name)
            }

        // ---------- звонок ----------
        Regex("^(?:позвони|набери|вызови|позвонить)(?:\\s+(?:на|по|в))?\\s+(.+)$")
            .find(s)?.let { m ->
                val name = m.groupValues[1].trim()
                return call(activity, name)
            }

        // ---------- навигация ----------
        Regex("^(?:маршрут(?:\\s+до)?|проложи(?:\\s+маршрут)?|как доехать(?:\\s+до)?|как добраться(?:\\s+до)?|веди(?:\\s+до)?|проведи(?:\\s+до)?|довези(?:\\s+до)?)\\s+(.+)$")
            .find(s)?.let { m ->
                val place = m.groupValues[1].trim()
                val q = URLEncoder.encode(place, "UTF-8")
                var i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))
                if (i.resolveActivity(activity.packageManager) == null) {
                    i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
                }
                return Outcome("Прокладываю маршрут: $place.", i)
            }

        // ---------- YouTube поиск ----------
        Regex("(?:включи|запусти|поставь|посмотрим?|найди|поищи)\\s+(?:мне\\s+)?(?:музыку|песню|песню|клип|видео|ролик)?\\s*(.*?)\\s*(?:на ютубе|в ютубе|ютуб)")
            .find(s)?.let { m ->
                val q = m.groupValues[1].trim()
                val url = if (q.isEmpty()) "https://www.youtube.com"
                else "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
                return Outcome(
                    if (q.isEmpty()) "Открываю YouTube."
                    else "Ищу на YouTube: $q.",
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                )
            }

        // ---------- музыка ----------
        Regex("^(?:включи|запусти|послушать|давай)\\s+(музыку|песню|трек)(?:\\s+(.+))?$")
            .find(s)?.let { m ->
                val q = m.groupValues[2]?.trim().orEmpty()
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra("query", q)
                return Outcome(
                    if (q.isEmpty()) "Включаю музыку."
                    else "Ищу: $q — в вашем плеере.",
                    i
                )
            }

        // ---------- поиск в интернете ----------
        Regex("^(?:найди|поищи|загугли|погугли|поиск|глянь)\\s+(?:в интернете\\s+)?(.+)$")
            .find(s)?.let { m ->
                val q = m.groupValues[1].trim()
                return Outcome(
                    "Ищу в интернете: $q.",
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
                    )
                )
            }

        // ---------- открыть приложение ----------
        Regex("^(?:открой|запусти|включи)\\s+(.+)$").find(s)?.let { m ->
            val target = m.groupValues[1].trim()
            return openApp(activity, target)
        }

        // ---------- помощь ----------
        if (Regex("что ты умеешь|какие команды|помощь|справк|help").containsMatchIn(s)) {
            return Outcome(
                "Умею, сэр:\n• «включи/выключи фонарик»\n• «открой ютуб/телеграм/камеру…»\n" +
                "• «позвони маме», «напиши папе»\n• «будильник на 7:30», «таймер на 5 минут»\n" +
                "• «сколько заряда», «который час», «какое число»\n• «маршрут до дома», «найди пиццу»\n" +
                "• «включи музыку», «громче/тише», «включи вайфай»\nА на всё остальное отвечу своими словами — нужен лишь API-ключ в настройках."
            )
        }

        return null // не понял — отдаём ИИ
    }

    // ======================= вспомогательные =======================

    private fun audio(c: Context): AudioManager =
        c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun setTorch(c: Context, on: Boolean) {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: throw IllegalStateException("камера не найдена")
        cm.setTorchMode(id, on)
    }

    private fun alarmOrTimer(s: String): Outcome? {
        // таймер: «таймер на 5 минут» / «через 10 минут»
        Regex("(?:таймер|напомни|разбуди|будильник)\\s+через\\s+(\\d+)\\s*(секунд|минут|час|часа|часов)?")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@let
                val mult = when (m.groupValues[2]) {
                    "секунд" -> 1
                    "час", "часа", "часов" -> 3600
                    else -> 60
                }
                val secs = n * mult
                val human = if (mult == 1) "$n сек." else if (mult == 3600) "$n ч." else "$n мин."
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                return Outcome("Ставлю таймер на $human.", i)
            }

        // таймер: «таймер на 5 минут»
        Regex("^таймер\\s+на\\s+(\\d+)\\s*(секунд|минут|час|часа|часов)?")
            .find(s)?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@let
                val mult = when (m.groupValues[2]) {
                    "секунд" -> 1
                    "час", "часа", "часов" -> 3600
                    else -> 60
                }
                val secs = n * mult
                val human = if (mult == 1) "$n сек." else if (mult == 3600) "$n ч." else "$n мин."
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                return Outcome("Таймер на $human, сэр.", i)
            }

        // будильник на время: 7:30 / 7 30 / 7 часов 30 минут / 7 вечера
        Regex("(?:будильник|разбуди меня?)\\s*(?:на|в)?\\s+(.+)$").find(s)?.let { m ->
            val rest = m.groupValues[1].trim()
            val pm = Regex("вечера|дня|по вечера").containsMatchIn(rest)
            val time = Regex("(\\d{1,2})[:.\\s]+(\\d{2})").find(rest)
                ?: Regex("(\\d{1,2})\\s*(?:час|часа|часов)?\\s*(\\d{1,2})?\\s*минут?").find(rest)
            if (time != null) {
                var h = time.groupValues[1].toIntOrNull() ?: -1
                var min = (time.groupValues[2].toIntOrNull() ?: 0)
                if (time.groupValues[2].length == 1) min = time.groupValues[2].toIntOrNull() ?: 0
                if (h in 1..11 && pm) h += 12
                if (h in 0..23 && min in 0..59) {
                    val hm = "%02d:%02d".format(h, min)
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    return Outcome("Будильник на $hm, сэр.", i)
                }
            }
            return Outcome("Не разобрал время. Скажите, например: «будильник на 7:30».")
        }
        return null
    }

    private val appAliases: Map<String, Pair<String, String>> = mapOf(
        "ютуб" to Pair("youtube", "https://www.youtube.com"),
        "ютюб" to Pair("youtube", "https://www.youtube.com"),
        "телеграм" to Pair("telegram", "https://web.telegram.org"),
        "телеграмм" to Pair("telegram", "https://web.telegram.org"),
        "вконтакте" to Pair("vk", "https://vk.com"),
        "вк" to Pair("vk", "https://vk.com"),
        "инстаграм" to Pair("instagram", "https://www.instagram.com"),
        "инстаграмм" to Pair("instagram", "https://www.instagram.com"),
        "вотсап" to Pair("whatsapp", "https://web.whatsapp.com"),
        "ватсап" to Pair("whatsapp", "https://web.whatsapp.com"),
        "вотсапп" to Pair("whatsapp", "https://web.whatsapp.com"),
        "тикток" to Pair("tiktok", "https://www.tiktok.com"),
        "тик ток" to Pair("tiktok", "https://www.tiktok.com"),
        "карты" to Pair("maps", "https://maps.google.com"),
        "калькулятор" to Pair("calc", "https://calculator.apps.url"),
        "камера" to Pair("camera", "https://camera.apps.url"),
        "почта" to Pair("gmail", "https://mail.google.com"),
        "гугл" to Pair("google", "https://www.google.com")
    )

    private fun openApp(activity: Activity, target: String): Outcome {
        val key = appAliases.keys.firstOrNull { target.startsWith(it) || target == it }
        val expected = key?.let { appAliases[it]!!.first }
        val pm = activity.packageManager
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
                } catch (e: Exception) {
                    ""
                }
                val pkg = ri.activityInfo.packageName.lowercase(Locale.getDefault())
                if (label.contains(expected) || pkg.contains(expected)) {
                    return Outcome(
                        "Открываю ${ri.loadLabel(pm)}.",
                        pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
                    )
                }
            }
            // приложение не найдено — откроем веб-версию
            val url = key?.let { appAliases[it]!!.second } ?: return Outcome("Не нашёл «$target», сэр.")
            return Outcome("Приложение не установлено — открываю веб-версию.", Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // поиск по названию
        if (target.length >= 2) {
            for (ri in apps) {
                val label = try {
                    ri.loadLabel(pm).toString().lowercase(Locale.getDefault())
                } catch (e: Exception) {
                    ""
                }
                if (label.startsWith(target) || (label.contains(target) && target.length >= 4)) {
                    return Outcome(
                        "Открываю ${ri.loadLabel(pm)}.",
                        pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
                    )
                }
            }
        }
        // интернет как крайний случай
        if (Regex("интернет|браузер|броузер").containsMatchIn(target)) {
            return Outcome("Открываю браузер.", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
        }
        return Outcome("Не нашёл приложение «$target», сэр.")
    }

    private fun contactNumber(activity: Activity, name: String): String? {
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val variants = linkedSetOf(name, name.dropLast(1), name.dropLast(2)).filter { it.length >= 2 }
        for (v in variants) {
            val cur = activity.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$v%"),
                null
            )
            cur?.use { c ->
                if (c.moveToFirst()) {
                    val num = c.getString(0)
                    if (!num.isNullOrBlank()) return num
                }
            }
        }
        return null
    }

    private fun call(activity: Activity, name: String): Outcome {
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 41)
            return Outcome("Дайте доступ к контактам (появился запрос) и повторите команду, сэр.")
        }
        val num = contactNumber(activity, name)
        if (num == null) {
            return Outcome(
                "Контакт «$name» не найден. Открываю номеронабиратель.",
                Intent(Intent.ACTION_DIAL)
            )
        }
        val i = if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) Intent(Intent.ACTION_CALL, Uri.parse("tel:$num"))
        else Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
        val verb = if (i.action == Intent.ACTION_CALL) "Звоню" else "Номер готов — жмите вызов"
        return Outcome("$verb: $name.", i)
    }

    private fun sms(activity: Activity, name: String): Outcome {
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 42)
            return Outcome("Дайте доступ к контактам (появился запрос) и повторите, сэр.")
        }
        val num = contactNumber(activity, name)
        if (num == null) {
            return Outcome("Контакт «$name» не найден, сэр.", Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))
        return Outcome("Открываю сообщение для $name.", i)
    }
}
