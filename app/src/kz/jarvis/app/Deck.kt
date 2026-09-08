package kz.jarvis.app

import java.util.Locale

/**
 * Презентация: разбор ответа модели в слайды и сборка готовых файлов
 * (HTML-презентация, которая листается прямо в браузере телефона, и
 * Markdown — его удобно вставить в Google Слайды, Canva или Notion).
 *
 * Файл не зависит от Android — покрыт scripts/test-logic.sh.
 */
object Deck {

    class Slide(
        val title: String,
        val bullets: List<String>,
        val note: String = ""
    )

    class Source(val title: String, val url: String)

    class Presentation(
        val topic: String,
        val title: String,
        val subtitle: String,
        val slides: List<Slide>,
        val summary: String,
        val sources: List<Source>
    ) {
        val ok: Boolean get() = slides.isNotEmpty()
    }

    // ------------------------------------------------------------------ разбор

    private val BULLET = Regex("^\\s*(?:[-–—•*]|\\d{1,2}[.)])\\s+(.*)$")
    private val SLIDE_HEAD = Regex(
        "^\\s*(?:слайд\\s*\\d*\\s*[:.\\-]|slide\\s*\\d*\\s*[:.\\-]|#{1,4}\\s+|\\*\\*слайд[^:]*:\\*\\*)\\s*(.*)$",
        RegexOption.IGNORE_CASE
    )

    /** Превращает ответ модели в презентацию. Терпим к вольностям формата. */
    fun parse(topic: String, raw: String, sources: List<Source> = emptyList()): Presentation {
        var title = ""
        var subtitle = ""
        var summary = ""
        val slides = ArrayList<Slide>()

        var curTitle: String? = null
        val curBullets = ArrayList<String>()
        var curNote = ""

        fun flush() {
            val t = curTitle?.trim().orEmpty()
            if (t.isNotEmpty() || curBullets.isNotEmpty()) {
                slides.add(Slide(t.ifEmpty { "Слайд ${slides.size + 1}" }, curBullets.toList(), curNote.trim()))
            }
            curTitle = null
            curBullets.clear()
            curNote = ""
        }

        for (rawLine in raw.replace("\r", "").split("\n")) {
            val line = rawLine.trim().trim('`')
            if (line.isEmpty()) continue
            val low = line.lowercase(Locale.ROOT)

            when {
                low.startsWith("заголовок:") || low.startsWith("title:") -> {
                    title = line.substringAfter(":").trim().trim('*', '"', '«', '»')
                    continue
                }
                low.startsWith("подзаголовок:") || low.startsWith("subtitle:") -> {
                    subtitle = line.substringAfter(":").trim().trim('*', '"')
                    continue
                }
                low.startsWith("итог:") || low.startsWith("вывод:") || low.startsWith("summary:") -> {
                    summary = line.substringAfter(":").trim().trim('*', '"')
                    continue
                }
                low.startsWith("заметка:") || low.startsWith("заметки:") ||
                    low.startsWith("notes:") || low.startsWith("note:") -> {
                    curNote = (curNote + " " + line.substringAfter(":").trim()).trim()
                    continue
                }
            }

            val head = SLIDE_HEAD.find(line)
            if (head != null) {
                val name = head.groupValues[1].trim().trim('*', '"', '«', '»', ':', '-', ' ')
                // «# Название презентации» до первого слайда — это титул
                if (slides.isEmpty() && curTitle == null && curBullets.isEmpty() && title.isEmpty()) {
                    title = name
                    continue
                }
                flush()
                curTitle = name
                continue
            }

            val bullet = BULLET.find(line)
            if (bullet != null) {
                val text = clean(bullet.groupValues[1])
                if (text.isNotEmpty()) curBullets.add(text)
                continue
            }

            // обычная строка: заголовок слайда, если его ещё нет, иначе — пункт
            val text = clean(line)
            if (text.isEmpty()) continue
            if (curTitle == null && curBullets.isEmpty()) curTitle = text else curBullets.add(text)
        }
        flush()

        val finalTitle = title.ifEmpty { topic.replaceFirstChar { it.uppercase() } }
        val finalSubtitle = subtitle.ifEmpty { "Материал собран Джарвисом по запросу «$topic»" }
        return Presentation(topic, finalTitle, finalSubtitle, slides, summary, sources)
    }

