package cn.luorenmu.command

import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.command.entity.BotReply

/**
 *
 * @author LoMu
 * Date 2025/10/24 13:27
 */
interface CommandEvent {
    suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply?
}
