package cn.luorenmu.exception

import cn.luorenmu.command.entity.BotReply

/**
 *
 * @author LoMu
 * Date 2025/11/27 12:39
 */
class ForbiddenException : MessageReplyException(BotReply.Text("服务器禁止了本次访问")) {
}
