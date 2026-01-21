package cn.luorenmu.command.entity

/**
 * Adapter-neutral reply type.
 *
 * Text and local file image are enough for current commands.
 */
sealed interface BotReply {
    data class Text(val text: String) : BotReply

    data class ImageFile(val path: String) : BotReply

    /**
     * Multiple replies that should be delivered in order.
     */
    data class Multi(val replies: List<BotReply>) : BotReply {
        init {
            require(replies.isNotEmpty()) { "replies is empty" }
        }
    }
}
