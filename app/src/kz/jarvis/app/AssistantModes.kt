package kz.jarvis.app

/**
 * Разбор голосовых команд двух новых режимов — чистая логика без Android
 * (покрыта scripts/test-logic.sh на JVM):
 *  • непрерывный диалог («после ответа можно говорить дальше без „Джарвис“»);
 *  • автоответчик в мессенджерах (вкл/выкл/статус и личные правила).
 */
object AssistantModes {

    /** true — «включи», false — «выключи», null — не команда или вопрос. */
    fun dialogRequest(s: String): Boolean? {
        if (!isAboutDialog(s)) return null
        // вопрос о состоянии — не приказ
        if (Regex("включен|выключен|статус|состояние|работает|как сейчас|активен").containsMatchIn(s)) {
            return null
        }
        val on = Regex("включ|вруб|актив|начни|продолжа(?:й|ть)").containsMatchIn(s)
        val off = Regex("выключ|отключ|выруб|убер|отмени").containsMatchIn(s)
        if (on == off) return null
        return on
    }

    /** Фраза вообще про непрерывный диалог. */
    fun isAboutDialog(s: String): Boolean {
        if (Regex("непрерывн|диалог подряд|говорить подряд|без (?:повтора|повторения|нового) ?«?джарвис|" +
                "не (?:переспрашивай|буди) ?«?джарвис").containsMatchIn(s)
        ) return true
        if (Regex("диалог|бесед|разговор").containsMatchIn(s) &&
            Regex("непрерывн|подряд|без джарвис|без «?джарвис»?|продолж|не прерыва").containsMatchIn(s)
        ) return true
        // «после ответа … без „Джарвис“» / «скажи „Джарвис“ один раз и дальше говори»
        return Regex("после ответа|один раз").containsMatchIn(s) && Regex("джарвис").containsMatchIn(s)
    }

    enum class AutoReplyCommand { ON, OFF, STATUS, DIAG, RULES_ADD, RULES_SHOW, RULES_CLEAR }

    /** Что хочет пользователь про автоответчик; null — фраза не про него. */
    fun autoReplyCommand(s: String): AutoReplyCommand? {
        if (Regex("правил").containsMatchIn(s)) {
            if (!isAboutAutoReply(s) && !isRuleCommand(s)) return null
            return when {
                Regex("(?:добавь|запомни|запиши|установи|поставь|новое|напиши правило|пусть)").containsMatchIn(s) ->
                    AutoReplyCommand.RULES_ADD
                Regex("(?:покажи|какие|что за|прочитай|расскажи)").containsMatchIn(s) ->
                    AutoReplyCommand.RULES_SHOW
                Regex("(?:очисти|удали|сбрось|убери|выбрось)").containsMatchIn(s) ->
                    AutoReplyCommand.RULES_CLEAR
                Regex("^правила").containsMatchIn(s) -> AutoReplyCommand.RULES_SHOW
                else -> null   // «правило» без действия — пусть ответит модель
            }
        }

        if (!isAboutAutoReply(s)) return null

        // просьба проверить / жалоба, что не работает — полная диагностика
        if (Regex("(?:проверь|диагностик|почему|что мешает|разберись)|не работает|не отвечает|не сработал|не срабатыва")
                .containsMatchIn(s) &&
            Regex("(?:автоответ|мессенджер|вацап|ватсап|телеграм|вотсап|проверь)").containsMatchIn(s)
        ) {
            return AutoReplyCommand.DIAG
        }

        // вопрос о состоянии / «что такое»
        if (Regex("включен|выключен|статус|работает|состояние|как сейчас|активен|что такое|что это|зачем|расскажи|объясни|настроен")
                .containsMatchIn(s)
        ) {
            return AutoReplyCommand.STATUS
        }
        val on = Regex("включ|вруб|актив|запуск|включился").containsMatchIn(s)
        val off = Regex("выключ|отключ|выруб|погас|стоп|прекрати|больше не надо").containsMatchIn(s)
        return when {
            on && !off -> AutoReplyCommand.ON
            off && !on -> AutoReplyCommand.OFF
            // «отвечай за меня в мессенджерах» без слова «включи» — тоже включить
            Regex("отвеча(?:й|ть)|отвечай сам").containsMatchIn(s) -> AutoReplyCommand.ON
            else -> AutoReplyCommand.STATUS
        }
    }

    fun isAboutAutoReply(s: String): Boolean =
        Regex("автоответ|авто ?ответчик|авто ?ответ|отвеча(?:й|ть) (?:за меня|сам)|ответы за меня|сам отвечай в мессенджер")
            .containsMatchIn(s)

    /**
     * Фраза про правила автоответа, даже если слова «автоответ» в ней нет:
     * «запомни правило: если зовут гулять — отвечай, что я занят».
     */
    fun isRuleCommand(s: String): Boolean {
        if (!Regex("правил").containsMatchIn(s)) return false
        if (isAboutAutoReply(s)) return true
        return Regex("автоответ|отвеча(?:й|ть)|пиши|писать|говори|не соглашайся").containsMatchIn(s) &&
            Regex("(добавь|запомни|запиши|установи|поставь|новое|очисти|удали|сбрось|какие|покажи)").containsMatchIn(s)
    }

    /** Текст правила из фразы «добавь правило: если зовут гулять — я занят». */
    fun autoRuleText(s: String): String? {
        Regex(
            "правил[а-я]*\\s*(?:для автоответа|автоответчика|в мессенджерах)?\\s*[:\\-—]\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { m ->
            val t = m.groupValues[1].trim()
            if (t.isNotEmpty()) return t
        }
        Regex(
            "(?:добавь|запомни|запиши|установи|поставь|новое)\\s+правил[а-я]*\\s*" +
                "(?:для автоответа|автоответчика|автоответа|в мессенджерах)?\\s*[:—\\-]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ).find(s)?.let { m ->
            val t = m.groupValues[1].trim()
            if (t.isNotEmpty()) return t
        }
        return null
    }
}
