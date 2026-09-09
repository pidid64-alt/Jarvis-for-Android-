package kz.jarvis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * Геолокация для автоответчика: последнее известное место телефона, ссылка
 * на Google Карты и (если повезёт с сетью) человеческий адрес через
 * OpenStreetMap/Nominatim — без ключей и токенов.
 *
 * Всё вызывается только из фонового потока и с проверкой разрешений.
 */
object Geo {

    data class Fix(val lat: Double, val lng: Double) {
        /** Ссылка вида https://maps.google.com/?q=51.09,71.40 */
        fun mapsLink(): String = String.format(java.util.Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lng)
    }

    private fun hasFine(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarse(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Последнее известное местоположение (GPS/сеть), без запроса свежих данных. */
    fun lastKnown(c: Context): Fix? {
        if (!hasFine(c) && !hasCoarse(c)) return null
        return try {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                val ok = try {
                    if (p == LocationManager.GPS_PROVIDER) hasFine(c)
                    else hasFine(c) || hasCoarse(c)
                } catch (e: Exception) { false }
                if (!ok) continue
                val loc = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
                if (loc != null && (best == null || loc.time > best.time)) best = loc
            }
            best?.let { Fix(it.latitude, it.longitude) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Человеческий адрес по координатам (OpenStreetMap Nominatim, без ключа).
     * Может не ответить — тогда вернётся null, и модель ответит по ссылке.
     */
    fun address(c: Context, fix: Fix): String? = try {
        val url = String.format(
            java.util.Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&accept-language=ru&lat=%.5f&lon=%.5f",
            fix.lat, fix.lng
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("User-Agent", "JarvisAndroidAssistant/2.5 (voice assistant)")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val m = Regex("\"display_name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(body)
            m?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                ?.substringBeforeLast(',')   // оставляем без страны
                ?.trim()
                ?.take(140)
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Готовый кусок контекста для модели: адрес, координаты и ссылка на карту.
     * Без разрешения/координат — null (модель просто не знает, где владелец).
     */
    fun contextText(c: Context): String? {
        val fix = lastKnown(c) ?: return null
        val addr = address(c, fix)
        val sb = StringBuilder("Местоположение владельца: ")
        if (addr != null) sb.append(addr).append(" (координаты ")
        sb.append(String.format(java.util.Locale.US, "%.5f, %.5f", fix.lat, fix.lng))
        if (addr != null) sb.append(")")
        sb.append(". Ссылка на карту: ").append(fix.mapsLink())
        sb.append(". Если спрашивают «где ты» — отвечай этим местом, но без технических подробностей, ")
            .append("просто «я сейчас …» или «скоро буду», и не выдумывай, если места не знаешь.")
        return sb.toString()
    }
}
