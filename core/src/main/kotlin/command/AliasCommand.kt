package cn.luorenmu.command

import cn.luorenmu.Adapter
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.HelpRender
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.CommandTextNormalizer
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.config.AppConfig
import cn.luorenmu.currentAdapter
import cn.luorenmu.render.FreemarkerRenderer
import cn.luorenmu.service.AliasService
import cn.luorenmu.service.OneBotRoleService
import org.koin.java.KoinJavaComponent.inject

/**
 * Alias command for multiple namespaces (player/character) with 3 scopes:
 * - personal: /alias <ns> set|del|get|list ...
 * - group:    /alias <ns> gset|gdel|gget|glist ...
 * - global:   /alias <ns> aset|adel|aget|alist ...
 */
@BotCommand(id = "alias", alias = "alias", value = "<args>")
class AliasCommand : CommandEvent {
    private val aliasService: AliasService by inject(AliasService::class.java)
    private val oneBotRoleService: OneBotRoleService by inject(OneBotRoleService::class.java)

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply {
        val parts = sender.plainText.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return help()
        val head = CommandTextNormalizer.normalize(parts[0].trim())
        val headNoSlash = head.removePrefix("/")

        // Permission diagnosis (superAdmins only).
        // Usage (no leading '/'): 别名权限 诊断
        if (head == "别名权限" || head == "权限诊断") {
            return handlePermissionDiagnosis(sender)
        }

        // Natural language shortcuts (no leading '/'):
        // 玩家别名 列表|设置|删除 ...
        // 角色别名 列表|设置|删除 ...
        if (head == "玩家别名" || head == "角色别名") {
            val namespace = if (head == "玩家别名") AliasService.Namespace.PLAYER else AliasService.Namespace.CHARACTER
            return handleCnAlias(namespace, parts, sender)
        }

        if (headNoSlash.lowercase() != "alias") return help()
        if (parts.size < 2) return help()

        val groupId = sender.groupOpenId
        val userId = sender.senderOpenId
        val userRole = sender.senderRole

        if (isManagerCommand(parts[1])) {
            return handleManagerCommand(parts, groupId, userId, userRole)
        }

        val namespace = parseNamespace(parts[1]) ?: return BotReply.Text("命名空间错误：支持 player / character")
        val action = parts.getOrNull(2)?.lowercase() ?: return help()

        val (scope, verb) = parseScopeAndVerb(action) ?: return help()

        return when (verb) {
            "help" -> help()
            "list" -> {
                val nicknameFilter = parts.getOrNull(3)
                listAliases(namespace, scope, groupId, userId, nicknameFilter)
            }

            "get" -> {
                val alias = parts.getOrNull(3) ?: return BotReply.Text("用法：/alias ${namespace.key} ${action} <别名>")
                val entry = aliasService.get(namespace, scope, groupId, userId, alias)
                if (entry == null) BotReply.Text("未找到：$alias")
                else BotReply.Text("${alias} -> ${entry.value}")
            }

            "set" -> {
                val alias = parts.getOrNull(3) ?: return BotReply.Text("用法：/alias ${namespace.key} ${action} <别名> <名称>")
                val value = parts.getOrNull(4) ?: return BotReply.Text("用法：/alias ${namespace.key} ${action} <别名> <名称>")
                if (!canWrite(scope, groupId, userId, userRole)) return noPermission(scope)
                aliasService.set(namespace, scope, groupId, userId, alias, value, operatorUserId = userId)
                BotReply.Text("已设置：$alias -> $value")
            }

            "del" -> {
                val alias = parts.getOrNull(3) ?: return BotReply.Text("用法：/alias ${namespace.key} ${action} <别名>")
                if (!canWrite(scope, groupId, userId, userRole)) return noPermission(scope)
                val removed = aliasService.remove(namespace, scope, groupId, userId, alias)
                if (removed) BotReply.Text("已删除：$alias") else BotReply.Text("未找到：$alias")
            }

            else -> help()
        }
    }

    private fun isManagerCommand(raw: String): Boolean {
        val v = CommandTextNormalizer.normalize(raw).lowercase()
        return v == "manager" || v == "perm" || v == "权限" || v == "管理员"
    }

