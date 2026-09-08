package kz.jarvis.app

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CallLog
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import android.media.AudioManager
import java.util.Collections
import java.util.Locale

/**
 * Расширенные системные команды: состояние телефона и сети, яркость,
 * громкость по потокам, медиа-клавиши, почта и «поделиться», журнал звонков,
 * установка и удаление приложений, дополнительные панели настроек.
 *
 * Вынесено из CommandEngine, чтобы тот оставался читаемым; все функции
 * возвращают готовый [CommandEngine.Outcome].
 */
object SystemCommands {

    private fun Intent.task(ctx: Context): Intent = apply {
        if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ------------------------------------------------------- состояние телефона

    fun deviceFacts(ctx: Context, s: String): CommandEngine.Outcome? {

        if (Regex("(?:мой |какой )?(?:ip|айпи|ай-пи)(?: адрес)?|локальный адрес").containsMatchIn(s)) {
            val ips = ipAddresses()
            return CommandEngine.Outcome(
                if (ips.isEmpty()) "Сейчас нет активного сетевого адреса, сэр."
                else "Ваш IP в этой сети: ${ips.joinToString(", ")}."
            )
        }

        if (Regex("сколько\\D{0,15}приложений установлено|количество приложений|сколько программ").containsMatchIn(s)) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val n = try { ctx.packageManager.queryIntentActivities(main, 0).size } catch (e: Exception) { -1 }
            return CommandEngine.Outcome(
                if (n < 0) "Не смог посчитать приложения, сэр." else "В меню установлено приложений: $n."
            )
        }

        if (Regex("сколько (?:времени )?работает телефон|время работы|аптайм|сколько включён|сколько включен").containsMatchIn(s)) {
            val ms = SystemClock.elapsedRealtime()
            val d = ms / 86_400_000L
            val h = ms % 86_400_000L / 3_600_000L
            val m = ms % 3_600_000L / 60_000L
            return CommandEngine.Outcome("Телефон работает $d дн. $h ч. $m мин., сэр.")
        }

        if (Regex("разрешение экрана|какой экран|диагональ|плотность экрана|dpi").containsMatchIn(s)) {
            return try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(dm)
                val inch = Math.sqrt(
                    Math.pow((dm.widthPixels / dm.xdpi).toDouble(), 2.0) +
                        Math.pow((dm.heightPixels / dm.ydpi).toDouble(), 2.0)
                )
                CommandEngine.Outcome(
                    "Экран ${dm.widthPixels}×${dm.heightPixels}, плотность ${dm.densityDpi} dpi, диагональ около ${MathEngine.format(inch)} дюйма."
                )
            } catch (e: Exception) {
                CommandEngine.Outcome("Не смог прочитать параметры экрана, сэр.")
            }
        }

