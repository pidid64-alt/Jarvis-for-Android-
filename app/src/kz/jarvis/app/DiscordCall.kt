package kz.jarvis.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Discord: интенты и «сам нажму кнопку звонка».
 *
 * Публичного способа начать звонок в Discord из чужого приложения нет, поэтому
 * Джарвис делает то, что может честно:
 *  1. открывает нужный чат, канал или приложение (по сохранённой ссылке);
 *  2. если включён сервис спец. возможностей ([AutoSendService]) и тумблер
 *     «сам нажимать кнопку звонка» — находит в открывшемся окне кнопку
 *     «Начать голосовой вызов» и жмёт её за владельца.
 *
 * Ссылки хранит сам владелец: «запомни канал дискорд общий https://discord.gg/…».
 * Разбор фраз — в [Discord] (чистая логика, покрыта JVM-тестами).
 */
object DiscordCall {

    /** Что сделать: ответ голосом + интент + что нажать в окне Discord. */
    data class Plan(
        val reply: String,
        val intent: Intent? = null,
        /** «voice», «video» или null — кнопку звонка искать не надо. */
        val tap: String? = null,
        /** Текст, который кладём в буфер («напиши … в дискорде»). */
        val clipboard: String? = null,
        /** Нужно ли напомнить про сервис спец. возможностей. */
        val needsHands: Boolean = false
    )

    // ------------------------------------------- ожидание «нажать кнопку звонка»

    @Volatile
    private var pkg: String? = null

    @Volatile
    private var kind: String? = null

    @Volatile
    private var deadline = 0L

    @Volatile
    private var clicked = false

    fun arm(targetPkg: String, tapKind: String, timeoutMs: Long = 30_000) {
        pkg = targetPkg
        kind = tapKind
        deadline = System.currentTimeMillis() + timeoutMs
        clicked = false
    }

    fun active(): Boolean = pkg != null && System.currentTimeMillis() < deadline

    fun targetPkg(): String? = pkg

    fun wantVideo(): Boolean = kind == "video"

    fun markClicked() {
        clicked = true
        pkg = null
        kind = null
    }

    fun clear() {
        pkg = null
        kind = null
        deadline = 0L
    }