    private suspend fun handleManagerCommand(parts: List<String>, groupId: String, userId: String, userRole: String?): BotReply {
        val sub = CommandTextNormalizer.normalize(parts.getOrNull(2).orEmpty()).lowercase().ifEmpty { "list" }
        return when (sub) {
            "list", "ls" -> {
                val managers = aliasService.listManagers(groupId)
                if (managers.isEmpty()) BotReply.Text("当前群未设置额外管理员")
                else BotReply.Text("已授权管理员：${managers.joinToString(",")}")
            }

            "add", "set" -> {
                if (!canWrite(AliasService.Scope.GROUP, groupId, userId, userRole)) return noPermission(AliasService.Scope.GROUP)
                val target = parts.getOrNull(3) ?: return BotReply.Text("用法：/alias manager add <QQ号>")
                val changed = aliasService.addManager(groupId, target)
                if (changed) BotReply.Text("已授权：$target") else BotReply.Text("已存在：$target")
            }

            "del", "rm", "remove" -> {
                if (!canWrite(AliasService.Scope.GROUP, groupId, userId, userRole)) return noPermission(AliasService.Scope.GROUP)
                val target = parts.getOrNull(3) ?: return BotReply.Text("用法：/alias manager del <QQ号>")
                val changed = aliasService.removeManager(groupId, target)
                if (changed) BotReply.Text("已移除授权：$target") else BotReply.Text("未找到授权：$target")
            }

            else -> BotReply.Text("用法：/alias manager list|add|del ...")
        }
    }

    private fun parseNamespace(raw: String): AliasService.Namespace? =
        when (CommandTextNormalizer.normalize(raw).lowercase()) {
            "player", "p", "玩家" -> AliasService.Namespace.PLAYER
            "character", "c", "角色" -> AliasService.Namespace.CHARACTER
            else -> null
        }

    private fun parseScopeAndVerb(action: String): Pair<AliasService.Scope, String>? {
        val normalized = CommandTextNormalizer.normalize(action).lowercase()
        return when {
            normalized == "list" -> AliasService.Scope.PERSONAL to "list"
            normalized == "get" -> AliasService.Scope.PERSONAL to "get"
            normalized == "set" -> AliasService.Scope.PERSONAL to "set"
            normalized == "del" || normalized == "rm" -> AliasService.Scope.PERSONAL to "del"

            normalized == "glist" -> AliasService.Scope.GROUP to "list"
            normalized == "gget" -> AliasService.Scope.GROUP to "get"
            normalized == "gset" -> AliasService.Scope.GROUP to "set"
            normalized == "gdel" || normalized == "grm" -> AliasService.Scope.GROUP to "del"

            normalized == "alist" -> AliasService.Scope.GLOBAL to "list"
            normalized == "aget" -> AliasService.Scope.GLOBAL to "get"
            normalized == "aset" -> AliasService.Scope.GLOBAL to "set"
            normalized == "adel" || normalized == "arm" -> AliasService.Scope.GLOBAL to "del"

            normalized == "help" || normalized == "h" || normalized == "?" || normalized == "？" -> AliasService.Scope.PERSONAL to "help"
            else -> null
        }
    }

