package cn.luorenmu.qqbot.listen

import cn.luorenmu.command.CommandRouter
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.listen.EventHandle
import love.forte.simbot.component.qguild.event.QGGroupAtMessageCreateEvent
import love.forte.simbot.event.Event
import love.forte.simbot.message.OfflineImage

/**
 * @author LoMu
 * Date 2025/10/22 23:00
 */

/**
 * 官方BOT只有AT事件附带的消息才会发送给机器人
 */
class GroupAtMessageCreateListen : EventHandle {

    private val commandListenAllocator = CommandRouter()
    override suspend fun handle(event: Event) {
        val atEvent = event as QGGroupAtMessageCreateEvent

        val reply = commandListenAllocator.call(
            MessageSender(
                groupOpenId = event.id.toString(),
                senderName = event.author().name,
                senderOpenId = event.authorId.toString(),
                message = event.messageContent.messages.toString(),
                plainText = event.messageContent.plainText,
            )
        )

        suspend fun replyOne(r: BotReply) {
            when (r) {
                is BotReply.Text -> atEvent.reply(r.text)
                is BotReply.ImageFile -> atEvent.reply(OfflineImage.fileOfflineImage(r.path))
                is BotReply.Multi -> r.replies.forEach { replyOne(it) }
            }
        }
        if (reply == null) atEvent.reply("命令错误") else replyOne(reply)
    }

}
