package kz.jarvis.app

import java.util.Locale

/**
 * Discord: чистый разбор голосовых команд и работа со ссылками.
 *
 * Android-часть (интенты, «сам нажму кнопку звонка») — в [DiscordCall];
 * здесь только логика, поэтому она покрыта scripts/test-logic.sh на JVM.
 *
 * Discord не даёт сторонним приложениям «позвонить» одной командой, поэтому
 * Джарвис действует честно:
 *  • открывает нужный чат/канал по сохранённой ссылке;
 *  • если включена автоотправка (сервис спец. возможностей) — сам нажимает
 *    кнопку звонка в открывшемся окне.
 * Ссылки на каналы и собеседников владелец задаёт сам — ничего не зашито:
 * «запомни канал дискорд общий https://discord.gg/…».
 */
object Discord {

    /** Пакеты клиента Discord (обычный и тестовый Canary). */
    val PACKAGES = listOf("com.discord", "com.discord.canary")

    enum class Kind {
        APP, FRIENDS, CALL, VIDEO_CALL, DM, MESSAGE, CHANNEL, LINK,
        ALIAS_ADD, ALIAS_LIST, ALIAS_REMOVE
    }

    /** Сохранённая ссылка: «общий» → https://discord.gg/xxx */
    data class Alias(val name: String, val link: String)

    /**
     * @param who   кому звоним / с кем чат (как сказано: «маме», «общий»)
     * @param link  ссылка, если она была в фразе
     * @param text  текст сообщения («напиши в дискорде: …»)
     * @param alias новое имя+ссылка для [Kind.ALIAS_ADD]
     */
    data class Cmd(
        val kind: Kind,
        val who: String? = null,
        val link: String? = null,
        val text: String? = null,
        val alias: Alias? = null
    )

    // ------------------------------------------------------------------ разбор

    private val MENTION = Regex(
        "дискорд[а-яa-z0-9]*|discord|дискорда|дискорде|дискорду",
        RegexOption.IGNORE_CASE
    )

    /** Фраза вообще про Discord. */
    fun isAbout(s: String): Boolean = MENTION.containsMatchIn(s.lowercase(Locale.ROOT))

    /** Любая ссылка на Discord в фразе. */
    private val LINK = Regex(
        "(?:https?://)?(?:www\\.)?(?:discord(?:app)?\\.(?:com|gg)|discord\\.gg)/[^\\s,;]+|" +
            "discord://-/[^\\s,;]+",
        RegexOption.IGNORE_CASE
    )

    /** Ищем ссылку в произвольной фразе. */
    fun findLink(s: String): String? = LINK.find(s)?.value?.trimEnd('.', ',', '!', ')')

    private val CALL = Regex(
        "позвон|звон|набер|вызов|созвон|войс|голосов|подключ|зайди|заходи|войди|присоедин|join"
    )
    private val VIDEO = Regex("видео(?:звон|\\s+звон|-звон)|по видео|видеосвяз|видеочат")
    private val WRITE = Regex("напиши|отправь|сообщи|передай|кинь|черкни")
    private val CHANNEL = Regex("канал|чат|сервер|войс|группу|комнату")
    private val OPEN = Regex("открой|запусти|включи|покажи|зайди в|перейди")