    /** Ждёт нажатия кнопки звонка; true — Discord принял звонок. */
    fun waitResult(timeoutMs: Long = 20_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (clicked) return true
            if (!active()) return clicked
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                return clicked
            }
        }
        clear()
        return clicked
    }

    // ---------------------------------------------------------------- интенты

    /** Установленный клиент Discord; null — нет. */
    fun installed(ctx: Context): String? =
        Discord.PACKAGES.firstOrNull { p ->
            try {
                ctx.packageManager.getLaunchIntentForPackage(p) != null
            } catch (e: Exception) {
                false
            }
        }

    /** Запустить Discord (приложение или веб-версию). */
    fun appIntent(ctx: Context): Intent? {
        val p = installed(ctx)
        if (p != null) {
            try {
                ctx.packageManager.getLaunchIntentForPackage(p)?.let {
                    return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Exception) { }
        }
        return viewIntent(ctx, "https://discord.com/app")
    }

    private fun viewIntent(ctx: Context, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Интент по сохранённой ссылке (приложению или браузеру). */
    private fun linkIntent(ctx: Context, link: String): Intent {
        val deep = Discord.deepLink(link)
        val i = viewIntent(ctx, deep)
        // discord:// умеет только сам клиент — направим интент в него
        if (deep.startsWith("discord://")) installed(ctx)?.let { i.setPackage(it) }
        return i
    }

    /**
     * План действий по разобранной команде. Вызывается из [CommandEngine];
     * сам ничего не запускает.
     */
    fun planFor(
        ctx: Context,
        cmd: Discord.Cmd,
        aliases: List<Discord.Alias>,
        handsEnabled: Boolean,
        autoTap: Boolean
    ): Plan {
        val wantTap = autoTap && handsEnabled
        when (cmd.kind) {
            Discord.Kind.APP -> {
                val i = appIntent(ctx)
                return if (i != null) Plan("Открываю Discord, сэр.", i)
                else Plan("Discord не установлен и открыть его нечем, сэр.")
            }

            Discord.Kind.FRIENDS -> {
                val i = installed(ctx)?.let { viewIntent(ctx, "discord://-/users/@me").setPackage(it) }
                    ?: appIntent(ctx)
                return Plan("Открываю список друзей в Discord.", i)
            }

            Discord.Kind.ALIAS_LIST -> return Plan(Discord.listText(aliases))

            Discord.Kind.LINK, Discord.Kind.CHANNEL, Discord.Kind.CALL, Discord.Kind.VIDEO_CALL -> {
                val (link, name) = resolveLink(cmd, aliases)
                if (link == null) {
                    val who = cmd.who
                    return Plan(
                        if (who == null) {
                            "Не знаю, куда заходить в Discord, сэр. Сохраните ссылку: " +
                                "«запомни канал дискорд общий https://discord.gg/ваша-ссылка»."
                        } else {
                            "Ссылку на «$who» в Discord я ещё не знаю, сэр. Скажите: " +
                                "«запомни канал дискорд $who https://discord.gg/…» — и в следующий раз зайду сам."
                        },
                        appIntent(ctx)
                    )
                }
                val video = cmd.kind == Discord.Kind.VIDEO_CALL
                val isCall = cmd.kind == Discord.Kind.CALL || cmd.kind == Discord.Kind.VIDEO_CALL ||
                    Discord.isInvite(link)
                val i = linkIntent(ctx, link)
                val label = name ?: cmd.who ?: "Discord"
                return when {
                    !isCall -> Plan("Открываю «$label» в Discord.", i)
                    wantTap -> Plan(
                        "Открываю «$label» и нажимаю кнопку звонка, сэр.",
                        i,
                        tap = if (video) "video" else "voice",
                        needsHands = false
                    )
                    autoTap && !handsEnabled -> Plan(
                        "Открываю «$label» в Discord. Чтобы я сам нажал кнопку звонка, включите " +
                            "«Джарвис — автоотправка» в специальных возможностях (открываю настройки).",
                        i,
                        needsHands = true
                    )
                    else -> Plan("Открываю «$label» в Discord — кнопку звонка нажмите сами, сэр.", i)
                }
            }

            Discord.Kind.DM, Discord.Kind.MESSAGE -> {
                val (link, name) = resolveLink(cmd, aliases)
                val text = cmd.text
                if (link == null) {
                    return Plan(
                        "Чат «${cmd.who ?: name}» в Discord я не знаю: сохраните ссылку " +
                            "«запомни канал дискорд ${cmd.who ?: name} https://discord.com/channels/@me/…». " +
                            (if (text != null) "Текст положил в буфер обмена." else ""),
                        appIntent(ctx),
                        clipboard = text
                    )
                }
                return Plan(
                    if (text.isNullOrBlank()) "Открываю чат «${name ?: cmd.who}» в Discord."
                    else "Открываю чат «${name ?: cmd.who}» в Discord — текст в буфере, вставьте его.",
                    linkIntent(ctx, link),
                    clipboard = text
                )
            }

            // ссылки сохраняет CommandEngine — здесь только текст ответа
            Discord.Kind.ALIAS_ADD -> {
                val a = cmd.alias ?: return Plan("Не разобрал ссылку, сэр.")
                return Plan("Запомнил «${a.name}» в Discord, сэр.")
            }

            Discord.Kind.ALIAS_REMOVE -> Plan("Убрал ссылку «${cmd.who}», сэр.")
        }
        // до сюда не доходит: все ветки перечислены, но компилятору нужен явный возврат
        return Plan("Открываю Discord, сэр.", appIntent(ctx))
    }

    /**
     * Находит ссылку: из фразы, по сохранённому имени или по ID пользователя
     * (17–20 цифр — Discord ID, их видно в профиле при включённом режиме
     * разработчика).
     */
    fun resolveLink(cmd: Discord.Cmd, aliases: List<Discord.Alias>): Pair<String?, String?> {
        cmd.link?.let { return Discord.deepLink(it) to null }
        val who = cmd.who?.trim().orEmpty()
        if (who.isEmpty()) return null to null
        if (Regex("^\\d{15,25}$").matches(who)) return "discord://-/channels/@me/$who" to who
        Discord.find(who, aliases)?.let { return it.link to it.name }
        // имя могло попасть в фразу с предлогом: «позвони по ссылке общий»
        val byWord = who.split(' ').firstOrNull()?.let { w -> Discord.find(w, aliases) }
        return if (byWord != null) byWord.link to byWord.name else null to who
    }

    /** Кладёт текст в буфер обмена (для «напиши … в дискорде»). */
    fun copy(ctx: Context, text: String) {
        if (text.isBlank()) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Jarvis → Discord", text))
        } catch (e: Exception) { }
    }
}