    private suspend fun listAliases(
        namespace: AliasService.Namespace,
        scope: AliasService.Scope,
        groupId: String,
        userId: String,
        nicknameFilter: String?,
    ): BotReply {
        val byAlias = aliasService.listByAlias(namespace, scope, groupId, userId)
        if (byAlias.isEmpty()) return BotReply.Text("没有别名记录")

        val nicknameToAliases = byAlias.entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, aliases) -> aliases.sorted() }
            .toSortedMap()

        val rawArg = nicknameFilter?.trim()?.takeIf { it.isNotBlank() }
        val (page, nameFilter) = parseListArg(rawArg)
        if (nameFilter != null) {
            val matchedNickname = nicknameToAliases.keys.firstOrNull { it.equals(nameFilter, ignoreCase = true) }
                ?: return BotReply.Text("未找到名称：$nameFilter")
            val aliases = nicknameToAliases[matchedNickname].orEmpty()
            return BotReply.Text("${matchedNickname} 的别名：${aliases.joinToString(",")}")
        }

        val maxPlayers = 50
        val total = nicknameToAliases.size
        val totalPages = ((total + maxPlayers - 1) / maxPlayers).coerceAtLeast(1)
        val p = page.coerceAtLeast(1)
        if (p > totalPages) {
            return BotReply.Text("页码超出范围：共 $totalPages 页。例：${cnListHint(namespace, scope)} 第2页")
        }
        val slice = nicknameToAliases.entries.drop((p - 1) * maxPlayers).take(maxPlayers)
        val body = slice.joinToString("\n") { (nickname, aliases) ->
            "${nickname}：${aliases.joinToString(",")}"
        }
        val suffix = if (total > maxPlayers) {
            "\n...（第 $p/$totalPages 页，每页 $maxPlayers 个名称，共 $total 个）\n翻页：${cnListHint(namespace, scope)} 第${(p + 1).coerceAtMost(totalPages)}页"
        } else ""
        return BotReply.Text(body + suffix)
    }

    private fun parseListArg(raw: String?): Pair<Int, String?> {
        if (raw.isNullOrBlank()) return 1 to null
        val s = raw.trim()
        // Page formats: "2", "第2页", "2页", "p2", "page2"
        Regex("^第?(\\d+)页?$").find(s)?.let { m ->
            val page = m.groupValues[1].toIntOrNull() ?: 1
            return page to null
        }
        Regex("^(?i)(p|page)(\\d+)$").find(s)?.let { m ->
            val page = m.groupValues[2].toIntOrNull() ?: 1
            return page to null
        }
        return 1 to s
    }

    private fun cnListHint(namespace: AliasService.Namespace, scope: AliasService.Scope): String {
        val head = if (namespace == AliasService.Namespace.PLAYER) "玩家别名" else "角色别名"
        val tail = when (scope) {
            AliasService.Scope.PERSONAL -> "列表"
            AliasService.Scope.GROUP -> "群列表"
            AliasService.Scope.GLOBAL -> "全局列表"
        }
        return "$head $tail"
    }

    private suspend fun canWrite(scope: AliasService.Scope, groupId: String, userId: String, userRole: String?): Boolean {
        val normalizedUserId = normalizeUserId(userId)
        val isSuperAdmin = normalizedUserId.isNotEmpty() && AppConfig.alias.superAdmins.contains(normalizedUserId)
        val isOwnerOrAdminByEvent = userRole?.lowercase() == "owner" || userRole?.lowercase() == "admin"

        return when (scope) {
            AliasService.Scope.PERSONAL -> true

            AliasService.Scope.GROUP -> {
                if (isSuperAdmin) return true
                if (aliasService.isGroupManager(groupId, userId)) return true
                if (currentAdapter == Adapter.ONE_BOT && isOwnerOrAdminByEvent) return true
                currentAdapter == Adapter.ONE_BOT && oneBotRoleService.isGroupAdminOrOwner(groupId, userId)
            }

            AliasService.Scope.GLOBAL -> {
                if (isSuperAdmin) return true
                AppConfig.alias.allowGroupAdminGlobal &&
                    currentAdapter == Adapter.ONE_BOT && (isOwnerOrAdminByEvent || oneBotRoleService.isGroupAdminOrOwner(groupId, userId))
            }
        }
    }

    private fun noPermission(scope: AliasService.Scope): BotReply {
        return when (scope) {
            AliasService.Scope.PERSONAL -> BotReply.Text("权限不足")
            AliasService.Scope.GROUP -> BotReply.Text("权限不足：需要群主/管理员、群授权管理员，或 superAdmins")
            AliasService.Scope.GLOBAL -> BotReply.Text("权限不足：需要 superAdmins（或开启 allowGroupAdminGlobal）。可用：玩家别名 申请全局设置/申请全局删除")
        }
    }

    private suspend fun handlePermissionDiagnosis(sender: MessageSender): BotReply {
        val groupId = sender.groupOpenId
        val userId = sender.senderOpenId
        val userRole = sender.senderRole

        val normalizedUserId = normalizeUserId(userId)
        val isSuperAdmin = normalizedUserId.isNotEmpty() && AppConfig.alias.superAdmins.contains(normalizedUserId)
        if (!isSuperAdmin) {
            return BotReply.Text("权限不足：该诊断仅对 superAdmins 开放")
        }

        val isGroupManager = aliasService.isGroupManager(groupId, userId)
        val roleCheck = if (currentAdapter == Adapter.ONE_BOT) oneBotRoleService.diagnoseGroupAdminOrOwner(groupId, userId) else null

        val lines = mutableListOf<String>()
        lines += "adapter=$currentAdapter"
        lines += "groupIdRaw=$groupId"
        lines += "userIdRaw=$userId"
        lines += "senderRole=${userRole ?: "null"}"
        lines += "isSuperAdmin=true"
        lines += "isGroupManager=$isGroupManager"
        lines += "enableOneBotRoleCheck=${AppConfig.alias.enableOneBotRoleCheck}"
        lines += "allowGroupAdminGlobal=${AppConfig.alias.allowGroupAdminGlobal}"
        lines += "onebot.apiServerHost=${AppConfig.oneBot.apiServerHost.trim().ifEmpty { "<empty>" }}"
        lines += "onebot.eventServerHost=${AppConfig.oneBot.eventServerHost.trim().ifEmpty { "<empty>" }}"
        lines += "onebot.tokenPresent=${!AppConfig.oneBot.accessToken.isNullOrBlank()}"

        if (roleCheck == null) {
            lines += "onebotRoleCheck=<skipped: adapter is not ONE_BOT>"
        } else {
            lines += "onebotRoleCheck.parsedGroupId=${roleCheck.parsedGroupId}"
            lines += "onebotRoleCheck.parsedUserId=${roleCheck.parsedUserId}"
            lines += "onebotRoleCheck.httpStatus=${roleCheck.httpStatus ?: "null"}"
            lines += "onebotRoleCheck.status=${roleCheck.oneBotStatus ?: "null"}"
            lines += "onebotRoleCheck.retcode=${roleCheck.oneBotRetcode ?: "null"}"
            lines += "onebotRoleCheck.role=${roleCheck.oneBotRole ?: "null"}"
            lines += "onebotRoleCheck.allowed=${roleCheck.allowed ?: "null"}"
            if (!roleCheck.error.isNullOrBlank()) lines += "onebotRoleCheck.error=${roleCheck.error}"
            if (!roleCheck.responseSnippet.isNullOrBlank()) lines += "onebotRoleCheck.response=${roleCheck.responseSnippet}"
        }

        return BotReply.Text(lines.joinToString("\n"))
    }

    private fun help(): BotReply {
        val text =
            """
        别名系统有 3 个层级（优先级：个人 > 本群 > 全局）：
        1) 个人：只有你自己生效（默认；与群无关，跨群通用）
        2) 本群：群内共享（需权限）
        3) 全局：所有群共享（需权限）

        玩家别名（无需 /）：
        - 玩家别名 列表
        - 玩家别名 列表 <玩家名>                 （只看某个玩家的全部别名）
        - 玩家别名 设置 <别名> <玩家名>           （个人）
          例：玩家别名 设置 md 耄耋
        - 玩家别名 删除 <别名>                   （个人）
          例：玩家别名 删除 md

        无需权限的“查看”：
        - 玩家别名 群列表
        - 玩家别名 群列表 <玩家名>
          例：玩家别名 群列表 第2页
        - 玩家别名 全局列表
        - 玩家别名 全局列表 <玩家名>
          例：玩家别名 全局列表 第2页

        - 玩家别名 群设置 <别名> <玩家名>         （本群共享，需群管理员权限）
          例：玩家别名 群设置 md 耄耋
        - 玩家别名 群删除 <别名>
          例：玩家别名 群删除 md

        - 玩家别名 全局设置 <别名> <玩家名>       （全局，需superAdmins权限）
          例：玩家别名 全局设置 md 耄耋
        - 玩家别名 全局删除 <别名>
          例：玩家别名 全局删除 md

        无权限但想改全局时（自动转发给 superAdmins 审核）：
        - 玩家别名 申请全局设置 <别名> <玩家名>
          例：玩家别名 申请全局设置 md 耄耋
        - 玩家别名 申请全局删除 <别名>
          例：玩家别名 申请全局删除 md

        角色别名同理（把“玩家别名”换成“角色别名”）。

        管理员（本群额外授权，需权限）：
        - 玩家别名 管理员 列表
        - 玩家别名 管理员 添加 <QQ号>
        - 玩家别名 管理员 删除 <QQ号>
        """.trimIndent()
        val imageReply = runCatching {
            val output = PathUtils.resourcesPathResolve("render", "help", "alias_help.png")
            val html = FreemarkerRenderer.render("help.ftl", HelpRender(title = "别名帮助", text = text))
            BrowserPool.getBrowser().screenshotContentSelector(html, output, "#help-container")
            BotReply.ImageFile(output.toString())
        }.getOrNull()

        return if (imageReply == null) BotReply.Text(text) else BotReply.Multi(listOf(BotReply.Text(text), imageReply))
    }

    private fun normalizeUserId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return Regex("\\d+").findAll(trimmed).toList().lastOrNull()?.value.orEmpty()
    }

    private data class CnAction(
        val scope: AliasService.Scope,
        val verb: String,
        val consumedTokens: Int,
    )

    private fun parseCnAction(parts: List<String>, startIndex: Int): CnAction? {
        val raw = CommandTextNormalizer.normalize(parts.getOrNull(startIndex)?.trim().orEmpty())
        if (raw.isEmpty()) return CnAction(AliasService.Scope.PERSONAL, "help", 0)

        fun verbFromToken(t: String): String? = when (t) {
            "列表", "list", "ls" -> "list"
            "设置", "set", "add" -> "set"
            "删除", "del", "rm" -> "del"
            "查询", "get" -> "get"
            "申请" -> "apply"
            "管理员" -> "manager"
            "帮助", "help", "h", "?", "？" -> "help"
            else -> null
        }

        // Compact Chinese forms: 群设置 / 全局删除 ...
        when (raw) {
            "群列表" -> return CnAction(AliasService.Scope.GROUP, "list", 1)
            "群设置" -> return CnAction(AliasService.Scope.GROUP, "set", 1)
            "群删除" -> return CnAction(AliasService.Scope.GROUP, "del", 1)
            "群查询" -> return CnAction(AliasService.Scope.GROUP, "get", 1)

            "全局列表" -> return CnAction(AliasService.Scope.GLOBAL, "list", 1)
            "全局设置" -> return CnAction(AliasService.Scope.GLOBAL, "set", 1)
            "全局删除" -> return CnAction(AliasService.Scope.GLOBAL, "del", 1)
            "全局查询" -> return CnAction(AliasService.Scope.GLOBAL, "get", 1)
        }

        // Request global ops without requiring permissions.
        when (raw) {
            "申请全局设置" -> return CnAction(AliasService.Scope.GLOBAL, "apply_set", 1)
            "申请全局删除" -> return CnAction(AliasService.Scope.GLOBAL, "apply_del", 1)
        }

        // Short forms aligned with /alias: gset/gdel/glist, aset/adel/alist...
        when (raw.lowercase()) {
            "glist" -> return CnAction(AliasService.Scope.GROUP, "list", 1)
            "gget" -> return CnAction(AliasService.Scope.GROUP, "get", 1)
            "gset" -> return CnAction(AliasService.Scope.GROUP, "set", 1)
            "gdel", "grm" -> return CnAction(AliasService.Scope.GROUP, "del", 1)

            "alist" -> return CnAction(AliasService.Scope.GLOBAL, "list", 1)
            "aget" -> return CnAction(AliasService.Scope.GLOBAL, "get", 1)
            "aset" -> return CnAction(AliasService.Scope.GLOBAL, "set", 1)
            "adel", "arm" -> return CnAction(AliasService.Scope.GLOBAL, "del", 1)
        }

        // Split Chinese forms: 群 设置 / 全局 列表 ...
        when (raw) {
            "群", "本群", "群内" -> {
                val v = verbFromToken(CommandTextNormalizer.normalize(parts.getOrNull(startIndex + 1)?.trim().orEmpty())) ?: return null
                return CnAction(AliasService.Scope.GROUP, v, 2)
            }

            "全局" -> {
                val v = verbFromToken(CommandTextNormalizer.normalize(parts.getOrNull(startIndex + 1)?.trim().orEmpty())) ?: return null
                if (v == "apply") {
                    val op = CommandTextNormalizer.normalize(parts.getOrNull(startIndex + 2)?.trim().orEmpty())
                    val mapped = when (op) {
                        "设置", "set", "add" -> "apply_set"
                        "删除", "del", "rm" -> "apply_del"
                        else -> "apply_set"
                    }
                    return CnAction(AliasService.Scope.GLOBAL, mapped, 3)
                }
                return CnAction(AliasService.Scope.GLOBAL, v, 2)
            }
        }

        val verb = verbFromToken(raw) ?: return null
        return CnAction(AliasService.Scope.PERSONAL, verb, 1)
    }

    private suspend fun handleCnAlias(
        namespace: AliasService.Namespace,
        parts: List<String>,
        sender: MessageSender,
    ): BotReply {
        val groupId = sender.groupOpenId
        val userId = sender.senderOpenId
        val userRole = sender.senderRole

        val action = parseCnAction(parts, 1) ?: return BotReply.Text(
            "未知操作：${parts.getOrNull(1).orEmpty()}（支持：列表/设置/删除/群列表/群设置/群删除/全局列表/全局设置/全局删除，也支持 gset/aset/glist/alist 等）"
        )

        val scope = action.scope
        val verb = action.verb
        if (verb == "help") return help()
        val argsStart = 1 + action.consumedTokens

        if ((verb == "apply_set" || verb == "apply_del") && scope != AliasService.Scope.GLOBAL) {
            return BotReply.Text("仅支持申请全局操作：申请全局设置 / 申请全局删除")
        }

        if (verb == "apply_set" || verb == "apply_del") {
            if (!AppConfig.alert.forwardAliasRequestsEnabled) {
                return BotReply.Text("该功能未启用：lomu.alert.aliasRequest.enable=false")
            }
            val alias = parts.getOrNull(argsStart) ?: return BotReply.Text("用法：${parts[0]} 申请全局${if (verb == "apply_set") "设置" else "删除"} <别名>${if (verb == "apply_set") " <名称>" else ""}")
            val value = if (verb == "apply_set") parts.getOrNull(argsStart + 1) else null
            if (verb == "apply_set" && value.isNullOrBlank()) {
                return BotReply.Text("用法：${parts[0]} 申请全局设置 <别名> <名称>")
            }
            val nsCn = if (namespace == AliasService.Namespace.PLAYER) "玩家" else "角色"
            val actionCn = if (verb == "apply_set") "设置" else "删除"
            val text = cn.luorenmu.alert.Alerting.buildGlobalAliasRequestText(
                namespaceCn = nsCn,
                actionCn = actionCn,
                alias = alias,
                value = value,
                sender = sender,
            )
            val ok = cn.luorenmu.alert.Alerting.forwardUserMessage(sender, title = "别名申请（全局）", content = text)
            return if (ok) {
                BotReply.Text("已转发给管理员审核：${nsCn}别名 全局${actionCn} $alias")
            } else {
                BotReply.Text("转发失败：未配置 superAdmins 或当前适配器不支持")
            }
        }

        if ((verb == "set" || verb == "del") && !canWrite(scope, groupId, userId, userRole)) {
            return noPermission(scope)
        }

        return when (verb) {
            "list" -> listAliases(namespace, scope, groupId, userId, parts.getOrNull(argsStart))
            "get" -> {
                val alias = parts.getOrNull(argsStart) ?: return BotReply.Text("用法：${parts[0]} ${parts.getOrNull(1).orEmpty()} <别名>")
                val entry = aliasService.get(namespace, scope, groupId, userId, alias)
                if (entry == null) BotReply.Text("未找到：$alias")
                else BotReply.Text("${alias} -> ${entry.value}")
            }

            "set" -> {
                val alias = parts.getOrNull(argsStart) ?: return BotReply.Text("用法：${parts[0]} ${parts.getOrNull(1).orEmpty()} <别名> <名称>")
                val value = parts.getOrNull(argsStart + 1) ?: return BotReply.Text("用法：${parts[0]} ${parts.getOrNull(1).orEmpty()} <别名> <名称>")
                aliasService.set(namespace, scope, groupId, userId, alias, value, operatorUserId = userId)
                BotReply.Text("已设置：$alias -> $value")
            }

            "del" -> {
                val alias = parts.getOrNull(argsStart) ?: return BotReply.Text("用法：${parts[0]} ${parts.getOrNull(1).orEmpty()} <别名>")
                val removed = aliasService.remove(namespace, scope, groupId, userId, alias)
                if (removed) BotReply.Text("已删除：$alias") else BotReply.Text("未找到：$alias")
            }

            else -> help()
        }
    }
}