    /**
     * Разбирает фразу про Discord; null — фраза не про него.
     *
     * Примеры:
     *  • «открой дискорд»                      → APP
     *  • «позвони маме в дискорде»             → CALL, who=маме
     *  • «видеозвонок с папой в дискорде»      → VIDEO_CALL, who=папой
     *  • «зайди в голосовой канал общий»       → CHANNEL, who=общий
     *  • «напиши сестре в дискорде: еду»       → MESSAGE, who=сестре, text=еду
     *  • «открой https://discord.gg/abc»       → LINK
     *  • «запомни канал дискорд общий <ссылка>»→ ALIAS_ADD
     */
    fun parse(raw: String): Cmd? {
        val s0 = raw.trim()
        if (!isAbout(s0)) return null
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')

        aliasAdd(s0)?.let { return it }
        if (Regex("какие каналы|какие ссылки|список каналов|покажи каналы|что за каналы|" +
                "какие каналы дискорда|мои каналы|покажи ссылки").containsMatchIn(s)
        ) {
            return Cmd(Kind.ALIAS_LIST)
        }
        aliasRemove(s0)?.let { return it }

        val link = findLink(s0)
        val who = who(s0)

        // сообщение с текстом: «напиши маме в дискорде: я еду»
        if (WRITE.containsMatchIn(s)) {
            val msg = messageText(s0)
            if (msg != null && (msg.second.isNotBlank() || link != null)) {
                return Cmd(Kind.MESSAGE, who = msg.first ?: who, link = link, text = msg.second)
            }
        }
        if (VIDEO.containsMatchIn(s)) return Cmd(Kind.VIDEO_CALL, who = who, link = link)
        if (CALL.containsMatchIn(s)) {
            // «зайди в голосовой канал общий» — канал; «позвони маме» — личный звонок
            val personal = Regex("позвон|набери|вызови|созвон").containsMatchIn(s) &&
                !CHANNEL.containsMatchIn(s)
            val kind = when {
                link != null && who == null -> Kind.LINK
                personal -> Kind.CALL
                who != null && CHANNEL.containsMatchIn(s) -> Kind.CHANNEL
                who == null && CHANNEL.containsMatchIn(s) -> Kind.CHANNEL
                else -> Kind.CALL
            }
            return Cmd(kind, who = who, link = link)
        }
        if (CHANNEL.containsMatchIn(s)) return Cmd(Kind.CHANNEL, who = who, link = link)
        if (link != null) return Cmd(Kind.LINK, link = link)
        if (Regex("друз|friend").containsMatchIn(s)) return Cmd(Kind.FRIENDS)
        if (OPEN.containsMatchIn(s)) return Cmd(Kind.APP)
        return Cmd(Kind.APP)
    }

    /** «запомни канал дискорд общий https://…», «добавь ссылку общий: https://…» */
    fun aliasAdd(s0: String): Cmd? {
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        if (!Regex("запомни|добавь|сохрани|запиши|назови|пусть будет").containsMatchIn(s)) return null
        if (!Regex("канал|ссылк|чат|сервер|войс|контакт").containsMatchIn(s)) return null
        val link = findLink(s0) ?: return null
        val rest = LINK.replace(s0, " ")
        val name = Regex(
            "(?:запомни|добавь|сохрани|запиши|назови|пусть будет)\\s+" +
                "(?:канал|ссылку|ссылка|чат|сервер|войс|контакт)?\\s*" +
                "(?:в|для|у)?\\s*(?:дискорд[а-яa-z0-9]*|discord)?\\s*" +
                "(?:под названием|названием|с именем|именем|как)?\\s*[:—\\-]?\\s*" +
                "([\\p{L}\\p{N}][\\p{L}\\p{N} _\\-]{1,28})"
        ).find(rest)?.groupValues?.get(1)?.trim()
        val clean = name?.trim(' ', '.', ',', ':', '«', '»')?.takeIf { it.length >= 2 }
            ?: return null
        if (Regex("^(?:в|на|для|у|дискорд|discord|дискорде|канал|ссылку)$").matches(clean.lowercase(Locale.ROOT))) {
            return null
        }
        return Cmd(Kind.ALIAS_ADD, alias = Alias(clean, deepLink(link)))
    }

    /** «удали канал дискорд общий». */
    fun aliasRemove(s0: String): Cmd? {
        val s = s0.lowercase(Locale.ROOT).replace('ё', 'е')
        if (!Regex("удали|убери|сотри|забудь|снеси").containsMatchIn(s)) return null
        if (!Regex("канал|ссылк|чат|сервер|войс").containsMatchIn(s)) return null
        val name = Regex(
            "(?:удали|убери|сотри|забудь|снеси)\\s+(?:канал|ссылку|ссылка|чат|сервер|войс)?\\s*" +
                "(?:в|из)?\\s*(?:дискорд[а-яa-z0-9]*|discord)?\\s*[:—\\-]?\\s*" +
                "([\\p{L}\\p{N}][\\p{L}\\p{N} _\\-]{1,28})\\s*$"
        ).find(s)?.groupValues?.get(1)?.trim()
        return name?.takeIf { it.length >= 2 }?.let { Cmd(Kind.ALIAS_REMOVE, who = it) }
    }

