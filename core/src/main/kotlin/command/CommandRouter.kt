package cn.luorenmu.command

import cn.luorenmu.command.entity.CommandFindResult
import cn.luorenmu.command.entity.CommandInfo
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.ReflectionUtil
import cn.luorenmu.currentAdapter
import cn.luorenmu.exception.MessageReplyException
import io.github.oshai.kotlinlogging.KotlinLogging


/**
 *
 * @author LoMu
 * Date 2025/10/24 13:42
 */
private val log = KotlinLogging.logger {}

class CommandRouter {
    companion object {
        private val LEGACY_SYNONYMS: Map<String, String> = mapOf(
            // Old repo supported multiple triggers for the same feature.
            "玩家查询" to "查询玩家",
            "战绩查询" to "查询玩家",
            "查询战绩" to "查询玩家",
            "help" to "帮助",

            // Natural language alias management.
            "玩家别名" to "alias",
            "角色别名" to "alias",

            // Permission diagnosis (routes into AliasCommand).
            "别名权限" to "alias",
            "权限诊断" to "alias",

            // Cutoffs.
            "半神线" to "永恒线",
            "永恒分数" to "永恒线",
            "半神分数" to "永恒线",
            "查询半神" to "永恒线",
            "查询永恒" to "永恒线",
            "永恒多少分" to "永恒线",
            "半神多少分" to "永恒线",
            "查询永恒线" to "永恒线",
            "查询半神线" to "永恒线",
            // Old repo command names for tier distribution.
            "永恒分段" to "段位统计",
            "半神分段" to "段位统计",

            // Web screenshot features from old repo.
            "网页查询" to "网页查询玩家",
            "查询实验体" to "查询角色",
            "查询路径" to "查询路线",
            "角色查询" to "查询角色",
            "查询英雄" to "查询角色",
            "查詢角色" to "查询角色",
            "英雄统计" to "实验体统计",
            "角色统计" to "实验体统计",
        )

        private val URL_TRIGGERS: List<Pair<Regex, String>> = listOf(
            Regex("https://playeternalreturn.com/posts/news/([0-9]{4,6})") to "news",
        )

        private fun normalizeCommandKey(raw: String): String =
            LEGACY_SYNONYMS[sanitizeKey(raw)] ?: sanitizeKey(raw)

        private fun sanitizeKey(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            // Strip common trailing punctuation from chat messages.
            s = s.trimEnd('?', '？', '!', '！', '。', '.', '，', ',', '、', ';', '；', ':', '：')
            return s.trim()
        }

        // Only these commands support "no-space" prefix form, e.g. "查询玩家耄耋".
        // Keep this list minimal to avoid false positives like "帮助我..." or "helpful".
        private val NO_SPACE_PREFIX_KEYS: Set<String> = setOf(
            "查询玩家",
            "玩家查询",
            "查询战绩",
            "战绩查询",

            "查询角色",
            "查询实验体",
            "角色查询",
            "查询英雄",
            "查詢角色",

            "网页查询玩家",
            "查询路线",
            "routes",
            "官网更新截图",
        )

        private val PREFIX_KEYS: List<String> by lazy {
            // Longest-first match to avoid partial prefix collisions.
            (LEGACY_SYNONYMS.keys + COMMANDS.keys)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedByDescending { it.length }
        }

        /**
         * KEY IS ALIAS
         * VALUE IS CommandInfo
         */
        private val COMMANDS by lazy {
            val c = ReflectionUtil.getSubTypesOf(this::class.java.packageName, CommandEvent::class.java)
            val result = mutableMapOf<String, CommandInfo>()
            for (klass in c) {
                if (klass.isAnnotationPresent(BotCommand::class.java)) {
                    val command = klass.getAnnotation(BotCommand::class.java)
                    val obj = klass.getDeclaredConstructor().newInstance() as CommandEvent
                    if (command.adapter.contains(currentAdapter)) {
                        val info = CommandInfo(command, obj)
                        result[command.alias] = info
                        result[command.id] = info
                    }
                }
            }
            log.debug { "current adapter -> $currentAdapter , load command ${result.keys}" }
            result
        }

    }

