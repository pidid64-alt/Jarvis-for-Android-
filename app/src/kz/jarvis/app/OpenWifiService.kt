package kz.jarvis.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Когда у телефона нет подтверждённого доступа в интернет, ищет видимые сети
 * без пароля и просит Android подключиться к лучшим из них.
 *
 * Android 8–9 разрешает приложению подключить открытую сеть напрямую.
 * Android 10+ намеренно запрещает тихую смену сети: используем официальный
 * WifiNetworkSuggestion. При первом использовании система просит владельца
 * один раз разрешить предложения Джарвиса, после чего выбирает сеть сама.
 * Страницы авторизации и условия публичных точек служба не обходит.
 */
class OpenWifiService : Service() {

    companion object {
        const val CH_ID = "jarvis_open_wifi"
        const val ACTION_STATE_CHANGED = "kz.jarvis.app.action.OPEN_WIFI_STATE"

        private const val ACTION_SCAN = "kz.jarvis.app.action.OPEN_WIFI_SCAN"
        private const val ACTION_ENERGY_CHANGED = "kz.jarvis.app.action.OPEN_WIFI_ENERGY_CHANGED"
        private const val ACTION_OFF = "kz.jarvis.app.action.OPEN_WIFI_OFF"
        private const val NOTIFICATION_ID = 31
        private const val SCAN_TIMEOUT_MS = 45_000L
        private const val MIN_ACTIVE_SCAN_MS = 90_000L
        private const val RETRY_MS = 45_000L
        private const val MAX_RESULT_AGE_MS = 6 * 60_000L

        /** Разрешения, без которых Android не отдаёт SSID и результаты скана. */
        fun missingPermissions(c: Context): List<String> {
            val out = ArrayList<String>()
            if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // На Android 12+ точную и примерную геолокацию нужно просить вместе.
                out += Manifest.permission.ACCESS_FINE_LOCATION
                out += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                out += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            return out.distinct()
        }

        fun permissionsGranted(c: Context): Boolean = missingPermissions(c).isEmpty()

        /** Сканирование Wi‑Fi на Android 9+ также требует включённой геолокации. */
        fun locationEnabled(c: Context): Boolean = try {
            if (Build.VERSION.SDK_INT < 28) true
            else (c.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
        } catch (_: Exception) {
            false
        }

        fun start(c: Context) {
            if (!Prefs.openWifiOn(c)) return
            val i = Intent(c, OpenWifiService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Exception) {
                setStatus(c, "Android не разрешил запустить поиск в фоне. Откройте Джарвис ещё раз.")
            }
        }

        fun scanNow(c: Context) {
            if (!Prefs.openWifiOn(c)) return
            // Сразу даём видимую обратную связь. Если Android не запустит
            // службу, пользователь увидит уже конкретную ошибку из catch.
            setStatus(c, "Ручная проверка запущена — проверяю подключение и список Wi‑Fi…")
            val i = Intent(c, OpenWifiService::class.java).setAction(ACTION_SCAN)
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Exception) {
                setStatus(c, "Не смог запустить проверку в фоне: ${e.message?.take(80) ?: "запрещено Android"}.")
            }
        }

        /** Пересчитать интервалы после смены профиля батареи. */
        fun energyChanged(c: Context) {
            if (!Prefs.openWifiOn(c)) return
            val i = Intent(c, OpenWifiService::class.java).setAction(ACTION_ENERGY_CHANGED)
            try { c.startService(i) } catch (_: Exception) { }
        }

        fun disable(c: Context) {
            Prefs.setOpenWifiOn(c, false)
            val cleared = clearSuggestions(c)
            try { c.stopService(Intent(c, OpenWifiService::class.java)) } catch (_: Exception) { }
            setStatus(
                c,
                if (cleared) "Автоподключение выключено."
                else "Автоподключение выключено, но Android не дал удалить прежнее сетевое предложение."
            )
        }

        /**
         * Удаляет только те предложения сетей, которые создал Джарвис.
         * При ошибке сохраняет их локальный список, чтобы повторить удаление
         * после возврата разрешения, а не потерять управление предложениями.
         */
        fun clearSuggestions(c: Context): Boolean {
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                val records = Prefs.openWifiLegacyNetworks(c)
                if (records.isEmpty()) return true
                val removed = try {
                    val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val current = wifi.configuredNetworks.orEmpty().associateBy { it.networkId }
                    var ok = true
                    var changed = false
                    for (record in records) {
                        val id = record.substringBefore('|').toIntOrNull() ?: continue
                        val ssid = record.substringAfter('|', "")
                        val config = current[id] ?: continue // уже удалена пользователем
                        val stillOurs = ssid.isNotEmpty() &&
                            config.SSID == OpenWifiLogic.quotedSsid(ssid) &&
                            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                        if (stillOurs) {
                            changed = true
                            if (!wifi.removeNetwork(id)) ok = false
                        }
                    }
                    if (changed && !wifi.saveConfiguration()) ok = false
                    ok
                } catch (_: Exception) {
                    false
                }
                if (removed) Prefs.setOpenWifiLegacyNetworks(c, emptySet())
                return removed
            }
            val records = Prefs.openWifiSuggestions(c)
            if (records.isEmpty()) return true
            val saved = records
                .mapNotNull(OpenWifiLogic::decode)
                .mapNotNull { suggestionFor(it) }
            if (saved.isEmpty()) return false
            val removed = try {
                val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifi.removeNetworkSuggestions(saved) == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
            } catch (_: Exception) {
                false
            }
            if (removed) Prefs.setOpenWifiSuggestions(c, emptySet())
            return removed
        }

