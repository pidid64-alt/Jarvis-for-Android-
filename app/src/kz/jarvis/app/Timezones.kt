package kz.jarvis.app

import java.util.Locale

/** Небольшой офлайн-справочник часовых поясов для «который час в …». */
object Timezones {

    private val table = linkedMapOf(
        "лондон" to "Europe/London",
        "нью-йорк" to "America/New_York",
        "нью йорк" to "America/New_York",
        "вашингтон" to "America/New_York",
        "лос-анджелес" to "America/Los_Angeles",
        "лос анджелес" to "America/Los_Angeles",
        "чикаго" to "America/Chicago",
        "торонто" to "America/Toronto",
        "сан паулу" to "America/Sao_Paulo",
        "сан-паулу" to "America/Sao_Paulo",
        "москва" to "Europe/Moscow",
        "санкт-петербург" to "Europe/Moscow",
        "питер" to "Europe/Moscow",
        "киев" to "Europe/Kiev",
        "минск" to "Europe/Minsk",
        "варшава" to "Europe/Warsaw",
        "берлин" to "Europe/Berlin",
        "париж" to "Europe/Paris",
        "рим" to "Europe/Rome",
        "мадрид" to "Europe/Madrid",
        "стамбул" to "Europe/Istanbul",
        "дубай" to "Asia/Dubai",
        "баку" to "Asia/Baku",
        "тбилиси" to "Asia/Tbilisi",
        "ереван" to "Asia/Yerevan",
        "астана" to "Asia/Almaty",
        "алматы" to "Asia/Almaty",
        "шымкент" to "Asia/Almaty",
        "ташкент" to "Asia/Tashkent",
        "бишкек" to "Asia/Bishkek",
        "новосибирск" to "Asia/Novosibirsk",
        "екатеринбург" to "Asia/Yekaterinburg",
        "дели" to "Asia/Kolkata",
        "мумбаи" to "Asia/Kolkata",
        "пекин" to "Asia/Shanghai",
        "шанхай" to "Asia/Shanghai",
        "токио" to "Asia/Tokyo",
        "сеул" to "Asia/Seoul",
        "бангкок" to "Asia/Bangkok",
        "сингапур" to "Asia/Singapore",
        "сидней" to "Australia/Sydney",
        "каир" to "Africa/Cairo",
        "тель-авив" to "Asia/Jerusalem",
        "хельсинки" to "Europe/Helsinki",
        "прага" to "Europe/Prague",
        "амстердам" to "Europe/Amsterdam"
    )

    /** Возвращает идентификатор часового пояса или null. */
    fun find(place: String): String? {
        val p = place.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        table[p]?.let { return it }
        for ((k, v) in table) {
            val key = k.replace('-', ' ')
            if (p.contains(key) || key.contains(p)) return v
        }
        return null
    }
}
