package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.common.util.toPinyin
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.api.EternalReturnWebUrls
import cn.luorenmu.request.api.entity.response.dakgg.DakGGCharactersResponse
import cn.luorenmu.service.AliasService
import com.microsoft.playwright.Page
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.java.KoinJavaComponent.inject

@BotCommand(id = "character", alias = "查询角色", value = "<name> <weapon>")
class CharacterCommand : CommandEvent {
    private val log = KotlinLogging.logger {}
    private val aliasService: AliasService by inject(AliasService::class.java)

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val text = sender.plainText.trim().removePrefix("/")
        val remainder = extractArgsRemainder(text) ?: ""
        val args = remainder.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (args.isEmpty()) {
            return BotReply.Text("用法：查询角色<名称> [武器序号0/1/2/3] [段位(灭钻/星陨/无暇/in1000/in1k)] 例：查询角色杰琪 0 in1k")
        }

        var inputName = args[0].trim()
        val optionTokens = args.drop(1)

        // Support "no-space" suffix like "查询角色杰琪0in1k" or "查询角色杰琪0".
        var weaponIndex: Int? = null
        var tierKey: String? = null
        run {
            val parsed = parseInlineSuffix(inputName)
            inputName = parsed.name
            weaponIndex = parsed.weaponIndex
            tierKey = parsed.tier
        }

        for (t in optionTokens) {
            val token = t.trim()
            if (weaponIndex == null) {
                if (token.matches(Regex("^[0-3]$"))) {
                    weaponIndex = token.toIntOrNull()
                    continue
                }
                // Support "0in1k"/"1灭钻" forms (weapon index glued to tier).
                val m = Regex("^([0-3])(.+)$").find(token)
                if (m != null) {
                    weaponIndex = m.groupValues[1].toIntOrNull()
                    if (tierKey == null) tierKey = parseTierKey(m.groupValues[2])
                    continue
                }
            }
            if (tierKey == null) tierKey = parseTierKey(token)
        }
        val tier = tierKey ?: "diamond_plus"

        val groupId = sender.groupOpenId
        val userId = sender.senderOpenId
        val resolvedName = aliasService.resolve(
            AliasService.Namespace.CHARACTER,
            groupId = groupId,
            userId = userId,
            input = inputName
        ).value

        val characters = EternalReturnDakGGApiClient.getCharacters().characters
        val resolvedPinyin = resolvedName.toPinyin().lowercase()
        val exact = characters.firstOrNull { c ->
            c.name.equals(resolvedName, ignoreCase = true) ||
                c.key.equals(resolvedName, ignoreCase = true) ||
                c.name.toPinyin().lowercase() == resolvedPinyin ||
                c.key.lowercase() == resolvedPinyin
        } ?: run {
            characters.firstOrNull { c ->
                c.name.contains(resolvedName, ignoreCase = true) ||
                    c.key.contains(resolvedName, ignoreCase = true) ||
                    c.name.toPinyin().lowercase().contains(resolvedPinyin) ||
                    c.key.lowercase().contains(resolvedPinyin)
            }
        }

        if (exact == null) return BotReply.Text("未找到角色：$resolvedName")

        val weaponTypes = exact.weaponTypes
        val weaponNameByKey = runCatching {
            EternalReturnDakGGApiClient.getWeapons().masteries.associate { it.key to it.name }
        }.getOrElse { emptyMap() }
        val weaponTypeKey = weaponIndex?.let { idx ->
            if (idx >= 0 && idx < weaponTypes.size) weaponTypes[idx].key else ""
        } ?: ""

