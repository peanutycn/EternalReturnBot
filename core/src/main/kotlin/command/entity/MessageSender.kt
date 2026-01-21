package cn.luorenmu.command.entity

/**
 *
 * @author LoMu
 * Date 2025/10/24 13:46
 */
data class MessageSender(
    var groupOpenId: String,
    var senderName: String,
    var senderOpenId: String,
    var message: String,
    var plainText: String,
    var senderRole: String? = null,
){
    init {
        plainText = plainText.trim()
    }
}
