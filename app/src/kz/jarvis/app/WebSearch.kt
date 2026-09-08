package kz.jarvis.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Поиск в интернете без ключей и без библиотек.
 *
 * Опрашиваются три бесплатных источника, каждый — в своём try/catch, так что
 * недоступность одного не ломает выдачу:
 *   1. Википедия (`/w/api.php`) — введение статей по теме;
 *   2. DuckDuckGo Instant Answer (`api.duckduckgo.com`) — короткая справка;
 *   3. DuckDuckGo HTML (`html.duckduckgo.com`) — обычные ссылки с описаниями.
 *
 * Разбор ответов сделан регулярками (без org.json), поэтому файл целиком
 * проверяется на JVM — см. scripts/test-logic.sh.
 */
object WebSearch {

    class Hit(
        val title: String,
        val snippet: String,
        val url: String,
        val source: String
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Jarvis) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    /** Сколько символов берём из одного описания. */
    private const val SNIPPET_MAX = 900

    // ------------------------------------------------------------------ поиск

    /**
     * Ищет по теме и возвращает найденное. Вызывать только из фонового потока.
     *
     * @param extraQueries дополнительные запросы (уточнения) — по одному на источник
     */
    fun search(topic: String, limit: Int = 9, extraQueries: List<String> = emptyList()): List<Hit> {
        val out = ArrayList<Hit>()
        val q = topic.trim()
        if (q.isEmpty()) return out

        addAll(out, wikipedia(q, "ru"))
        addAll(out, duckInstant(q))
        addAll(out, duckHtml(q))
        if (out.size < 4) addAll(out, wikipedia(q, "en"))
        for (extra in extraQueries) {
            if (out.size >= limit) break
            addAll(out, duckHtml(extra))
        }
        return out.take(limit)
    }

    private fun addAll(out: ArrayList<Hit>, hits: List<Hit>) {
        for (h in hits) {
            if (h.title.isBlank() && h.snippet.isBlank()) continue
            val dup = out.any {
                it.url == h.url || (it.title.equals(h.title, true) && h.title.isNotBlank())
            }
            if (!dup) out.add(h)
        }
    }

    // --------------------------------------------------------------- источники

    fun wikipedia(query: String, lang: String): List<Hit> = try {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=" + enc(query) + "&gsrlimit=3&prop=extracts&exintro=1&explaintext=1" +
            "&redirects=1&format=json&utf8=1"
        parseWikipedia(get(url), lang)
    } catch (e: Exception) {
        emptyList()
    }

    fun duckInstant(query: String): List<Hit> = try {
        val url = "https://api.duckduckgo.com/?q=" + enc(query) +
            "&format=json&no_html=1&skip_disambig=1&t=jarvis"
        parseDuckJson(get(url))
    } catch (e: Exception) {
        emptyList()
    }

