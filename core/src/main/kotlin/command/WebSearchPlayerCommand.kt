package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.api.EternalReturnWebUrls
import cn.luorenmu.service.AliasService
import com.microsoft.playwright.Page
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.java.KoinJavaComponent.inject

@BotCommand(id = "web_player", alias = "网页查询玩家", value = "<nickname>")
class WebSearchPlayerCommand : CommandEvent {
    private val log = KotlinLogging.logger {}
    private val aliasService: AliasService by inject(AliasService::class.java)

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val inputNickname = command["nickname"]?.trim().orEmpty()
        if (inputNickname.isBlank()) return BotReply.Text("用法：网页查询玩家 <名称>")

        val nickname = aliasService.resolve(
            AliasService.Namespace.PLAYER,
            groupId = sender.groupOpenId,
            userId = sender.senderOpenId,
            input = inputNickname
        ).value

        if (nickname.length < 2 || nickname.contains("@")) {
            return BotReply.Text("名称不合法：$nickname")
        }

        val syncOk = runCatching { EternalReturnDakGGApiClient.syncPlayer(nickname) }.getOrDefault(true)
        if (!syncOk) {
            return BotReply.Text("不存在的玩家 -> $nickname")
        }

        val url = EternalReturnWebUrls.playerPage(nickname)
        val outputPath = PathUtils.resourcesPathResolve("render", "web", "player", "${nickname}.png")

        try {
            BrowserPool.getBrowser().screenshotSelector(
                url = url,
                output = outputPath,
                selector = "#content-container",
            ) { page: Page ->
                page.waitForTimeout(3000.0)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to screenshot player page: $url" }
            return BotReply.Text("网页截图失败：${e.message ?: "未知错误"}")
        }

        return BotReply.ImageFile(outputPath.toString())
    }
}