    suspend fun call(messageSender: MessageSender): BotReply? {
        val start = System.currentTimeMillis()
        val found = commandFind(messageSender.plainText) ?: return null
        return try {
            log.info { "Command start: ${found.eventObj::class.java.simpleName} group=${messageSender.groupOpenId} user=${messageSender.senderOpenId}" }
            val result = found.eventObj.listen(messageSender, found.commandParse)
            log.info { "Command done: ${found.eventObj::class.java.simpleName} costMs=${System.currentTimeMillis() - start}" }
            result
        } catch (e: MessageReplyException) {
            log.info { "Command rejected: ${found.eventObj::class.java.simpleName} costMs=${System.currentTimeMillis() - start}" }
            e.returnMsg
        } catch (e: Exception) {
            log.warn(e) { "Command failed: ${found.eventObj::class.java.simpleName} costMs=${System.currentTimeMillis() - start}" }
            try {
                cn.luorenmu.alert.Alerting.reportException(
                    sender = messageSender,
                    commandName = found.eventObj::class.java.simpleName,
                    error = e,
                )
            } catch (_: Exception) {
                // ignore
            }
            BotReply.Text("执行失败：${e.message ?: "未知错误"}")
        }
    }

    fun commandFind(plainText: String): CommandFindResult? {
        if (plainText.isEmpty()) return null
        // Regex triggers (e.g. official news URL).
        for ((regex, targetId) in URL_TRIGGERS) {
            val m = regex.find(plainText)
            if (m != null) {
                val cmd = COMMANDS[targetId] ?: continue
                val id = m.groupValues.getOrNull(1).orEmpty()
                val syntheticInput = "$targetId $id"
                return CommandFindResult(cmd.commandEvent, parseCommand(cmd.command.value, syntheticInput))
            }
        }
        if (plainText.startsWith("/")) {
            val originCommand = plainText.replaceFirst("/", "")
            val inputCommand = originCommand.split("\\s".toRegex())
            val inputCommandFirst = normalizeCommandKey(inputCommand[0])
            // Keep help strict: only "/help" or "/帮助" (no extra args).
            if (inputCommandFirst == "帮助" && inputCommand.size > 1) return null
            val command = COMMANDS[inputCommandFirst] ?: run { return null }
            return CommandFindResult(
                command.commandEvent,
                parseCommand(command.command.value, originCommand)
            )
        }
        // Support legacy style commands without leading '/', e.g. "查询玩家 xxx".
        val originCommand = plainText.trim()
        val inputCommand = originCommand.split("\\s".toRegex())
        val inputCommandFirst = normalizeCommandKey(inputCommand.firstOrNull()?.trim().orEmpty())
        if (inputCommandFirst.isEmpty()) return null
        // Keep help strict: only "help/帮助" (no extra args).
        if (inputCommandFirst == "帮助" && inputCommand.size > 1) return null

        val direct = COMMANDS[inputCommandFirst]
        if (direct != null) {
            return CommandFindResult(
                direct.commandEvent,
                parseCommand(direct.command.value, originCommand)
            )
        }

        // Support "no-space" commands like "查询玩家摸余ovo" / "查询角色威廉".
        val matchedPrefix = PREFIX_KEYS.firstOrNull { k ->
            originCommand.length > k.length && originCommand.startsWith(k)
        } ?: return null

        // Only allow no-space form for selected commands to avoid accidental triggers.
        if (!NO_SPACE_PREFIX_KEYS.contains(matchedPrefix)) return null

        val normalizedKey = normalizeCommandKey(matchedPrefix)
        if (normalizedKey == "帮助") return null
        val cmd = COMMANDS[normalizedKey] ?: return null
        val remainder = originCommand.substring(matchedPrefix.length).trim()
        val syntheticInput = "$matchedPrefix $remainder"
        return CommandFindResult(
            cmd.commandEvent,
            parseCommand(cmd.command.value, syntheticInput)
        )
    }


    /**
     * @param template 命令样式  sample -> <nickname> <mode> <season>
     * @param input    输入内容  sample -> 查询玩家 螺母 钴协议 赛季6
     * @return 当为null时 不符合条件 否则正常返回   sample -> {nickname=螺母, mode=钴协议, season=赛季6}
     *
     */
    private fun parseCommand(template: String, input: String): Map<String, String> {
        val templateSpilt = template.split("\\s+".toRegex())
        val inputSplit = input.split("\\s+".toRegex()).drop(1)
        val commandMap = mutableMapOf<String, String>()
        val regexPattern = "<(.*?)>".toRegex()
        for ((index, value) in templateSpilt.withIndex()) {
            if (index > inputSplit.size - 1) break
            val result = regexPattern.find(value)
            val key = result?.groupValues[1] ?: continue
            commandMap[key] = inputSplit[index]
        }
        return commandMap
    }

}