        if (Regex("сколько (?:оперативной )?памяти|оперативк|озу|ram").containsMatchIn(s) &&
            Regex("оператив|озу|ram").containsMatchIn(s)
        ) {
            return try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val total = mi.totalMem / (1024L * 1024 * 1024)
                val free = mi.availMem / (1024L * 1024 * 1024)
                CommandEngine.Outcome("Оперативной памяти ${total} ГБ, доступно ${free} ГБ, сэр.")
            } catch (e: Exception) {
                CommandEngine.Outcome("Не смог прочитать память, сэр.")
            }
        }

        if (Regex("процессор|ядер|частот|архитектур").containsMatchIn(s)) {
            val cores = Runtime.getRuntime().availableProcessors()
            val abi = try { Build.SUPPORTED_ABIS.firstOrNull() ?: "?" } catch (e: Exception) { "?" }
            return CommandEngine.Outcome(
                "Процессор ${Build.HARDWARE}, ядер: $cores, архитектура $abi, Android ${Build.VERSION.RELEASE}."
            )
        }

        if (Regex("датчик|сенсор").containsMatchIn(s)) {
            return try {
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val list = sm.getSensorList(Sensor.TYPE_ALL)
                val names = list.take(4).joinToString(", ") { it.name }
                CommandEngine.Outcome("Датчиков: ${list.size}. Например: $names.")
            } catch (e: Exception) {
                CommandEngine.Outcome("Не смог прочитать датчики, сэр.")
            }
        }

        if (Regex("блютуз включен|bluetooth включен|блютуз выключен|статус блютус|блютуз работает").containsMatchIn(s)) {
            val on = globalInt(ctx, Settings.Global.BLUETOOTH_ON)
            return CommandEngine.Outcome(
                when (on) {
                    1 -> "Bluetooth включён, сэр."
                    0 -> "Bluetooth выключен, сэр."
                    else -> "Не смог прочитать состояние Bluetooth, сэр."
                }
            )
        }

        if (Regex("вай ?фай включен|wifi включен|вайфай работает|статус wifi|wi-fi включен").containsMatchIn(s)) {
            val on = globalInt(ctx, Settings.Global.WIFI_ON)
            return CommandEngine.Outcome(
                when (on) {
                    0 -> "Wi-Fi выключен, сэр."
                    1, 2 -> "Wi-Fi включён, сэр."
                    else -> "Не смог прочитать состояние Wi-Fi, сэр."
                }
            )
        }

        if (Regex("режим пол[её]та включен|авиарежим включен|самол[её]т включен").containsMatchIn(s)) {
            val on = globalInt(ctx, Settings.Global.AIRPLANE_MODE_ON)
            return CommandEngine.Outcome(
                if (on == 1) "Режим полёта включён, сэр." else "Режим полёта выключен, сэр."
            )
        }

        if (Regex("энергосбережение включено|режим энергосбережения включен|экономия заряда включена").containsMatchIn(s)) {
            val on = try {
                (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
            } catch (e: Exception) { false }
            return CommandEngine.Outcome(if (on) "Энергосбережение включено, сэр." else "Энергосбережение выключено, сэр.")
        }

        if (Regex("какая яркость|яркость экрана сейчас|текущая яркость").containsMatchIn(s)) {
            val v = try {
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) { -1 }
            return CommandEngine.Outcome(
                if (v < 0) "Не смог прочитать яркость, сэр."
                else "Яркость экрана — $v из 255 (${v * 100 / 255}%)."
            )
        }

        if (Regex("какой оператор|какая симка|какая сим-карта|имя оператора|какая сеть").containsMatchIn(s)) {
            val name = try {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName
            } catch (e: Exception) { null }
            return CommandEngine.Outcome(
                if (name.isNullOrBlank()) "Оператор не определяется — возможно, нет SIM-карты, сэр."
                else "Ваш оператор: $name."
            )
        }

        if (Regex("мой номер|какой у меня номер|номер телефона").containsMatchIn(s) &&
            !Regex("набери|позвони").containsMatchIn(s)
        ) {
            return try {
                val num = (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).line1Number
                if (num.isNullOrBlank()) {
                    CommandEngine.Outcome("Оператор не отдаёт номер этой SIM-карты, сэр.")
                } else {
                    CommandEngine.Outcome("Ваш номер: $num.")
                }
            } catch (e: Exception) {
                CommandEngine.Outcome("Для чтения номера нужно разрешение «Телефон», сэр.")
            }
        }

        if (Regex("здоровье батареи|состояние батареи|напряжение|здоровье аккумулятора").containsMatchIn(s)) {
            val fi = try {
                ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } catch (e: Exception) { null }
            val health = fi?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1) ?: -1
            val mv = fi?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val temp = (fi?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val h = when (health) {
                android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "хорошее"
                android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "перегрев"
                android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "батарея неисправна"
                android.os.BatteryManager.BATTERY_HEALTH_COLD -> "низкая температура"
                else -> "неизвестно"
            }
            return CommandEngine.Outcome(
                "Состояние батареи: $h, температура ${MathEngine.format(temp.toDouble())}°, напряжение ${mv / 1000f} В."
            )
        }

        return null
    }

    private fun globalInt(ctx: Context, key: String): Int = try {
        Settings.Global.getInt(ctx.contentResolver, key, -1)
    } catch (e: Exception) { -1 }

    private fun ipAddresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (nif in Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (e: Exception) { /* без сети */ }
        return out
    }

    // ------------------------------------------------------------------ экран

    fun screen(ctx: Context, s: String): CommandEngine.Outcome? {
        Regex("яркость\\s+(?:на\\s+)?(\\d{1,3})\\s*(?:процентов|процента|процент|%)?").find(s)?.let { m ->
            val p = m.groupValues[1].toIntOrNull() ?: return@let
            if (p in 0..100) return setBrightness(ctx, p)
        }
        if (Regex("яркость на (полную|максимум)|максимальная яркость").containsMatchIn(s)) return setBrightness(ctx, 100)
        if (Regex("минимальная яркость|яркость на минимум").containsMatchIn(s)) return setBrightness(ctx, 5)
        if (Regex("сделай ярче|прибавь яркость|экран ярче").containsMatchIn(s)) return bumpBrightness(ctx, +40)
        if (Regex("сделай темнее|убавь яркость|экран темнее|притуши").containsMatchIn(s)) return bumpBrightness(ctx, -40)
        return null
    }

    private fun curBrightness(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) { -1 }

    private fun bumpBrightness(ctx: Context, delta: Int): CommandEngine.Outcome {
        val cur = curBrightness(ctx)
        if (cur < 0) return setBrightness(ctx, -1)
        val p = ((cur + delta) * 100 / 255).coerceIn(1, 100)
        return setBrightness(ctx, p)
    }

    private fun setBrightness(ctx: Context, percent: Int): CommandEngine.Outcome {
        if (percent < 0) {
            return CommandEngine.Outcome(
                "Не смог прочитать яркость, сэр.",
                Intent(Settings.ACTION_DISPLAY_SETTINGS).task(ctx)
            )
        }
        if (!Settings.System.canWrite(ctx)) {
            return CommandEngine.Outcome(
                "Нужно разрешение «изменять системные настройки» — открыл страницу разрешений.",
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).task(ctx)
            )
        }
        return try {
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                (255 * percent / 100).coerceIn(1, 255)
            )
            CommandEngine.Outcome("Яркость $percent процентов, сэр.")
        } catch (e: Exception) {
            CommandEngine.Outcome("Не смог изменить яркость: ${e.message?.take(80)}")
        }
    }

    // ------------------------------------------------------ звук и плеер

    fun media(ctx: Context, s: String): CommandEngine.Outcome? {
        if (!Regex("перемотай|перемотка|мотни|громкость (звонка|рингтона|вызова|будильника|уведомлений|сообщений)")
                .containsMatchIn(s)
        ) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Regex("перемотай вперёд|перемотай вперед|перемотка вперёд|мотни вперёд").containsMatchIn(s)) {
            key(am, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            return CommandEngine.Outcome("Перематываю вперёд.")
        }
        if (Regex("перемотай назад|перемотка назад|мотни назад").containsMatchIn(s)) {
            key(am, KeyEvent.KEYCODE_MEDIA_REWIND)
            return CommandEngine.Outcome("Перематываю назад.")
        }

        val stream = when {
            Regex("громкость (звонка|рингтона|вызова)").containsMatchIn(s) -> AudioManager.STREAM_RING to "звонка"
            Regex("громкость будильника").containsMatchIn(s) -> AudioManager.STREAM_ALARM to "будильника"
            Regex("громкость уведомлений|громкость сообщений").containsMatchIn(s) ->
                AudioManager.STREAM_NOTIFICATION to "уведомлений"
            else -> return null
        }
        Regex("(\\d{1,3})\\s*(?:процентов|процента|процент|%)").find(s)?.let { m ->
            val p = m.groupValues[1].toIntOrNull() ?: return@let
            if (p !in 0..100) return@let
            return try {
                val max = am.getStreamMaxVolume(stream.first)
                am.setStreamVolume(stream.first, (max * p / 100f).toInt().coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
                CommandEngine.Outcome("Громкость ${stream.second} — $p процентов, сэр.")
            } catch (e: Exception) {
                CommandEngine.Outcome("Не смог изменить громкость ${stream.second}.")
            }
        }
        return null
    }

    private fun key(am: AudioManager, code: Int) {
        val t = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Exception) { /* плеер может не поддерживать */ }
    }

    // --------------------------------------------------- почта, шеринг, мессенджеры

    fun share(ctx: Context, s: String): CommandEngine.Outcome? {
        Regex("(?:отправь|напиши|отправить)\\s+(?:письмо|почту|email|e-mail|мейл)\\s*(?:на\\s+)?(\\S+@\\S+)?\\s*(.*)")
            .find(s)?.let { m ->
                val to = m.groupValues[1].orEmpty()
                val body = m.groupValues[2].removePrefix("текст ").trim()
                val uri = Uri.parse("mailto:" + to)
                    .buildUpon()
                    .appendQueryParameter("body", body)
                    .build()
                val i = Intent(Intent.ACTION_SENDTO, uri).task(ctx)
                return CommandEngine.Outcome(
                    if (to.isEmpty()) "Открываю новое письмо, сэр." else "Письмо для $to готово.",
                    i
                )
            }

        Regex("^(?:отправь|напиши|скинь)\\s+(?:в|во)\\s+(?:ватсап|вотсап|whatsapp|телеграм|telegram|вайбер|viber)\\s+(.+)")
            .find(s)?.let { m ->
                val text = m.groupValues[1].trim()
                return CommandEngine.Outcome("Открываю окно отправки — выберите контакт, сэр.", shareIntent(ctx, text))
            }

        Regex("^(?:поделиться|поделюсь|репост|отправь текст)\\s+(.+)").find(s)?.let { m ->
            val text = m.groupValues[1].trim()
            return CommandEngine.Outcome("Открываю окно отправки, сэр.", shareIntent(ctx, text))
        }

        return null
    }

    private fun shareIntent(ctx: Context, text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }.task(ctx)

    // --------------------------------------------------------- журнал звонков

    fun callLog(ctx: Context, s: String): CommandEngine.Outcome? {
        if (!Regex("кто звонил|последн[а-яa-z0-9]+ (?:звонок|вызов)|последн[а-яa-z0-9]+ пропущенн[а-яa-z0-9]+|перезвони|кто набирал")
                .containsMatchIn(s)
        ) return null

        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return if (ctx is android.app.Activity) {
                ctx.requestPermissions(arrayOf(android.Manifest.permission.READ_CALL_LOG), 42)
                CommandEngine.Outcome("Дайте доступ к журналу звонков (появился запрос) и повторите команду, сэр.")
            } else {
                CommandEngine.Outcome("Нужен доступ к журналу звонков, сэр. Откройте приложение и выдайте разрешение.", openApp = true)
            }
        }

        val missed = Regex("пропущенн").containsMatchIn(s)
        val cols = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME)
        val cur = try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, null, null,
                CallLog.Calls.DATE + " DESC LIMIT 10")
        } catch (e: Exception) { null }
        cur?.use { c ->
            var found = false
            while (c.moveToNext()) {
                val type = c.getInt(1)
                if (missed && type != CallLog.Calls.MISSED_TYPE) continue
                val num = c.getString(0).orEmpty()
                val name = c.getString(3)
                val date = java.text.SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(java.util.Date(c.getLong(2)))
                val who = when {
                    !name.isNullOrBlank() -> name
                    num.isNotBlank() -> num
                    else -> "неизвестный номер"
                }
                val kind = when (type) {
                    CallLog.Calls.MISSED_TYPE -> "пропущенный"
                    CallLog.Calls.OUTGOING_TYPE -> "исходящий"
                    else -> "входящий"
                }
                return if (Regex("перезвони").containsMatchIn(s)) {
                    CommandEngine.Outcome("Перезваниваю: $who.",
                        Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).task(ctx))
                } else {
                    found = true
                    CommandEngine.Outcome("Последний $kind звонок: $who ($date).")
                }
            }
            if (!found) return CommandEngine.Outcome("В журнале пусто, сэр.")
        }
        return CommandEngine.Outcome("Не смог прочитать журнал звонков, сэр.")
    }

    // ------------------------------------------------------------- маркет и приложения

    fun market(ctx: Context, s: String): CommandEngine.Outcome? {
        Regex("^(?:установи|скачай|установить|скачать|поставь(?:\\s+приложение|\\s+игру)?" +
            "|найди в (?:плей маркете|магазине))\\s+(?:приложение\\s+|игру\\s+)?(.+)$")
            .find(s)?.let { m ->
                val q = m.groupValues[1].trim()
                if (q.length < 2) return@let
                // «поставь музыку queen», «скачай фильм» — это плеер и интернет, не маркет
                if (Regex("музык|песн|трек|радио|клип|видео|фильм|сериал").containsMatchIn(q)) return@let
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                var i = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$enc")).task(ctx)
                if (i.resolveActivity(ctx.packageManager) == null) {
                    i = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$enc")).task(ctx)
                }
                return CommandEngine.Outcome("Ищу «$q» в Play Market.", i)
            }

        if (Regex("мои приложения|мои обновления|обнови приложения|установленные обновления").containsMatchIn(s)) {
            var i = Intent(Intent.ACTION_VIEW, Uri.parse("market://apps")).task(ctx)
            if (i.resolveActivity(ctx.packageManager) == null) {
                i = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps")).task(ctx)
            }
            return CommandEngine.Outcome("Открываю мои приложения.", i)
        }

        Regex("^(?:удали|снеси|убери)\\s+(?:приложение\\s+)?(.+)$").find(s)?.let { m ->
            val name = m.groupValues[1].trim()
            // «удали молоко из списка покупок» и «удали напоминания» — не про приложения
            if (Regex("списк|покупк|заметк|напоминан|истори|будильник|таймер").containsMatchIn(name)) return@let
            val pkg = findPackage(ctx, name)
            return if (pkg == null) {
                CommandEngine.Outcome("Не нашёл приложение «$name», сэр.")
            } else {
                CommandEngine.Outcome("Открываю удаление «$name».",
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).task(ctx))
            }
        }

        Regex("^(?:информация|сведения|данные)\\s+(?:о|по)\\s+приложении\\s+(.+)$").find(s)?.let { m ->
            val name = m.groupValues[1].trim()
            val pkg = findPackage(ctx, name)
            return if (pkg == null) {
                CommandEngine.Outcome("Не нашёл приложение «$name», сэр.")
            } else {
                CommandEngine.Outcome("Открываю сведения о «$name».",
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).task(ctx))
            }
        }

        return null
    }

    private fun findPackage(ctx: Context, name: String): String? {
        val t = name.lowercase(Locale.getDefault()).trim('.', '!', '?')
        if (t.length < 2) return null
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try { ctx.packageManager.queryIntentActivities(main, 0) } catch (e: Exception) { return null }
        for (ri in apps) {
            val label = try { ri.loadLabel(ctx.packageManager).toString().lowercase(Locale.getDefault()) } catch (e: Exception) { "" }
            if (label == t || label.startsWith(t) || (t.length >= 4 && label.contains(t))) {
                return ri.activityInfo.packageName
            }
        }
        return null
    }

    // ---------------------------------------------------------- панели настроек

    fun panels(ctx: Context, s: String): CommandEngine.Outcome? {
        fun panel(reply: String, intent: Intent) = CommandEngine.Outcome(reply, intent.task(ctx))

        if (Regex("режим разработчика|для разработчиков|отладка по usb|developer").containsMatchIn(s)) {
            return panel("Открываю меню разработчика.", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        if (Regex("доступность|спец(?:иальные)? возможности|talkback").containsMatchIn(s)) {
            return panel("Открываю спецвозможности.", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (Regex("аккаунт|учетн[а-яa-z0-9]+ запис|пользовател[а-яa-z0-9]+ и аккаунты").containsMatchIn(s)) {
            return panel("Открываю аккаунты.", Intent(Settings.ACTION_SYNC_SETTINGS))
        }
        if (Regex("геолокац|местоположени|gps|глонасс|локаци").containsMatchIn(s)) {
            return panel("Открываю настройки местоположения.", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        if (Regex("отпечаток|биометр|face ?id|разблокировка по лицу").containsMatchIn(s)) {
            return panel("Открываю настройки биометрии.", Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        if (Regex("обновлени[а-яa-z0-9]+ системы|обновить андроид|обновления системы|версия прошивки").containsMatchIn(s)) {
            val i = Intent("android.settings.SYSTEM_UPDATE_SETTINGS")
            return if (i.resolveActivity(ctx.packageManager) != null) panel("Открываю обновления системы.", i)
            else panel("Открываю сведения о телефоне.", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
        if (Regex("^vpn|впн|виртуальн[а-яa-z0-9]+ частн[а-яa-z0-9]+ сет").containsMatchIn(s)) {
            return panel("Открываю настройки VPN.", Intent(Settings.ACTION_VPN_SETTINGS))
        }
        if (Regex("обои|фон рабочего стола|смени обои|заставк").containsMatchIn(s)) {
            return panel("Открываю выбор обоев.", Intent(Intent.ACTION_SET_WALLPAPER))
        }
        if (Regex("загрузк|скачанные файлы|мои файлы|проводник|файловый менеджер").containsMatchIn(s)) {
            val i = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            return if (i.resolveActivity(ctx.packageManager) != null) panel("Открываю загрузки.", i)
            else panel("Открываю хранилище.", Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        }
        if (Regex("уведомлени[а-яa-z0-9]+ джарвиса|настройки уведомлений|разрешени[а-яa-z0-9]+ на уведомления").containsMatchIn(s)) {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            return panel("Открываю настройки уведомлений «Джарвис».", i)
        }
        if (Regex("сколько трафика|использование данных|мобильный трафик|расход трафика").containsMatchIn(s)) {
            return panel("Открываю статистику трафика.", Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
        }
        if (Regex("о телефоне|информация об устройстве|сведения о телефоне|серийный номер").containsMatchIn(s)) {
            return panel("Открываю сведения о телефоне.", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
        if (Regex("печать|принтер").containsMatchIn(s)) {
            return panel("Открываю настройки печати.", Intent(Settings.ACTION_PRINT_SETTINGS))
        }
        if (Regex("настройки батареи|использование батареи|расход батареи|статистика батареи").containsMatchIn(s)) {
            val i = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            return if (i.resolveActivity(ctx.packageManager) != null) panel("Открываю статистику батареи.", i)
            else panel("Открываю энергосбережение.", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
        if (Regex("язык и ввод|раскладк|клавиатур").containsMatchIn(s)) {
            return panel("Открываю язык и ввод.", Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        return null
    }

    /** Короткая справка по системным командам. */
    fun help(): String = """
        Команды для телефона, сэр:
        • «сколько заряда», «здоровье батареи», «сколько памяти», «сколько оперативки»
        • «какой у меня ip», «какой оператор», «какой процессор», «разрешение экрана»
        • «блютуз включен», «вайфай включен», «режим полёта включен», «какая яркость»
        • «яркость 40 процентов», «сделай ярче», «громкость звонка 50 процентов»
        • «сколько приложений установлено», «сколько работает телефон», «какие датчики»
        • «включи/выключи шумоподавление», «шумоподавление включено?»
        • «установи приложение шахматы», «удали приложение …», «мои приложения»
        • «открой режим разработчика / vpn / обои / загрузки / о телефоне»
        • «кто звонил последним», «перезвони», «отправь письмо на …», «поделиться …»
    """.trimIndent()
}