    fun duckHtml(query: String): List<Hit> = try {
        parseDuckHtml(get("https://html.duckduckgo.com/html/?q=" + enc(query)))
    } catch (e: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------- сеть

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "ru,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) text else ""
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- разборы

    private val JSON_STR = "\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""

    private fun jsonField(chunk: String, key: String): String {
        val m = Regex(String.format(JSON_STR, key)).find(chunk) ?: return ""
        return jsonUnescape(m.groupValues[1])
    }

    fun jsonUnescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c); i++; continue
            }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append(' '); i += 2 }
                'r' -> { i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    /** Ответ Википедии: заголовки статей и вступления. */
    fun parseWikipedia(json: String, lang: String = "ru"): List<Hit> {
        if (json.isBlank()) return emptyList()
        val out = ArrayList<Hit>()
        val chunks = json.split("\"pageid\":")
        for (idx in 1 until chunks.size) {
            val chunk = chunks[idx]
            val title = jsonField(chunk, "title")
            val extract = jsonField(chunk, "extract")
            if (title.isBlank() && extract.isBlank()) continue
            val page = title.replace(' ', '_')
            out.add(
                Hit(
                    title = title,
                    snippet = trim(extract),
                    url = "https://$lang.wikipedia.org/wiki/" + enc(page).replace("+", "_"),
                    source = "Википедия"
                )
            )
        }
        return out
    }

    /** Ответ DuckDuckGo Instant Answer. */
    fun parseDuckJson(json: String): List<Hit> {
        if (json.isBlank()) return emptyList()
        val out = ArrayList<Hit>()
        val abstract = jsonField(json, "AbstractText")
        if (abstract.isNotBlank()) {
            out.add(
                Hit(
                    title = jsonField(json, "Heading").ifBlank { "Справка DuckDuckGo" },
                    snippet = trim(abstract),
                    url = jsonField(json, "AbstractURL"),
                    source = jsonField(json, "AbstractSource").ifBlank { "DuckDuckGo" }
                )
            )
        }
        val related = json.substringAfter("\"RelatedTopics\"", "")
        for (m in Regex("\\{[^{}]*\"FirstURL\"[^{}]*\\}").findAll(related).take(4)) {
            val chunk = m.value
            val text = jsonField(chunk, "Text")
            val url = jsonField(chunk, "FirstURL")
            if (text.isBlank()) continue
            out.add(
                Hit(
                    title = text.take(80),
                    snippet = trim(text),
                    url = url,
                    source = "DuckDuckGo"
                )
            )
        }
        return out
    }

    /** Обычная выдача DuckDuckGo (HTML-версия без JavaScript). */
    fun parseDuckHtml(html: String): List<Hit> {
        if (html.isBlank()) return emptyList()
        val titles = Regex(
            "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(html).toList()
        val snippets = Regex(
            "class=\"result__snippet\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        val out = ArrayList<Hit>()
        for ((i, m) in titles.withIndex()) {
            val href = m.groupValues[1]
            val title = stripTags(m.groupValues[2])
            if (title.isBlank()) continue
            val url = realUrl(href)
            out.add(
                Hit(
                    title = title,
                    snippet = trim(snippets.getOrElse(i) { "" }),
                    url = url,
                    source = host(url)
                )
            )
            if (out.size >= 6) break
        }
        return out
    }

    /** DuckDuckGo прячет ссылку в редирект `/l/?uddg=…` — достаём настоящую. */
    fun realUrl(href: String): String {
        val h = unescapeHtml(href)
        val m = Regex("[?&]uddg=([^&]+)").find(h) ?: return normalize(h)
        return try {
            normalize(URLDecoder.decode(m.groupValues[1], "UTF-8"))
        } catch (e: Exception) {
            normalize(h)
        }
    }

    private fun normalize(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http") -> u
        u.isBlank() -> ""
        else -> "https://$u"
    }

    fun host(url: String): String = try {
        URL(url).host.removePrefix("www.")
    } catch (e: Exception) {
        "интернет"
    }

    fun stripTags(s: String): String =
        unescapeHtml(s.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun unescapeHtml(s: String): String {
        var t = s
        val map = mapOf(
            "&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'",
            "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ", "&laquo;" to "«",
            "&raquo;" to "»", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…"
        )
        for ((k, v) in map) t = t.replace(k, v)
        t = Regex("&#(\\d{2,5});").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null) code.toChar().toString() else m.value
        }
        return t
    }

    private fun trim(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= SNIPPET_MAX) one else one.take(SNIPPET_MAX).substringBeforeLast(' ') + "…"
    }

    // ---------------------------------------------------------------- выжимка

    /** Текст для модели: тема, дата, пронумерованные находки с адресами. */
    fun digest(topic: String, hits: List<Hit>, today: String = "", maxChars: Int = 9_000): String {
        val sb = StringBuilder()
        sb.append("Тема запроса: ").append(topic).append('\n')
        if (today.isNotEmpty()) sb.append("Дата поиска: ").append(today).append('\n')
        sb.append("Найдено источников: ").append(hits.size).append("\n\n")
        for ((i, h) in hits.withIndex()) {
            val block = StringBuilder()
            block.append('[').append(i + 1).append("] ").append(h.title).append('\n')
            if (h.url.isNotEmpty()) block.append("Ссылка: ").append(h.url).append('\n')
            block.append("Источник: ").append(h.source).append('\n')
            if (h.snippet.isNotEmpty()) block.append(h.snippet).append('\n')
            block.append('\n')
            if (sb.length + block.length > maxChars) break
            sb.append(block)
        }
        return sb.toString().trim()
    }

    /** Короткий пересказ находок голосом, если модель недоступна. */
    fun offlineSummary(topic: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return "По теме «$topic» ничего не нашёл, сэр."
        val best = hits.first()
        val sources = hits.take(3).joinToString(", ") { it.source }
        val text = best.snippet.ifBlank { best.title }
        val short = if (text.length > 600) text.take(600).substringBeforeLast(' ') + "…" else text
        return "Вот что нашёл по теме «$topic», сэр. $short Источники: $sources."
    }

    /** Источники для последнего слайда презентации. */
    fun sources(hits: List<Hit>): List<Deck.Source> =
        hits.filter { it.url.isNotBlank() }
            .map { Deck.Source(it.title.take(90).ifBlank { host(it.url) }, it.url) }

    /**
     * Черновик презентации прямо из поисковой выдачи — на случай, когда модель
     * недоступна (нет ключа, нет ответа). Не так красиво, как у Клода, но файл
     * с материалом и ссылками у пользователя всё равно появляется.
     */
    fun draft(topic: String, hits: List<Hit>, slides: Int = ResearchPlan.DEFAULT_SLIDES): Deck.Presentation {
        val body = ArrayList<Deck.Slide>()
        val intro = hits.firstOrNull()
        if (intro != null) {
            body.add(
                Deck.Slide(
                    "О чём речь",
                    sentences(intro.snippet.ifBlank { intro.title }, 4),
                    "Вводный слайд собран из источника: ${intro.source}."
                )
            )
        }
        for (h in hits.drop(1)) {
            if (body.size >= slides - 1) break
            val bullets = sentences(h.snippet, 4)
            if (bullets.isEmpty()) continue
            body.add(Deck.Slide(h.title.take(80), bullets, "Источник: ${h.source} — ${h.url}"))
        }
        return Deck.Presentation(
            topic = topic,
            title = titleCase(topic),
            subtitle = "Черновик: материал из интернета, собранный Джарвисом",
            slides = body,
            summary = "Черновик собран без модели — попросите Клода причесать текст.",
            sources = sources(hits)
        )
    }

    /** Режет текст на пункты-предложения нужной длины. */
    fun sentences(text: String, max: Int): List<String> =
        text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim().trim('•', '-', ' ') }
            .filter { it.length in 25..220 }
            .take(max)

    /** Приводит тему к виду, пригодному для заголовка. */
    fun titleCase(topic: String): String =
        topic.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
}
