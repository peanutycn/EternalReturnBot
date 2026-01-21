package cn.luorenmu.command

import cn.luorenmu.Adapter
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.render.FreemarkerRenderer
import cn.luorenmu.service.EternalReturnRenderService
import cn.luorenmu.service.AliasService
import org.koin.java.KoinJavaComponent.inject

/**
 *
 * @author LoMu
 * Date 2025/11/29 15:34
 */
@BotCommand(id = "oldName", alias = "曾用名", value = "<nickname>", adapter = [Adapter.ONE_BOT])
class OldNameCommand : CommandEvent {
    private val eternalReturnRenderService: EternalReturnRenderService by inject(
        EternalReturnRenderService::class.java
    )
    private val aliasService: AliasService by inject(AliasService::class.java)


    override suspend fun listen(
        sender: MessageSender,
        command: Map<String, String>,
    ): BotReply? {
        val inputNickname = command["nickname"] ?: run {
            return BotReply.Text("请输入名称")
        }
        val nickname = aliasService.resolve(
            AliasService.Namespace.PLAYER,
            groupId = sender.groupOpenId,
            userId = sender.senderOpenId,
            input = inputNickname
        ).value

        val freeMarkerContent = FreemarkerRenderer.render(
            "old_name.ftl",
            eternalReturnRenderService.oldName(nickname)
        )
        val resourcesPathResolve = PathUtils.resourcesPathResolve("render", "old_name", "${nickname}.png")
        BrowserPool.getBrowser().screenshotContentSelector(
            freeMarkerContent,
            resourcesPathResolve,
            "#app"
        )

        return BotReply.ImageFile(resourcesPathResolve.toString())
    }
}
