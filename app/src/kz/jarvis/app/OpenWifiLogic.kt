package kz.jarvis.app

import java.util.Locale

/**
 * Чистая логика выбора Wi‑Fi без пароля.
 *
 * Здесь нет Android API, поэтому правила безопасности и сортировку можно
 * проверять обычными JVM-тестами. Сеть считается подходящей, только если в
 * capabilities нет ни одного способа аутентификации. OWE (Enhanced Open)
 * тоже не требует пароля, но шифрует радиоэфир, поэтому получает небольшой
 * приоритет перед обычной открытой сетью.
 */
object OpenWifiLogic {

    enum class Security {
        /** Обычная открытая сеть: пароля и шифрования нет. */
        OPEN,

        /** Enhanced Open / OWE: пароля нет, трафик по радио зашифрован. */
        ENHANCED_OPEN,

        /** WEP/WPA/WPA2/WPA3/Enterprise либо другой защищённый тип. */
        PROTECTED
    }

    data class Seen(
        val ssid: String,
        val capabilities: String,
        val level: Int
    )

    data class Candidate(
        val ssid: String,
        val level: Int,
        val enhancedOpen: Boolean
    )

    enum class Command { ENABLE, DISABLE, SCAN, STATUS }

    /** Определяет тип защиты по строке ScanResult.capabilities. */
    fun security(capabilities: String): Security {
        val cap = capabilities.uppercase(Locale.ROOT)

        // OWE не имеет общего пароля. Проверяем раньше остальных маркеров:
        // переходный режим иногда содержит сразу несколько служебных флагов.
        if (cap.contains("OWE")) return Security.ENHANCED_OPEN

        val protectedMarkers = listOf(
            // WPA/RSN берём целиком: неизвестный новый вариант безопаснее
            // пропустить, чем ошибочно принять за сеть без пароля.
            "WEP", "WPA", "RSN", "PSK", "SAE", "EAP", "IEEE8021X", "WAPI",
            "DPP", "OSEN", "SUITE_B", "FILS", "WPS", "PRIVACY", "PASSPOINT"
        )
        return if (protectedMarkers.any { cap.contains(it) }) {
            Security.PROTECTED
        } else {
            Security.OPEN
        }
    }

    /**
     * Оставляет только видимые сети без пароля, убирает дубли SSID и выбирает
     * сильнейшие. [blockedSsids] нужен службе, чтобы временно не повторять
     * неудачное подключение.
     */
    fun candidates(
        seen: List<Seen>,
        blockedSsids: Set<String> = emptySet(),
        limit: Int = 4
    ): List<Candidate> {
        if (limit <= 0) return emptyList()

        val bestBySsid = LinkedHashMap<String, Candidate>()
        for (network in seen) {
            val ssid = network.ssid
            if (ssid.isBlank() || ssid == "<unknown ssid>" || ssid in blockedSsids) continue
            val kind = security(network.capabilities)
            if (kind == Security.PROTECTED) continue

            val candidate = Candidate(
                ssid = ssid,
                level = network.level,
                enhancedOpen = kind == Security.ENHANCED_OPEN
            )
            val old = bestBySsid[ssid]
            if (old == null || score(candidate) > score(old)) bestBySsid[ssid] = candidate
        }

        return bestBySsid.values
            .sortedWith(
                compareByDescending<Candidate> { score(it) }
                    .thenBy { it.ssid.lowercase(Locale.ROOT) }
            )
            .take(limit)
    }

    /** Небольшой бонус OWE: при похожем сигнале выбираем шифрованную сеть. */
    private fun score(candidate: Candidate): Int =
        candidate.level + if (candidate.enhancedOpen) 6 else 0

    /** Формат для локального SharedPreferences StringSet. */
    fun encode(candidate: Candidate): String =
        (if (candidate.enhancedOpen) "E:" else "O:") + candidate.ssid

    fun decode(value: String): Candidate? {
        if (value.length < 3 || value[1] != ':') return null
        val enhanced = when (value[0]) {
            'E' -> true
            'O' -> false
            else -> return null
        }
        val ssid = value.substring(2)
        if (ssid.isBlank()) return null
        return Candidate(ssid, Int.MIN_VALUE, enhanced)
    }

    /** Экранирование SSID для старого WifiConfiguration (Android 8–9). */
    fun quotedSsid(ssid: String): String =
        "\"" + ssid.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Голосовые фразы именно про бесплатные/открытые сети. Обычное
     * «включи Wi‑Fi» намеренно не перехватываем — оно открывает панель Android.
     */
    fun command(raw: String): Command? {
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е')
        // Берём основы слов, чтобы поймать «вайфаю», «сетям», «точкам».
        val mentionsWifi = Regex("вай ?фа|wi[ -]?fi|wifi").containsMatchIn(s)
        val mentionsOpen = Regex("бесплатн|открыт|без парол|безопасност[ьи] нет").containsMatchIn(s)
        val mentionsAuto = Regex("авто ?подключ|автоматическ.*подключ").containsMatchIn(s)
        val mentionsNetworks = Regex("сет|точк").containsMatchIn(s)
        if (!(mentionsWifi && (mentionsOpen || mentionsAuto) || mentionsAuto && mentionsNetworks)) {
            return null
        }

        if (Regex("выключ|отключ|не подключай|перестань").containsMatchIn(s)) return Command.DISABLE
        if (Regex("статус|работает|включен|включено|что с|какое состояние").containsMatchIn(s)) {
            return Command.STATUS
        }
        if (Regex("найди|ищи|поиск|проверь|подключись|подключи").containsMatchIn(s)) return Command.SCAN
        if (Regex("включ|разреш|подключайся|запусти").containsMatchIn(s)) return Command.ENABLE
        return Command.STATUS
    }
}