    /** Слова, которые не являются именем: глаголы, предлоги и «канал». */
    private val NOT_WHO = setOf(
        "позвони", "позвонить", "позвоните", "набери", "набрать", "вызови", "вызвать",
        "созвон", "созвонись", "созвониться", "звонок", "звонка", "звонку", "звони",
        "видеозвонок", "видео", "видеосвязь", "видеочат", "открой", "открыть", "запусти",
        "включи", "зайди", "заходи", "войди", "перейди", "подключись", "присоединись",
        "напиши", "отправь", "сообщи", "передай", "кинь", "черкни", "сбрось",
        "голосовой", "голосовую", "голосового", "голосовом", "войс", "войса",
        "канал", "каналу", "канала", "канале", "чат", "чату", "чата", "чате",
        "сервер", "серверу", "сервера", "сервере", "группу", "группе", "комнату",
        "в", "во", "на", "по", "с", "со", "к", "из", "до", "у", "мне", "меня", "мой",
        "моя", "моё", "приложение", "друзьям", "друзей", "ссылку", "ссылка", "ссылке",
        "это", "тот", "та", "то", "же", "бы", "там", "сюда", "сейчас", "пожалуйста",
        "снова", "ещё", "еще", "просто", "ему", "ей", "им", "нему", "ней"
    )

    /**
     * С кем созвон/чат: «позвони маме в дискорде» → «маме».
     * Убираются упоминания Discord, ссылки, глаголы и слово «канал» —
     * остаётся имя собеседника или название канала.
     */
    fun who(s0: String): String? {
        val noLink = LINK.replace(s0, " ")
        val noMention = MENTION.replace(noLink, " ")
        val words = noMention.split(Regex("[\\s,;:.!?«»\"()]+")).filter { it.isNotEmpty() }
        val kept = words.filterNot {
            NOT_WHO.contains(it.lowercase(Locale.ROOT).replace('ё', 'е'))
        }
        if (kept.isEmpty()) return null
        val name = kept.joinToString(" ").trim()
        return if (name.length < 2) null else name.take(40)
    }

    /** Текст сообщения: «напиши маме в дискорде: я еду» → («маме», «я еду»). */
    fun messageText(s0: String): Pair<String?, String>? {
        var rest = LINK.replace(s0, " ")
        rest = MENTION.replace(rest, " ")
        rest = Regex("\\s*[,;]\\s*").replace(rest, " ")
        val verb = Regex("(?:напиши|отправь|сообщи|передай|кинь|черкни)\\s+", RegexOption.IGNORE_CASE)
            .find(rest) ?: return null
        rest = rest.substring(verb.range.last + 1).trim()
        val intro = Regex("\\s(?:что|текст|следующее|такое)\\s|\\s*[:—-]\\s+", RegexOption.IGNORE_CASE)
            .find(rest)
        if (intro == null) {
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            val name = rest.substring(0, sp).trim()
            val body = rest.substring(sp + 1).trim()
            if (name.isEmpty() || body.isEmpty()) return null
            return Pair(name, body)
        }
        val name = trimPreps(rest.substring(0, intro.range.first))
        val body = rest.substring(intro.range.last + 1).trim()
        if (body.isEmpty()) return null
        return Pair(name.takeIf { it.length >= 2 }, body)
    }

    /** Убирает предлоги, оставшиеся от «напиши в …»: «сестре в» → «сестре». */
    private fun trimPreps(raw: String): String =
        raw.split(' ')
            .filterNot { NOT_WHO.contains(it.lowercase(Locale.ROOT).replace('ё', 'е').trim(',', '.', ':')) }
            .joinToString(" ")
            .trim()

    // ------------------------------------------------------------------ ссылки

    /**
     * Приводит любую ссылку Discord к виду, который открывает приложение:
     *  • https://discord.com/channels/1/2      → discord://-/channels/1/2
     *  • https://discordapp.com/channels/@me/3 → discord://-/channels/@me/3
     *  • https://discord.gg/abc                → https://discord.gg/abc (инвайт)
     *  • 123456789012345678                    → discord://-/channels/123456789012345678
     */
    fun deepLink(raw: String): String {
        val s = raw.trim()
        if (s.startsWith("discord://")) return s
        val m = Regex("discord(?:app)?\\.com/channels/(@me/)?([\\d/@A-Za-z._-]+)").find(s)
        if (m != null) {
            val me = m.groupValues[1]
            val path = m.groupValues[2].trimEnd('/')
            return "discord://-/channels/${me}$path"
        }
        Regex("discord(?:app)?\\.com/invite/([A-Za-z0-9-]+)").find(s)?.let {
            return "https://discord.gg/" + it.groupValues[1]
        }
        Regex("discord\\.gg/([A-Za-z0-9-]+)").find(s)?.let {
            return "https://discord.gg/" + it.groupValues[1]
        }
        Regex("discord(?:app)?\\.com/users/([\\d]+)").find(s)?.let {
            return "discord://-/users/" + it.groupValues[1]
        }
        // просто ID канала/сервера
        if (Regex("^\\d{15,25}$").matches(s)) return "discord://-/channels/$s"
        return s
    }

