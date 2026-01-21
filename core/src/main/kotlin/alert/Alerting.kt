package cn.luorenmu.alert

import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.config.AppConfig
import kotlinx.coroutines.currentCoroutineContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Central alert helpers.
 *
 * Formatting is done here so adapter modules only need to implement message delivery.
 */
object Alerting {
    private val reportedAtBySignature = ConcurrentHashMap<String, Long>()
    private val timeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    suspend fun reportException(
        sender: MessageSender,
        commandName: String?,
        error: Throwable,
    ) {
        if (!AppConfig.alert.enable) return
        val messenger = currentCoroutineContext()[SuperAdminMessengerContext]?.messenger ?: return

        val signature = buildSignature(error)
        val now = System.currentTimeMillis()
        val last = reportedAtBySignature[signature] ?: 0L
        if (now - last < AppConfig.alert.cooldownSeconds * 1000L) return
        reportedAtBySignature[signature] = now

        val ts = timeFormatter.format(Instant.ofEpochMilli(now))
        val header =
            buildString {
                appendLine("[ERBot 异常上报]")
                appendLine("时间：$ts")
                appendLine("群：${sender.groupOpenId}")
                appendLine("用户：${sender.senderName}(${sender.senderOpenId})")
                if (!commandName.isNullOrBlank()) appendLine("指令：$commandName")
                appendLine("消息：${truncate(redact(sender.plainText), 200)}")
                appendLine("异常：${error::class.java.name}: ${truncate(redact(error.message ?: ""), 200)}")
            }

        val stack = formatStack(error, AppConfig.alert.maxStackTraceLines)
        val payload = if (stack.isBlank()) header else header + "\n" + stack
        messenger.sendToSuperAdmins(truncate(payload, AppConfig.alert.maxMessageChars))
    }

    suspend fun forwardUserMessage(
        sender: MessageSender,
        title: String,
        content: String,
    ): Boolean {
        if (!AppConfig.alert.enable) return false
        val messenger = currentCoroutineContext()[SuperAdminMessengerContext]?.messenger ?: return false

        val ts = timeFormatter.format(Instant.ofEpochMilli(System.currentTimeMillis()))
        val msg =
            """
            [ERBot 转发] $title
            时间：$ts
            群：${sender.groupOpenId}
            用户：${sender.senderName}(${sender.senderOpenId})
            内容：${truncate(redact(content), 600)}
            """.trimIndent()
        messenger.sendToSuperAdmins(truncate(msg, AppConfig.alert.maxMessageChars))
        return true
    }

    fun buildGlobalAliasRequestText(
        namespaceCn: String,
        actionCn: String,
        alias: String,
        value: String?,
        sender: MessageSender,
    ): String {
        val ts = timeFormatter.format(Instant.ofEpochMilli(System.currentTimeMillis()))
        val suggested =
            when (actionCn) {
                "设置" -> "${namespaceCn}别名 全局设置 $alias ${value ?: ""}".trim()
                "删除" -> "${namespaceCn}别名 全局删除 $alias"
                else -> ""
            }
        return """
            [ERBot 别名申请]（全局）
            时间：$ts
            群：${sender.groupOpenId}
            用户：${sender.senderName}(${sender.senderOpenId})
            申请：${namespaceCn}别名 $actionCn $alias${if (value != null) " -> $value" else ""}
            建议执行：$suggested
        """.trimIndent()
    }

    private fun buildSignature(t: Throwable): String {
        val top = t.stackTrace.firstOrNull()?.toString().orEmpty()
        return "${t::class.java.name}::$top"
    }

    private fun formatStack(t: Throwable, maxLines: Int): String {
        val elements = t.stackTrace.take(maxLines)
        if (elements.isEmpty()) return ""
        return buildString {
            appendLine("StackTrace（前 ${elements.size} 行）：")
            for (e in elements) appendLine("  at ${redact(e.toString())}")
        }.trimEnd()
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "...(truncated)"

    private fun redact(input: String): String {
        var s = input
        // OneBot access token in URL query
        s = s.replace(Regex("access_token=[^\\s&]+"), "access_token=<redacted>")
        // Authorization header/token dumps
        s = s.replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]+"), "Bearer <redacted>")
        return s
    }
}