        /** Текст для настроек и голосовой команды. */
        fun status(c: Context): String = when {
            !Prefs.openWifiOn(c) &&
                (Prefs.openWifiSuggestions(c).isNotEmpty() || Prefs.openWifiLegacyNetworks(c).isNotEmpty()) ->
                "Автоподключение выключено. Android не дал удалить прежнюю сеть; верните разрешения и выключите режим ещё раз."
            !Prefs.openWifiOn(c) -> "Автоподключение к Wi‑Fi без пароля выключено."
            !permissionsGranted(c) -> "Нужны разрешения «Точная геолокация» и «Устройства поблизости»."
            !locationEnabled(c) -> "Включите геолокацию: без неё Android скрывает результаты Wi‑Fi."
            else -> Prefs.openWifiStatus(c)
        }

        private fun setStatus(c: Context, text: String): Boolean {
            // SharedPreferences + broadcast на каждом NetworkCallback заметно
            // будили процесс. Одинаковое состояние второй раз не записываем.
            if (Prefs.openWifiStatus(c) == text) return false
            Prefs.setOpenWifiStatus(c, text)
            try {
                c.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(c.packageName))
            } catch (_: Exception) { }
            return true
        }

        /** Строится и при восстановлении списка после перезапуска процесса. */
        private fun suggestionFor(candidate: OpenWifiLogic.Candidate): WifiNetworkSuggestion? {
            if (Build.VERSION.SDK_INT < 29) return null
            return try {
                WifiNetworkSuggestion.Builder()
                    .setSsid(candidate.ssid)
                    .apply {
                        if (candidate.enhancedOpen) setIsEnhancedOpen(true)
                    }
                    .build()
            } catch (_: Exception) {
                null
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private lateinit var connectivity: ConnectivityManager
    private var powerManager: PowerManager? = null
    private var initialized = false
    private var receiverRegistered = false
    private var networkCallbackRegistered = false
    private var lastScanAt = 0L
    private var lastSuggestedSignature = ""
    private var lastNotificationState = ""
    private var scheduledScanAt = Long.MAX_VALUE
    private var scanInFlight = false
    private var lastScanRequestAccepted = false
    /** Явное нажатие «Проверить сейчас» может тестировать сеть даже при mobile data. */
    private var manualScan = false
    private var emptyScans = 0
    private var networkState = NetworkState.UNKNOWN

    private enum class NetworkState { UNKNOWN, ONLINE, CAPTIVE, OFFLINE }

    /** На старых Android не повторяем одну неудачную сеть сразу же. */
    private val blockedUntil = HashMap<String, Long>()
    private var legacyAttemptSsid: String? = null
    private var legacyAttemptAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            disable(this)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.openWifiOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Для первого startForegroundService уведомление должно появиться
        // сразу. Повторные scanNow/смена профиля уже работающую foreground-
        // службу не переуведомляют.
        if (!initialized) {
            if (!goForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            initialize()
        }

        when (intent?.action) {
            ACTION_SCAN -> {
                // Ручная кнопка — диагностическое явное действие пользователя:
                // проверяем открытые сети даже при рабочем mobile data. Фоновая
                // автоматика по-прежнему сканирует только без интернета.
                manualScan = true
                emptyScans = 0
                val online = validatedInternet()
                networkState = if (online) NetworkState.ONLINE else NetworkState.OFFLINE
                setState(
                    if (online)
                        "Интернет работает, но по вашей команде проверяю открытые Wi‑Fi…"
                    else
                        "Интернета нет — запускаю ручной поиск Wi‑Fi без пароля…"
                )
                scheduleScan(0, force = true)
            }
            ACTION_ENERGY_CHANGED -> rescheduleForEnergyMode()
            else -> evaluateConnection()
        }
        return START_STICKY
    }

    private fun initialize() {
        initialized = true
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(scanReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) { }

        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { unregisterReceiver(scanReceiver) } catch (_: Exception) { }
        }
        if (networkCallbackRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        }
        initialized = false
        powerManager = null
        scheduledScanAt = Long.MAX_VALUE
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val state = Prefs.openWifiStatus(this)
        startForeground(NOTIFICATION_ID, notification(state))
        lastNotificationState = state
        true
    } catch (e: Exception) {
        setState("Не удалось запустить фоновый поиск Wi‑Fi: ${e.message?.take(90) ?: "ошибка Android"}.")
        false
    }

    private fun notification(state: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            31,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SettingsActivity.EXTRA_OPEN_WIFI_SETUP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val off = PendingIntent.getService(
            this,
            32,
            Intent(this, OpenWifiService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_mic_bg)
            .setContentTitle("Джарвис · бесплатный Wi‑Fi")
            .setContentText(state)
            .setStyle(Notification.BigTextStyle().bigText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Выключить", off)
            .build()
    }

    private fun setState(text: String) {
        val changed = setStatus(this, text)
        if (!changed && text == lastNotificationState) return
        lastNotificationState = text
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(text))
        } catch (_: Exception) { }
    }

    private fun powerSave(): Boolean = try {
        powerManager?.isPowerSaveMode == true
    } catch (_: Exception) {
        false
    }

    private fun deviceIdle(): Boolean = try {
        Build.VERSION.SDK_INT >= 23 && powerManager?.isDeviceIdleMode == true
    } catch (_: Exception) {
        false
    }

    private fun emptyRetryDelay(): Long {
        emptyScans = (emptyScans + 1).coerceAtMost(20)
        return Energy.wifiEmptyRetryMs(Prefs.energyMode(this), emptyScans, powerSave())
    }

    private fun foundRetryDelay(): Long =
        Energy.wifiFoundRetryMs(Prefs.energyMode(this), powerSave())

    private fun rescheduleForEnergyMode() {
        if (networkState == NetworkState.UNKNOWN) {
            evaluateConnection()
            return
        }
        if (networkState == NetworkState.OFFLINE || networkState == NetworkState.CAPTIVE) {
            val delay = if (emptyScans > 0) {
                Energy.wifiEmptyRetryMs(Prefs.energyMode(this), emptyScans, powerSave())
            } else {
                foundRetryDelay()
            }
            scheduleScan(delay, force = true)
        }
    }

    // ------------------------------------------------------------- сеть

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { evaluateConnection() }
        }

        override fun onLost(network: Network) {
            main.post { evaluateConnection() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            main.post { evaluateConnection() }
        }
    }

    /**
     * Проверяем именно активную сеть по умолчанию. allNetworks содержит ещё
     * «доживающие» мобильные сети: на Android 13 они могли несколько секунд
     * оставаться VALIDATED после выключения данных и полностью блокировать
     * ручной Wi‑Fi scan, хотя трафик приложения через них уже не шёл.
     */
    private fun activeCapabilities(): NetworkCapabilities? = try {
        val active = connectivity.activeNetwork
        if (active == null) null else connectivity.getNetworkCapabilities(active)
    } catch (_: Exception) {
        null
    }

    private fun validatedInternet(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun validatedWifiInternet(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun captivePortal(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
    }

    private fun evaluateConnection() {
        if (!Prefs.openWifiOn(this)) {
            stopSelf()
            return
        }
        val previous = networkState
        val next = when {
            validatedInternet() -> NetworkState.ONLINE
            captivePortal() -> NetworkState.CAPTIVE
            else -> NetworkState.OFFLINE
        }
        networkState = next

        when (next) {
            NetworkState.ONLINE -> {
                if (manualScan) {
                    // Не отменяем явно запрошенный диагностический scan из-за
                    // мобильной сети или запоздалого VALIDATED callback.
                    setState("Интернет работает; продолжаю ручную проверку открытых Wi‑Fi…")
                    return
                }
                cancelScheduledScan()
                emptyScans = 0
                legacyAttemptSsid = null
                // Если интернет вернулся не через Wi‑Fi (например, ожила
                // мобильная сеть), убираем предложения: режим не должен
                // перетягивать телефон на открытую точку при рабочей связи.
                if (previous != NetworkState.ONLINE && !validatedWifiInternet()) {
                    clearSuggestions(this)
                    lastSuggestedSignature = ""
                }
                setState("Интернет доступен. Если связь пропадёт, начну поиск автоматически.")
            }
            NetworkState.CAPTIVE -> {
                cancelScheduledScan()
                setState("Открытая точка найдена, но требует входа или принятия условий Android.")
                scheduleScan(foundRetryDelay())
            }
            NetworkState.OFFLINE -> {
                // NetworkCallback бывает очень шумным. Немедленный scan нужен
                // только при реальном переходе online → offline; повторные
                // capabilities-события не сбрасывают экспоненциальный backoff.
                if (previous != NetworkState.OFFLINE) {
                    emptyScans = 0
                    scheduleScan(1_000L, force = true)
                } else if (scheduledScanAt == Long.MAX_VALUE && !scanInFlight) {
                    scheduleScan(if (emptyScans > 0) {
                        Energy.wifiEmptyRetryMs(Prefs.energyMode(this), emptyScans, powerSave())
                    } else foundRetryDelay())
                }
            }
            NetworkState.UNKNOWN -> Unit
        }
    }

    // ------------------------------------------------------------- сканирование

    private var forceNextScan = false

    private fun cancelScheduledScan() {
        main.removeCallbacks(scanTask)
        main.removeCallbacks(scanTimeoutTask)
        scheduledScanAt = Long.MAX_VALUE
        scanInFlight = false
        forceNextScan = false
        manualScan = false
    }

    private fun scheduleScan(delay: Long, force: Boolean = false) {
        val safeDelay = delay.coerceAtLeast(0L)
        val target = SystemClock.elapsedRealtime() + safeDelay
        if (!force && scheduledScanAt <= target) return
        if (force) forceNextScan = true
        main.removeCallbacks(scanTask)
        scheduledScanAt = target
        main.postDelayed(scanTask, safeDelay)
    }

    private val scanTask = Runnable {
        scheduledScanAt = Long.MAX_VALUE
        attemptScan()
    }

    private val scanTimeoutTask = Runnable {
        scanInFlight = false
        if (manualScan || !validatedInternet()) processResults(readResults())
    }

    private fun attemptScan() {
        if (!Prefs.openWifiOn(this)) {
            manualScan = false
            return
        }
        if (!manualScan && validatedInternet()) return
        if (captivePortal()) {
            manualScan = false
            setState("Открытая точка требует входа или принятия условий Android — автоматический обход отключён.")
            scheduleScan(foundRetryDelay())
            return
        }
        val inactiveDelay = Energy.wifiEmptyRetryMs(
            Prefs.energyMode(this),
            4,
            powerSave()
        )
        if (!permissionsGranted(this)) {
            manualScan = false
            setState("Не могу искать сети: дайте точную геолокацию и доступ к устройствам поблизости.")
            scheduleScan(inactiveDelay)
            return
        }
        if (!locationEnabled(this)) {
            manualScan = false
            setState("Включите геолокацию: Android не показывает результаты Wi‑Fi, пока она выключена.")
            scheduleScan(inactiveDelay)
            return
        }
        if (deviceIdle()) {
            manualScan = false
            // В Doze активный radio scan почти всегда будет отложен системой,
            // но всё равно разбудит процесс. Ждём экрана либо длинного таймера.
            setState("Интернета нет. Телефон спит — продолжу поиск при включении экрана.")
            scheduleScan(inactiveDelay)
            return
        }
        if (!wifi.isWifiEnabled) {
            // До Android 10 обычному приложению ещё разрешалось включать Wi‑Fi.
            if (Build.VERSION.SDK_INT < 29) {
                try { @Suppress("DEPRECATION") wifi.isWifiEnabled = true } catch (_: Exception) { }
            }
            if (!wifi.isWifiEnabled) {
                manualScan = false
                setState("Wi‑Fi выключен. Включите его в системной панели — поиск продолжится сам.")
                scheduleScan(inactiveDelay)
                return
            }
        }

        val now = SystemClock.elapsedRealtime()
        val force = forceNextScan
        forceNextScan = false
        val wait = MIN_ACTIVE_SCAN_MS - (now - lastScanAt)
        if (!force && lastScanAt > 0L && wait > 0L) {
            scheduleScan(wait)
            return
        }
        lastScanAt = now
        setState("Интернета нет — ищу рядом Wi‑Fi без пароля…")

        val started = try {
            @Suppress("DEPRECATION")
            wifi.startScan()
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        main.removeCallbacks(scanTimeoutTask)
        lastScanRequestAccepted = started
        scanInFlight = started
        if (!started) {
            // Android мог ограничить частоту скана; свежий системный кэш всё
            // равно полезен, но старые результаты отбрасываются ниже.
            processResults(readResults())
        } else {
            // Один именованный timeout: broadcast удалит его. Раньше после
            // каждого успешного broadcast оставалась ещё одна лямбда на 45 с.
            main.postDelayed(scanTimeoutTask, SCAN_TIMEOUT_MS)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    main.removeCallbacks(scanTimeoutTask)
                    scanInFlight = false
                    processResults(readResults())
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> if (wifi.isWifiEnabled) {
                    emptyScans = 0
                    scheduleScan(500L, force = true)
                }
                Intent.ACTION_SCREEN_ON -> if (!validatedInternet()) {
                    // Пользователь взял телефон — хороший момент быстро
                    // проверить сеть, даже если фоновый backoff уже вырос.
                    emptyScans = 0
                    scheduleScan(700L)
                }
                LocationManager.MODE_CHANGED_ACTION -> if (locationEnabled(this@OpenWifiService)) {
                    emptyScans = 0
                    scheduleScan(700L, force = true)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> rescheduleForEnergyMode()
            }
        }
    }

    private fun readResults(): List<ScanResult> = try {
        @Suppress("DEPRECATION")
        wifi.scanResults ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun processResults(results: List<ScanResult>) {
        main.removeCallbacks(scanTimeoutTask)
        main.removeCallbacks(scanTask)
        scheduledScanAt = Long.MAX_VALUE
        scanInFlight = false
        val wasManual = manualScan
        manualScan = false
        if (!Prefs.openWifiOn(this) || (!wasManual && validatedInternet()) || captivePortal()) return

        val nowMs = SystemClock.elapsedRealtime()
        blockedUntil.entries.removeAll { it.value <= nowMs }
        val nowUs = nowMs * 1_000L
        val fresh = results.filter { result ->
            val ageMs = if (result.timestamp <= 0L) 0L else (nowUs - result.timestamp) / 1_000L
            ageMs in 0..MAX_RESULT_AGE_MS
        }
        val seen = fresh.map { result ->
            @Suppress("DEPRECATION")
            OpenWifiLogic.Seen(result.SSID.orEmpty(), result.capabilities.orEmpty(), result.level)
        }
        var choices = OpenWifiLogic.candidates(seen, blockedUntil.keys)

        if (Build.VERSION.SDK_INT < 29) {
            // Android 8–9 не умеет WifiNetworkSuggestion и OWE через старую
            // конфигурацию; выбираем обычную открытую сеть.
            choices = choices.filterNot { it.enhancedOpen }
        } else {
            val oweSupported = try { wifi.isEnhancedOpenSupported } catch (_: Exception) { false }
            if (!oweSupported) choices = choices.filterNot { it.enhancedOpen }
        }
        if (choices.isEmpty()) {
            val openSeen = seen.count {
                val security = OpenWifiLogic.security(it.capabilities)
                security == OpenWifiLogic.Security.OPEN ||
                    security == OpenWifiLogic.Security.ENHANCED_OPEN
            }
            val detail = when {
                fresh.isEmpty() && !lastScanRequestAccepted ->
                    "Android отклонил активный scan и не отдал свежий кэш. " +
                        "Подождите 30 секунд и нажмите «Проверить сейчас» ещё раз."
                fresh.isEmpty() ->
                    "Android завершил scan, но не отдал ни одной свежей сети. " +
                        "Проверьте, что системный список Wi‑Fi видит точку."
                openSeen == 0 ->
                    "Android показал сетей: ${seen.size}, но все они защищены паролем."
                else ->
                    "Android показал открытых сетей: $openSeen, но прошивка не разрешила их использовать."
            }
            setState((if (validatedInternet()) "Ручная проверка. " else "Интернета нет. ") + detail)
            if (!validatedInternet()) scheduleScan(emptyRetryDelay())
            return
        }

        emptyScans = 0
        if (Build.VERSION.SDK_INT >= 29) suggest(choices.take(4))
        else connectLegacy(choices)
        // После передачи сети Android новые активные scan ничего не ускоряют.
        if (!validatedInternet()) scheduleScan(foundRetryDelay())
    }

    // ------------------------------------------------------ Android 10+

    private fun suggest(choices: List<OpenWifiLogic.Candidate>) {
        val signature = choices.joinToString("|") { OpenWifiLogic.encode(it) }
        val stored = Prefs.openWifiSuggestions(this)
        val desired = choices.map(OpenWifiLogic::encode).toSet()
        if (signature == lastSuggestedSignature || stored == desired) {
            lastSuggestedSignature = signature
            val best = choices.first()
            setState(
                "Нашёл «${safeName(best.ssid)}». Android выбирает сеть; если появится запрос, разрешите предложения Джарвиса."
            )
            return
        }

        if (!clearSuggestions(this)) {
            setState("Android не дал заменить прежнее предложение Wi‑Fi. Проверьте разрешение «Устройства поблизости».")
            return
        }
        val suggestions = choices.mapNotNull { candidate -> suggestionFor(candidate) }
        if (suggestions.isEmpty()) {
            setState("Нашёл сеть без пароля, но эта прошивка не умеет добавить её автоматически.")
            return
        }

        val result = try {
            wifi.addNetworkSuggestions(suggestions)
        } catch (_: SecurityException) {
            -100
        } catch (_: Exception) {
            -101
        }
        if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Prefs.setOpenWifiSuggestions(this, desired)
            lastSuggestedSignature = signature
            setState(
                "Нашёл «${safeName(choices.first().ssid)}» и передал Android для автоподключения. " +
                    "При первом запуске подтвердите системный запрос один раз."
            )
        } else {
            setState(
                "Android не принял открытую сеть (код $result). Откройте системную панель Wi‑Fi и разрешите предложения Джарвиса."
            )
        }
    }

    // ------------------------------------------------------ Android 8–9

    @Suppress("DEPRECATION")
    private fun connectLegacy(choices: List<OpenWifiLogic.Candidate>) {
        val now = SystemClock.elapsedRealtime()
        val previous = legacyAttemptSsid
        if (previous != null && now - legacyAttemptAt >= RETRY_MS && !validatedInternet()) {
            blockedUntil[previous] = now + 10 * 60_000L
        }
        val candidate = choices.firstOrNull { (blockedUntil[it.ssid] ?: 0L) <= now }
        if (candidate == null) {
            setState("Найденные открытые сети не дали интернет. Повторю попытку позже.")
            return
        }

        val config = WifiConfiguration().apply {
            SSID = OpenWifiLogic.quotedSsid(candidate.ssid)
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            status = WifiConfiguration.Status.ENABLED
        }

        var networkId = try { wifi.addNetwork(config) } catch (_: Exception) { -1 }
        val createdByJarvis = networkId >= 0
        if (networkId < 0) {
            networkId = try {
                wifi.configuredNetworks
                    ?.firstOrNull { existing ->
                        existing.SSID == config.SSID &&
                            existing.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                    }
                    ?.networkId ?: -1
            } catch (_: Exception) {
                -1
            }
        }

        if (networkId < 0) {
            blockedUntil[candidate.ssid] = now + 10 * 60_000L
            setState("Нашёл «${safeName(candidate.ssid)}», но Android не разрешил добавить сеть.")
            return
        }

        if (createdByJarvis) {
            val records = Prefs.openWifiLegacyNetworks(this).toMutableSet()
            records += "$networkId|${candidate.ssid}"
            Prefs.setOpenWifiLegacyNetworks(this, records)
        }
        try { wifi.saveConfiguration() } catch (_: Exception) { }
        val enabled = try { wifi.enableNetwork(networkId, true) } catch (_: Exception) { false }
        if (enabled) {
            try { wifi.reconnect() } catch (_: Exception) { }
            legacyAttemptSsid = candidate.ssid
            legacyAttemptAt = now
            setState("Нашёл «${safeName(candidate.ssid)}» — подключаюсь автоматически…")
        } else {
            blockedUntil[candidate.ssid] = now + 10 * 60_000L
            setState("Нашёл «${safeName(candidate.ssid)}», но подключение не удалось. Ищу другую сеть.")
        }
    }

    /** Не даём необычному SSID ломать или растягивать уведомление. */
    private fun safeName(ssid: String): String =
        ssid.replace(Regex("[\\r\\n\\t]"), " ").take(42)
}
