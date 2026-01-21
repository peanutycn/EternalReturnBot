package cn.luorenmu.exception

import cn.luorenmu.command.entity.BotReply


class NotFoundNickNameException(returnMsg: BotReply) :
    MessageReplyException(returnMsg) {
}
