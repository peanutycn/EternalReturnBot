package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.HelpRender
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.render.FreemarkerRenderer

/**
 * Bot help command.
 *
 * Keep examples "no-slash" to match legacy usage.
 */
@BotCommand(id = "help", alias = "帮助", value = "")
class HelpCommand : CommandEvent {
    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply {
        val text =
            """
            常用指令（无需 /）：
            - 查询玩家 <名称> [模式数字]（默认排位）
              例：查询玩家 耄耋
            - 网页查询玩家 <名称>
              例：网页查询玩家 耄耋
            - 查询角色 <名称> [武器序号0/1/2/3] [段位(灭钻/星陨/无暇/in1000/in1k)]
              例：查询角色杰琪 0 in1k（通常只省略第一个空格即可）
            - 查询路线 <路线ID>（或 routes <路线ID>）
              例：查询路线 12345
            - 实验体统计 [段位(灭钻/星陨/无暇/in1000/in1k)]（或 角色统计 / 英雄统计 / statistics）
              例：实验体统计 无暇
            - 永恒/半神线（同义指令都可用）
              - 永恒线 [服务器]
              - 永恒多少分 / 永恒分数 / 查询永恒线 / 永恒分数线
              - 半神多少分 / 半神分数 / 查询半神线 / 半神线
              例：永恒线 亚一
            - 段位统计 [服务器]
              例：段位统计 亚一
            - 反馈 <内容>（转发给管理员）
              例：反馈 查询玩家截图异常

            别名（个人/本群/全局）：
            - 玩家别名 列表 [玩家名]
            - 玩家别名 设置 <别名> <玩家名>           （仅自己生效）
            - 玩家别名 群设置 <别名> <玩家名>         （本群共享，需权限）
            - 玩家别名 全局设置 <别名> <玩家名>       （全局，需权限）
            - 玩家别名 申请全局设置 <别名> <玩家名>   （无权限也可，转发给管理员）
            角色别名同理；发送：玩家别名 帮助 获取完整示例。
            """.trimIndent()

        val imageReply = runCatching {
            val output = PathUtils.resourcesPathResolve("render", "help", "help.png")
            val html = FreemarkerRenderer.render("help.ftl", HelpRender(title = "ERBot 帮助", text = text))
            BrowserPool.getBrowser().screenshotContentSelector(html, output, "#help-container")
            BotReply.ImageFile(output.toString())
        }.getOrNull()

        return if (imageReply == null) {
            BotReply.Text(text)
        } else {
            BotReply.Multi(listOf(BotReply.Text(text), imageReply))
        }
    }
}