    private fun clean(s: String): String =
        s.replace(Regex("\\*\\*|__|`"), "").trim().trim('*', ' ', ';')

    // -------------------------------------------------------------- озвучка

    /** Короткий отчёт голосом: сколько слайдов и о чём они. */
    fun speech(p: Presentation): String {
        if (!p.ok) return "Слайды собрать не удалось, сэр."
        val heads = p.slides.take(4).joinToString("; ") { it.title }
        val tail = if (p.slides.size > 4) " и ещё ${p.slides.size - 4}" else ""
        val sum = if (p.summary.isNotEmpty()) " Главный вывод: ${p.summary}" else ""
        return "Готово, сэр. Презентация «${p.title}» — ${p.slides.size} слайдов: $heads$tail.$sum"
    }

    // ------------------------------------------------------------- markdown

    fun markdown(p: Presentation): String = buildString {
        append("# ").append(p.title).append("\n\n")
        append("_").append(p.subtitle).append("_\n\n")
        for ((i, s) in p.slides.withIndex()) {
            append("---\n\n## ").append(i + 1).append(". ").append(s.title).append("\n\n")
            for (b in s.bullets) append("- ").append(b).append("\n")
            if (s.note.isNotEmpty()) append("\n> Заметка докладчику: ").append(s.note).append("\n")
            append("\n")
        }
        if (p.summary.isNotEmpty()) append("---\n\n**Вывод:** ").append(p.summary).append("\n\n")
        if (p.sources.isNotEmpty()) {
            append("---\n\n## Источники\n\n")
            for (s in p.sources) append("- [").append(s.title).append("](").append(s.url).append(")\n")
        }
        append("\n<!-- собрано Джарвисом -->\n")
    }

