package cn.luorenmu.command

import cn.luorenmu.alert.Alerting
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand

@BotCommand(id = "feedback", alias = "反馈", value = "<content>")
class FeedbackCommand : CommandEvent {
    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply {
        val content = sender.plainText.removePrefix("反馈").trim()
        if (content.isBlank()) return BotReply.Text("用法：反馈 <内容>")
        val ok = Alerting.forwardUserMessage(sender, title = "用户反馈", content = content)
        return if (ok) BotReply.Text("已转发给管理员") else BotReply.Text("转发失败：未配置 superAdmins 或当前适配器不支持")
    }
}