        val weaponKeyForFile = weaponTypeKey.takeIf { it.isNotBlank() } ?: "all"
        val outputPath = PathUtils.resourcesPathResolve(
            "render",
            "web",
            "character",
            "${exact.key}-${weaponKeyForFile}-${tier}.png"
        )
        val url = EternalReturnWebUrls.characterPage(
            characterKey = exact.key,
            weaponType = weaponTypeKey,
            tier = tier,
        )
        try {
            BrowserPool.getBrowser().screenshotSelector(
                url = url,
                output = outputPath,
                selector = ".contents",
            ) { page: Page ->
                page.waitForTimeout(3000.0)
                // Replace visible title to keep user input wording when possible.
                runCatching {
                    page.locator("div.title h3").evaluate(
                        """node => {
                            node.innerHTML = node.innerHTML.replace(/(<strong>.*?<\/strong>)([^<]+)/, "$1${resolvedName}");
                        }"""
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to screenshot character page: $url" }
            return BotReply.Text("网页截图失败：${e.message ?: "未知错误"}")
        }
        val weaponHint = buildWeaponHintIfNeeded(weaponTypes, weaponNameByKey, weaponIndex)
        return if (weaponHint == null) {
            BotReply.ImageFile(outputPath.toString())
        } else {
            BotReply.Multi(listOf(BotReply.ImageFile(outputPath.toString()), BotReply.Text(weaponHint)))
        }
    }

    private fun extractArgsRemainder(text: String): String? {
        val candidates = listOf(
            "查询角色",
            "查询实验体",
            "角色查询",
            "查询英雄",
            "查詢角色",
            "character",
        )
        val hit = candidates.firstOrNull { c -> text.startsWith(c, ignoreCase = true) } ?: return null
        return text.substring(hit.length).trim()
    }

    private data class InlineSuffixParse(
        val name: String,
        val weaponIndex: Int?,
        val tier: String?,
    )

    private fun parseInlineSuffix(raw: String): InlineSuffixParse {
        var s = raw.trim()
        var tier = parseTierKeyFromSuffix(s)
        if (tier != null) {
            s = removeTierSuffix(s)
        }

        var weaponIndex: Int? = null
        val m = Regex("^(.*?)([0-3])$").find(s)
        if (m != null) {
            s = m.groupValues[1].trim()
            weaponIndex = m.groupValues[2].toIntOrNull()
        }
        return InlineSuffixParse(name = s, weaponIndex = weaponIndex, tier = tier)
    }

    private fun parseTierKey(raw: String): String? {
        val s = raw.trim().lowercase()
        return when (s) {
            "灭钻", "灭钻+", "灭钻＋", "钻石", "钻石+", "钻石＋", "diamond_plus" -> "diamond_plus"
            "星陨", "星陨+", "星陨＋", "meteorite_plus" -> "meteorite_plus"
            "无暇", "无暇+", "无暇＋", "mithril_plus" -> "mithril_plus"
            "in1000", "in1k", "前1000", "top1000" -> "in1000"
            else -> null
        }
    }

    private fun parseTierKeyFromSuffix(raw: String): String? {
        val s = raw.trim()
        val candidates = listOf(
            "diamond_plus",
            "meteorite_plus",
            "mithril_plus",
            "in1000",
            "in1k",
            "灭钻+",
            "灭钻＋",
            "灭钻",
            "钻石+",
            "钻石＋",
            "钻石",
            "星陨+",
            "星陨＋",
            "星陨",
            "无暇+",
            "无暇＋",
            "无暇",
            "前1000",
            "top1000",
        )
        val hit = candidates.firstOrNull { c ->
            s.length > c.length && s.endsWith(c, ignoreCase = true)
        } ?: return null
        return parseTierKey(hit)
    }

    private fun removeTierSuffix(raw: String): String {
        val s = raw.trim()
        val candidates = listOf(
            "diamond_plus",
            "meteorite_plus",
            "mithril_plus",
            "in1000",
            "in1k",
            "灭钻+",
            "灭钻＋",
            "灭钻",
            "钻石+",
            "钻石＋",
            "钻石",
            "星陨+",
            "星陨＋",
            "星陨",
            "无暇+",
            "无暇＋",
            "无暇",
            "前1000",
            "top1000",
        )
        val hit = candidates.firstOrNull { c -> s.length > c.length && s.endsWith(c, ignoreCase = true) } ?: return s
        return s.dropLast(hit.length).trim()
    }

    private fun buildWeaponHintIfNeeded(
        weaponTypes: List<DakGGCharactersResponse.DakGGCharacterById.DakGGWeaponType>,
        weaponNameByKey: Map<String, String>,
        weaponIndex: Int?,
    ): String? {
        if (weaponTypes.size <= 1) return null
        if (weaponIndex != null && weaponIndex >= 0 && weaponIndex < weaponTypes.size) return null
        val list = weaponTypes.withIndex().joinToString(", ") { (i, wt) ->
            val name = weaponNameByKey[wt.key]?.takeIf { it.isNotBlank() } ?: wt.key
            "$i.$name"
        }
        return "可选武器序号：$list"
    }
}