    // ------------------------------------------------------------------ HTML

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Самодостаточная HTML-презентация: без интернета, без библиотек.
     * Листается стрелками, кнопками, пробелом и свайпом; печать в PDF — Ctrl+P
     * или «Поделиться → Печать» в браузере телефона.
     */
    fun html(p: Presentation): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(p.title)).append("</title><style>")
        sb.append(CSS)
        sb.append("</style></head><body>")
        sb.append("<div id=\"bar\"><i id=\"fill\"></i></div>")

        // титульный слайд
        sb.append("<section class=\"slide title\"><div class=\"inner\">")
        sb.append("<div class=\"kicker\">J.A.R.V.I.S. // отчёт по запросу</div>")
        sb.append("<h1>").append(esc(p.title)).append("</h1>")
        sb.append("<p class=\"sub\">").append(esc(p.subtitle)).append("</p>")
        sb.append("</div></section>")

        for ((i, s) in p.slides.withIndex()) {
            sb.append("<section class=\"slide\"><div class=\"inner\">")
            sb.append("<div class=\"num\">").append(i + 1).append(" / ").append(p.slides.size).append("</div>")
            sb.append("<h2>").append(esc(s.title)).append("</h2><ul>")
            for (b in s.bullets) sb.append("<li>").append(esc(b)).append("</li>")
            sb.append("</ul>")
            if (s.note.isNotEmpty()) {
                sb.append("<div class=\"note\"><b>Докладчику:</b> ").append(esc(s.note)).append("</div>")
            }
            sb.append("</div></section>")
        }

        if (p.summary.isNotEmpty() || p.sources.isNotEmpty()) {
            sb.append("<section class=\"slide\"><div class=\"inner\">")
            sb.append("<h2>Вывод и источники</h2>")
            if (p.summary.isNotEmpty()) sb.append("<p class=\"sum\">").append(esc(p.summary)).append("</p>")
            if (p.sources.isNotEmpty()) {
                sb.append("<ul class=\"src\">")
                for (s in p.sources.take(12)) {
                    sb.append("<li><a href=\"").append(esc(s.url)).append("\">")
                        .append(esc(s.title.ifEmpty { s.url })).append("</a></li>")
                }
                sb.append("</ul>")
            }
            sb.append("</div></section>")
        }

        sb.append("<div id=\"nav\"><button id=\"prev\">◀</button>")
            .append("<span id=\"pos\"></span><button id=\"next\">▶</button></div>")
        sb.append("<script>").append(JS).append("</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private const val CSS = """
:root{--bg:#04080f;--cyan:#28e0ff;--dim:#8aa3b8;--card:#0b1622}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:#e8f4ff;font-family:-apple-system,Roboto,'Segoe UI',sans-serif;overflow:hidden}
.slide{position:absolute;inset:0;display:none;padding:34px 26px 90px;overflow-y:auto;
  background:radial-gradient(circle at 20% 0%,#0d2a3d 0%,var(--bg) 60%)}
.slide.on{display:block;animation:in .35s ease}
@keyframes in{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}
.inner{max-width:900px;margin:0 auto}
.kicker{color:var(--cyan);letter-spacing:.22em;font-size:12px;text-transform:uppercase;margin-bottom:18px}
h1{font-size:34px;line-height:1.15;margin:0 0 14px}
h2{font-size:26px;line-height:1.2;margin:6px 0 18px;color:var(--cyan)}
.sub{color:var(--dim);font-size:16px}
.num{color:var(--dim);font-size:12px;letter-spacing:.2em}
ul{margin:0;padding-left:22px}
li{margin:0 0 12px;font-size:18px;line-height:1.45}
li::marker{color:var(--cyan)}
.note{margin-top:22px;padding:14px 16px;border-left:3px solid var(--cyan);background:var(--card);
  border-radius:0 10px 10px 0;color:var(--dim);font-size:14px;line-height:1.5}
.sum{font-size:19px;line-height:1.5}
.src a{color:var(--cyan);font-size:14px;word-break:break-all}
#nav{position:fixed;left:0;right:0;bottom:0;display:flex;gap:12px;align-items:center;
  justify-content:center;padding:12px;background:rgba(4,8,15,.85);backdrop-filter:blur(6px)}
#nav button{background:var(--card);color:var(--cyan);border:1px solid #1d3b52;border-radius:12px;
  font-size:20px;padding:8px 22px}
#pos{color:var(--dim);font-size:13px;min-width:64px;text-align:center}
#bar{position:fixed;top:0;left:0;right:0;height:3px;background:#0d2030;z-index:5}
#fill{display:block;height:100%;width:0;background:var(--cyan);transition:width .3s}
@media print{
  body{overflow:visible;background:#fff;color:#000}
  .slide{position:static;display:block!important;page-break-after:always;background:#fff;min-height:100vh}
  h2,.kicker,li::marker{color:#0a6ea8}
  #nav,#bar{display:none}
}
"""

    private const val JS = """
var s=document.querySelectorAll('.slide'),i=0;
function go(n){i=Math.max(0,Math.min(s.length-1,n));
 for(var k=0;k<s.length;k++)s[k].classList.toggle('on',k===i);
 document.getElementById('pos').textContent=(i+1)+' / '+s.length;
 document.getElementById('fill').style.width=((i+1)/s.length*100)+'%';}
document.getElementById('prev').onclick=function(){go(i-1)};
document.getElementById('next').onclick=function(){go(i+1)};
document.onkeydown=function(e){
 if(e.key==='ArrowRight'||e.key===' '||e.key==='PageDown')go(i+1);
 if(e.key==='ArrowLeft'||e.key==='PageUp')go(i-1);};
var x0=null;
document.addEventListener('touchstart',function(e){x0=e.touches[0].clientX},{passive:true});
document.addEventListener('touchend',function(e){
 if(x0===null)return;var dx=e.changedTouches[0].clientX-x0;
 if(Math.abs(dx)>60)go(dx<0?i+1:i-1);x0=null;},{passive:true});
go(0);
"""

    // -------------------------------------------------------------- имя файла

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
        'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y",
        'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    /** «Квантовые компьютеры» → «kvantovye-kompyutery». */
    fun slug(topic: String, max: Int = 40): String {
        val sb = StringBuilder()
        for (ch in topic.lowercase(Locale.ROOT).replace('ё', 'е')) {
            when {
                TRANSLIT.containsKey(ch) -> sb.append(TRANSLIT[ch])
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch)
                else -> if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            }
        }
        val s = sb.toString().trim('-').take(max).trim('-')
        return s.ifEmpty { "prezentaciya" }
    }
}
