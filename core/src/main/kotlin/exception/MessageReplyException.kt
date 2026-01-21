package cn.luorenmu.exception

import cn.luorenmu.command.entity.BotReply

open class MessageReplyException(val returnMsg: BotReply, error: String = returnMsg.toString()) :
    RuntimeException(error)