    /** Ссылка ведёт в личные сообщения (DM), а не в канал сервера. */
    fun isDm(link: String): Boolean = link.contains("/channels/@me") || link.contains("/users/")

    /** Ссылка-инвайт: по ней Discord сам предлагает войти. */
    fun isInvite(link: String): Boolean = link.contains("discord.gg/")

    // --------------------------------------------------------- хранилище ссылок

    /**
     * Разбирает сохранённый список «имя|ссылка» (по одной на строку).
     * Терпим к старому формату «имя ссылка» и к лишним пробелам.
     */
    fun parseAliases(raw: String): List<Alias> =
        raw.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val parts = t.split('|', limit = 2)
            val (name, link) = if (parts.size == 2) parts[0].trim() to parts[1].trim()
            else {
                val m = Regex("^([\\p{L}\\p{N}][\\p{L}\\p{N} _\\-]{0,28})\\s+(https?://\\S+|discord://\\S+)$")
                    .find(t) ?: return@mapNotNull null
                m.groupValues[1].trim() to m.groupValues[2].trim()
            }
            if (name.isEmpty() || link.isEmpty()) return@mapNotNull null
            Alias(name, deepLink(link))
        }.distinctBy { it.name.lowercase(Locale.ROOT) }

    /** Список → строка для настроек. */
    fun serialize(list: List<Alias>): String =
        list.joinToString("\n") { "${it.name}|${it.link}" }

    /** Добавляет/обновляет ссылку в списке. */
    fun upsert(list: List<Alias>, alias: Alias): List<Alias> {
        val key = alias.name.lowercase(Locale.ROOT).replace('ё', 'е')
        val out = list.filterNot { it.name.lowercase(Locale.ROOT).replace('ё', 'е') == key }.toMutableList()
        out.add(Alias(alias.name.trim(), deepLink(alias.link)))
        return out
    }

    fun remove(list: List<Alias>, name: String): List<Alias> {
        val key = name.lowercase(Locale.ROOT).replace('ё', 'е').trim()
        return list.filterNot { it.name.lowercase(Locale.ROOT).replace('ё', 'е').trim() == key }
    }

    /**
     * Ищет сохранённую ссылку по имени: точно, по началу слова и с допуском
     * на ошибку распознавания («общий» ≈ «общем»).
     */
    fun find(name: String, list: List<Alias>): Alias? {
        if (list.isEmpty()) return null
        val key = CodeWords.normalize(name)
        if (key.isEmpty()) return null
        list.firstOrNull { CodeWords.normalize(it.name) == key }?.let { return it }
        list.firstOrNull { CodeWords.normalize(it.name).startsWith(key) || key.startsWith(CodeWords.normalize(it.name)) }
            ?.let { return it }
        return list.firstOrNull { CodeWords.levenshtein(CodeWords.normalize(it.name), key) <= 1 }
    }

    /** Человекочитаемый список сохранённых ссылок. */
    fun listText(list: List<Alias>): String = when {
        list.isEmpty() -> "Ссылок Discord пока нет, сэр. Скажите: «запомни канал дискорд общий " +
            "https://discord.gg/ваша-ссылка» — и я буду заходить туда голосом."
        else -> "Знаю в Discord: " + list.joinToString("; ") { "«${it.name}»" } + "."
    }

    fun help(): String = """
        Discord, сэр:
        • «открой дискорд» — запущу приложение
        • «позвони маме в дискорде» — открою чат и нажму кнопку звонка
          (нужен включённый сервис спец. возможностей)
        • «видеозвонок с папой в дискорде» — то же, но с видео
        • «зайди в голосовой канал общий» — по сохранённой ссылке
        • «запомни канал дискорд общий https://discord.gg/…» — сохранить ссылку
        • «какие каналы дискорда», «удали канал дискорд общий»
        • «напиши сестре в дискорде: я еду» — открою чат, текст положу в буфер
        Discord не разрешает сторонним приложениям начинать звонок самим,
        поэтому ссылку на собеседника или канал нужно сохранить один раз.
    """.trimIndent()
}
