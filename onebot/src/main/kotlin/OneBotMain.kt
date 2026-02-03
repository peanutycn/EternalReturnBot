package cn.luorenmu.onebot

import cn.luorenmu.Adapter
import cn.luorenmu.alert.SuperAdminMessenger
import cn.luorenmu.alert.SuperAdminMessengerContext
import cn.luorenmu.command.CommandRouter
import cn.luorenmu.command.HelpCommand
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.util.HttpProxyUtil
import cn.luorenmu.config.AppConfig
import cn.luorenmu.currentAdapter
import cn.luorenmu.moduleCore
import cn.luorenmu.common.util.PathUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import love.forte.simbot.application.Application
import love.forte.simbot.application.listeners
import love.forte.simbot.component.onebot.v11.core.bot.OneBotBotConfiguration
import love.forte.simbot.component.onebot.v11.core.bot.firstOneBotBotManager
import love.forte.simbot.component.onebot.v11.core.useOneBot11
import love.forte.simbot.core.application.launchSimpleApplication
import love.forte.simbot.event.ChatGroupMessageEvent
import love.forte.simbot.event.process
import love.forte.simbot.message.OfflineImage
import java.net.ConnectException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 *
 * @author LoMu
 * Date 2025/11/25 21:19
 *
 *
 */

private val log = KotlinLogging.logger { }

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val ackHttpClient = HttpClient(CIO) {
    engine {
        HttpProxyUtil.applyTo(this)
    }
    install(HttpTimeout) {
        // Keep it a bit higher: some OneBot servers respond slowly under load.
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 10_000
    }
}

private fun appendAccessToken(baseUrl: String, token: String?): String {
    val t = token?.trim()?.takeIf { it.isNotBlank() } ?: return baseUrl
    val separator = if (baseUrl.contains("?")) "&" else "?"
    return "${baseUrl}${separator}access_token=${URLEncoder.encode(t, "UTF-8")}"
}

private fun sanitizeOneBotUrl(raw: String): String =
    // Some configs accidentally contain spaces like "ws:// 127.0.0.1:3001".
    // URLs should not contain whitespace, so we strip it defensively.
    raw.trim().replace(Regex("\\s+"), "")

/**
 * Best-effort group id cache for simbot mode.
 *
 * Some OneBot implementations don't support (or don't respond to) get_group_list on forward-ws/HTTP.
 * We record group ids we've seen since startup as a fallback for superAdmin broadcast.
 */
private object SimbotSeenGroupStore {
    private val groupIds = ConcurrentHashMap.newKeySet<Long>()

    fun record(groupIdRaw: String) {
        val gid = extractDigitsFirst(groupIdRaw)?.toLongOrNull() ?: return
        if (gid > 0) groupIds.add(gid)
    }

    fun snapshot(): List<Long> = groupIds.toList().sorted()
}

/**
 * In simbot mode we forward "反馈/别名申请" to superAdmins via HTTP private messages.
 * This store enables superAdmins to reply back to the original group/user by DM'ing the bot.
 *
 * Note: in-memory only (WIP). It will be cleared on bot restart.
 */
private object SimbotReceiptStore {
    data class Target(
        val type: String, // "group" | "private"
        val groupId: Long?,
        val userId: Long,
        val title: String,
        val createdAtMs: Long,
    )

    private val receiptById = ConcurrentHashMap<String, Target>()
    private const val TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L

    private fun cleanup() {
        val now = System.currentTimeMillis()
        receiptById.entries.removeIf { now - it.value.createdAtMs > TTL_MS }
    }

    private fun extractTargetFromForwardText(text: String): Target? {
        // Format from Alerting.forwardUserMessage:
        // [ERBot 转发] <title>
        // 时间：...
        // 群：...
        // 用户：name(id)
        val lines = text.lines().map { it.trim() }
        val titleLine = lines.firstOrNull().orEmpty()
        val title =
            Regex("^\\[ERBot 转发\\]\\s*(.*)$")
                .find(titleLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "转发"

        val groupLine = lines.firstOrNull { it.startsWith("群：") }.orEmpty()
        val userLine = lines.firstOrNull { it.startsWith("用户：") }.orEmpty()

        val groupDigits = Regex("\\d+").find(groupLine)?.value
        val userDigits = Regex("\\d+").findAll(userLine).toList().lastOrNull()?.value
        val uid = userDigits?.toLongOrNull() ?: return null

        val gid = groupDigits?.toLongOrNull()
        val type = if (gid != null && gid > 0L) "group" else "private"
        return Target(type = type, groupId = gid, userId = uid, title = title, createdAtMs = System.currentTimeMillis())
    }

    fun attachReceiptIfForwarded(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("[ERBot 转发]")) return raw
        cleanup()
        val target = extractTargetFromForwardText(text) ?: return raw

        var id: String
        do {
            id = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        } while (receiptById.containsKey(id))
        receiptById[id] = target

        return raw +
            """

            回执ID：$id
            管理员回复：回执回复 $id <内容>
            """.trimIndent()
    }

    fun get(id: String): Target? {
        cleanup()
        return receiptById[id.uppercase()]
    }

    fun remove(id: String): Target? {
        cleanup()
        return receiptById.remove(id.uppercase())
    }
}

suspend fun main() {
    // Ensure adapter is initialized before command discovery.
    currentAdapter = Adapter.ONE_BOT

    // Start embedded HTTP server for static assets used by templates.
    embeddedServer(Netty, port = AppConfig.server.port, host = AppConfig.server.host) {
        moduleCore(Adapter.ONE_BOT)
    }.start(wait = false)

    val commandRouter = CommandRouter()

    val resolvedMode = resolveMode(AppConfig.oneBot.mode, AppConfig.oneBot.apiServerHost)
    when (resolvedMode) {
        OneBotMode.WS_ONLY -> {
            val wsUrl = appendAccessToken(sanitizeOneBotUrl(AppConfig.oneBot.eventServerHost), AppConfig.oneBot.accessToken)
            OneBotForwardWsBot(wsUrl, commandRouter).runForever()
        }

        OneBotMode.SIMBOT -> {
            val wsUrl = appendAccessToken(sanitizeOneBotUrl(AppConfig.oneBot.eventServerHost), AppConfig.oneBot.accessToken)
            coroutineScope {
                // In simbot mode, private/request events aren't always exposed as simbot events.
                // We add a lightweight forward-WS control loop for superAdmin DM controls and invite forwarding.
                launch { OneBotForwardWsControlBot(wsUrl).runForever() }
                startSimbotOneBot(commandRouter)
                awaitCancellation()
            }
        }
    }
}

private enum class OneBotMode {
    WS_ONLY,
    SIMBOT,
}

private fun resolveMode(modeRaw: String, apiServerHost: String): OneBotMode {
    val mode = modeRaw.trim().lowercase()
    return when (mode) {
        "ws", "ws-only", "wsonly", "forward-ws", "forwardws" -> OneBotMode.WS_ONLY
        "simbot", "http", "http+ws" -> OneBotMode.SIMBOT
        "auto", "" -> {
            val api = apiServerHost.trim()
            if (api.startsWith("http://") || api.startsWith("https://")) OneBotMode.SIMBOT else OneBotMode.WS_ONLY
        }

        else -> OneBotMode.WS_ONLY
    }
}

private fun extractDigitsFirst(input: String): String? = Regex("\\d+").find(input)?.value

private fun normalizeUserIdRaw(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    return Regex("\\d+").findAll(trimmed).toList().lastOrNull()?.value.orEmpty()
}

private fun normalizeGroupIdRaw(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    return Regex("\\d+").find(trimmed)?.value.orEmpty()
}

private fun isSuperAdminRaw(userIdRaw: String): Boolean {
    val uid = normalizeUserIdRaw(userIdRaw)
    if (uid.isBlank()) return false
    return AppConfig.alias.superAdmins.any { normalizeUserIdRaw(it) == uid }
}

private fun tryInvokeNoArg(target: Any, methodName: String): Any? =
    target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.let { m ->
        runCatching { m.invoke(target) }.getOrNull()
    }

private fun resolveSimbotMessageId(event: ChatGroupMessageEvent): Long? {
    val candidates = listOf(
        tryInvokeNoArg(event, "getMessageId"),
        tryInvokeNoArg(event, "messageId"),
        tryInvokeNoArg(event, "getMessage"),
        tryInvokeNoArg(event, "message"),
        tryInvokeNoArg(event, "getSource"),
        tryInvokeNoArg(event, "source"),
        tryInvokeNoArg(event, "getId"),
        tryInvokeNoArg(event, "id"),
    ).filterNotNull()

    for (c in candidates) {
        val digits = extractDigitsFirst(c.toString())
        if (!digits.isNullOrBlank()) return digits.toLongOrNull()
        val nested = tryInvokeNoArg(c, "getId") ?: tryInvokeNoArg(c, "id")
        if (nested != null) {
            val nestedDigits = extractDigitsFirst(nested.toString())
            if (!nestedDigits.isNullOrBlank()) return nestedDigits.toLongOrNull()
        }
    }
    return null
}

private fun resolveSimbotGroupId(event: ChatGroupMessageEvent): String {
    val candidates = listOf(
        tryInvokeNoArg(event, "getGroupId"),
        tryInvokeNoArg(event, "groupId"),
        tryInvokeNoArg(event, "getChatGroup"),
        tryInvokeNoArg(event, "chatGroup"),
        tryInvokeNoArg(event, "getGroup"),
        tryInvokeNoArg(event, "group"),
        tryInvokeNoArg(event, "getSource"),
        tryInvokeNoArg(event, "source"),
    ).filterNotNull()

    for (c in candidates) {
        val text = c.toString()
        val digits = extractDigitsFirst(text)
        if (!digits.isNullOrBlank()) return digits

        val nested = tryInvokeNoArg(c, "getId") ?: tryInvokeNoArg(c, "id")
        if (nested != null) {
            val nestedDigits = extractDigitsFirst(nested.toString())
            if (!nestedDigits.isNullOrBlank()) return nestedDigits
        }
    }

    val fallback = event.id.toString()
    return extractDigitsFirst(fallback) ?: fallback
}

private fun toJsonNumberOrString(value: String): JsonPrimitive {
    val n = value.trim().toLongOrNull()
    return if (n != null) JsonPrimitive(n) else JsonPrimitive(value)
}

private suspend fun sendAckEmojiLikeHttp(messageId: Long) {
    if (!AppConfig.oneBot.ackEmojiEnabled) return
    val base = sanitizeOneBotUrl(AppConfig.oneBot.apiServerHost).trimEnd('/')
    if (!(base.startsWith("http://") || base.startsWith("https://"))) return

    val token = AppConfig.oneBot.accessToken?.trim()?.takeIf { it.isNotBlank() }
    val url = appendAccessToken("$base/set_msg_emoji_like", token)
    val emojiId = AppConfig.oneBot.ackEmojiId

    try {
        ackHttpClient.post(url) {
            contentType(ContentType.Application.Json)
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                buildJsonObject {
                    put("message_id", JsonPrimitive(messageId))
                    put("emoji_id", toJsonNumberOrString(emojiId))
                }.toString()
            )
        }
    } catch (e: Exception) {
        log.debug(e) { "Ack emoji failed via HTTP: messageId=$messageId" }
    }
}

private suspend fun sendPrivateMsgHttp(userId: String, text: String) {
    val base = sanitizeOneBotUrl(AppConfig.oneBot.apiServerHost).trimEnd('/')
    if (!(base.startsWith("http://") || base.startsWith("https://"))) return

    val token = AppConfig.oneBot.accessToken?.trim()?.takeIf { it.isNotBlank() }
    val url = appendAccessToken("$base/send_private_msg", token)
    try {
        ackHttpClient.post(url) {
            contentType(ContentType.Application.Json)
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                buildJsonObject {
                    put("user_id", toJsonNumberOrString(userId))
                    put("message", JsonPrimitive(text))
                }.toString()
            )
        }
    } catch (e: Exception) {
        log.debug(e) { "Send private msg failed via HTTP: userId=$userId" }
    }
}

private suspend fun sendGroupMsgHttp(groupId: String, message: String) {
    val base = sanitizeOneBotUrl(AppConfig.oneBot.apiServerHost).trimEnd('/')
    if (!(base.startsWith("http://") || base.startsWith("https://"))) return

    val token = AppConfig.oneBot.accessToken?.trim()?.takeIf { it.isNotBlank() }
    val url = appendAccessToken("$base/send_group_msg", token)
    try {
        ackHttpClient.post(url) {
            contentType(ContentType.Application.Json)
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                buildJsonObject {
                    put("group_id", toJsonNumberOrString(groupId))
                    put("message", JsonPrimitive(message))
                }.toString()
            )
        }
    } catch (e: Exception) {
        log.debug(e) { "Send group msg failed via HTTP: groupId=$groupId" }
    }
}

private fun buildHelpText(): String = HelpCommand.buildHelpText()

private fun buildHelpReplyForJoin(): BotReply {
    val text = buildHelpText()
    if (!AppConfig.help.onJoinImageEnabled) return BotReply.Text(text)
    val imagePath = HelpCommand.renderHelpImage()
    return if (imagePath == null) {
        BotReply.Text(text)
    } else {
        BotReply.Multi(listOf(BotReply.Text(text), BotReply.ImageFile(imagePath)))
    }
}

private fun buildHelpMessageForHttpJoin(): String? {
    val text = buildHelpText()
    if (text.isBlank()) return null
    if (!AppConfig.help.onJoinImageEnabled) return text
    val imagePath = HelpCommand.renderHelpImage() ?: return text
    val fileParam = toOneBotFileParamHttp(imagePath)
    return text + "\n" + buildImageCq(fileParam)
}

private suspend fun sendHelpToGroupHttp(groupId: Long) {
    if (!AppConfig.help.onJoinEnabled) return
    val message = buildHelpMessageForHttpJoin() ?: return
    sendGroupMsgHttp(groupId.toString(), message)
}

private suspend fun getGroupListHttp(): List<Long> {
    val base = sanitizeOneBotUrl(AppConfig.oneBot.apiServerHost).trimEnd('/')
    if (!(base.startsWith("http://") || base.startsWith("https://"))) return emptyList()

    val token = AppConfig.oneBot.accessToken?.trim()?.takeIf { it.isNotBlank() }
    val url = appendAccessToken("$base/get_group_list", token)
    suspend fun parse(resp: String): List<Long> {
        val root = runCatching { json.parseToJsonElement(resp) }.getOrNull() as? JsonObject ?: return emptyList()
        val dataEl = root["data"]
        val arr = when (dataEl) {
            is JsonArray -> dataEl
            is JsonObject -> {
                (dataEl["groups"] as? JsonArray)
                    ?: (dataEl["group_list"] as? JsonArray)
                    ?: (dataEl["list"] as? JsonArray)
            }
            else -> null
        } ?: return emptyList()

        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            obj.long("group_id")
        }.distinct()
    }

    // Prefer GET first: some implementations (e.g. certain napcat builds) may only return data for GET.
    runCatching {
        val resp = ackHttpClient.get(url) {}.bodyAsText()
        val parsed = parse(resp)
        if (parsed.isNotEmpty()) return parsed
    }.onFailure { e ->
        log.debug(e) { "Get group list failed via HTTP GET." }
    }

    // Fallback to POST if GET returned empty.
    return runCatching {
        val resp = ackHttpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { }.toString())
        }.bodyAsText()
        parse(resp)
    }.onFailure { e ->
        log.debug(e) { "Get group list failed via HTTP POST." }
    }.getOrElse { emptyList() }
}

private fun buildImageCq(fileParam: String): String = "[CQ:image,file=$fileParam]"

private fun buildOneBotMessageForHttp(reply: BotReply): String {
    val builder = StringBuilder()
    fun append(r: BotReply) {
        when (r) {
            is BotReply.Text -> builder.append(r.text)
            is BotReply.ImageFile -> builder.append(buildImageCq(toOneBotFileParamHttp(r.path)))
            is BotReply.Multi -> r.replies.forEach { append(it) }
        }
    }
    append(reply)
    return builder.toString()
}

private fun toOneBotFileParamHttp(path: String): String {
    val trimmed = path.trim()
    if (trimmed.startsWith("file://")) return trimmed
    return if (trimmed.startsWith("/")) "file://$trimmed" else trimmed
}

private fun buildSuperAdminMessengerHttp(): SuperAdminMessenger {
    val superAdmins = AppConfig.alias.superAdmins
    return SuperAdminMessenger { text ->
        if (superAdmins.isEmpty()) return@SuperAdminMessenger
        val payload = SimbotReceiptStore.attachReceiptIfForwarded(text)
        for (uid in superAdmins) {
            val normalized = normalizeUserIdRaw(uid)
            if (normalized.isBlank()) continue
            sendPrivateMsgHttp(normalized, payload)
        }
    }
}

private suspend fun startSimbotOneBot(commandRouter: CommandRouter) {
    val api = sanitizeOneBotUrl(AppConfig.oneBot.apiServerHost)
    if (!(api.startsWith("http://") || api.startsWith("https://"))) {
        error("OneBot simbot 模式需要配置 lomu.onebot.apiServerHost=http(s)://...，当前=$api")
    }
    val ws = sanitizeOneBotUrl(AppConfig.oneBot.eventServerHost)
    if (!(ws.startsWith("ws://") || ws.startsWith("wss://"))) {
        error("OneBot simbot 模式需要配置 lomu.onebot.eventServerHost=ws(s)://...，当前=$ws")
    }

    val app = launchSimpleApplication {
        useOneBot11()
    }

    val botManager = app.botManagers.firstOneBotBotManager()
    val bot = botManager.register(
        OneBotBotConfiguration().apply {
            botUniqueId = AppConfig.oneBot.botUniqueId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
            apiServerHost = Url(appendAccessToken(api, AppConfig.oneBot.accessToken))
            eventServerHost = Url(appendAccessToken(ws, AppConfig.oneBot.accessToken))
        }
    )

    app.listeners {
        process<ChatGroupMessageEvent> { event ->
            // Resolve to a stable numeric group id if possible.
            // Some adapters use composite ids, so we use reflection + digit extraction.
            val groupId = resolveSimbotGroupId(event)
            SimbotSeenGroupStore.record(groupId)
            val messageId = resolveSimbotMessageId(event)

            val sender = MessageSender(
                groupOpenId = groupId,
                senderName = event.author().name,
                senderOpenId = event.authorId.toString(),
                message = event.messageContent.messages.toString(),
                plainText = event.messageContent.plainText?.trim() ?: "",
            )

            // Group shortcut: guide users to DM the bot (superAdmin only).
            run {
                val text = sender.plainText.trim()
                if (text == "群发帮助" || text == "群发 帮助" || text == "回执帮助" || text == "回执 帮助") {
                    event.reply(
                        """
                        这些是“超级管理员私聊 bot”指令：
                        - 群发帮助 / 群发 ...
                        - 回执帮助 / 回执回复 ...
                        """.trimIndent()
                    )
                    return@process
                }
            }

            if (messageId != null && AppConfig.oneBot.ackEmojiEnabled && commandRouter.commandFind(sender.plainText) != null) {
                // Best-effort ack as soon as a command is recognized.
                sendAckEmojiLikeHttp(messageId)
            }

            val reply = withContext(SuperAdminMessengerContext(buildSuperAdminMessengerHttp())) {
                commandRouter.call(sender)
            }
            suspend fun replyOne(r: BotReply) {
                when (r) {
                    is BotReply.Text -> event.reply(r.text)
                    is BotReply.ImageFile -> event.reply(OfflineImage.fileOfflineImage(r.path))
                    is BotReply.Multi -> {
                        val merged = buildOneBotMessageForHttp(r).trim()
                        if (merged.isNotBlank()) {
                            sendGroupMsgHttp(sender.groupOpenId, merged)
                        } else {
                            r.replies.forEach { replyOne(it) }
                        }
                    }
                }
            }
            if (reply != null) replyOne(reply)
        }
    }

    try {
        bot.start()
    } catch (e: ConnectException) {
        log.error(e) { "无法连接 OneBot HTTP API，请先启动 OneBot HTTP 服务或切换到 ws-only 模式" }
        throw e
    }
}

private class OneBotForwardWsControlBot(
    private val wsUrl: String,
) {
    private val echo = AtomicLong(1)
    private val pendingActionResponses = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val pendingBroadcastByUser = ConcurrentHashMap<Long, PendingBroadcast>()
    private val broadcastBlacklistLock = Any()
    private val broadcastBlacklistPath = PathUtils.dataPathResolve("broadcast", "group_blacklist.json")

    private val client = HttpClient(CIO) {
        engine {
            HttpProxyUtil.applyTo(this)
        }
        install(WebSockets)
    }

    private data class PendingBroadcast(
        val token: String,
        val content: String,
        val groupIds: List<Long>,
        val createdAtMs: Long,
    )

    private val lastRequestAtMs = AtomicLong(0)
    @Volatile
    private var lastRequestSummary: String? = null

    suspend fun runForever() {
        if (wsUrl.isBlank()) return
        while (true) {
            try {
                log.info { "Connecting OneBot control forward WS: $wsUrl" }
                client.webSocket(urlString = wsUrl) {
                    log.info { "OneBot control WS connected: $wsUrl" }
                    while (isActive) {
                        when (val frame = incoming.receive()) {
                            is Frame.Text -> handleEvent(frame.readText(), this)
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "OneBot control WS disconnected, retrying in 2s..." }
                delay(2_000)
            }
        }
    }

    private suspend fun handleEvent(text: String, session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return

        // Action responses: used by get_group_list during broadcast.
        run {
            val echoValue = root.long("echo")
            val hasStatus = root["status"] != null || root["retcode"] != null
            val hasPostType = root["post_type"] != null
            if (echoValue != null && hasStatus && !hasPostType) {
                pendingActionResponses.remove(echoValue)?.complete(root)
                return
            }
        }

        val postType = root.string("post_type") ?: return
        if (postType == "request") {
            // Record last request for diagnostics (superAdmin command: 邀请诊断).
            runCatching {
                val reqType = root.string("request_type").orEmpty()
                val subType = root.string("sub_type").orEmpty()
                val gid = root.long("group_id")
                val uid = root.long("user_id")
                lastRequestAtMs.set(System.currentTimeMillis())
                lastRequestSummary = "request_type=$reqType sub_type=$subType group_id=$gid user_id=$uid"
            }
            handleRequest(root)
            return
        }
        if (postType == "notice") {
            handleNotice(root)
            return
        }
        if (postType != "message") return

        val messageType = root.string("message_type") ?: return
        if (messageType != "private") return

        val userId = root.long("user_id") ?: return
        val plainText = extractPlainText(root["message"])?.trim().orEmpty()
        if (plainText.isBlank()) return

        tryHandleSuperAdminPrivateCommand(session, userId, plainText)
    }

    private suspend fun handleNotice(root: JsonObject) {
        if (!AppConfig.help.onJoinEnabled) return
        val noticeType = root.string("notice_type") ?: return
        if (noticeType != "group_increase") return
        val selfId = root.long("self_id") ?: return
        val userId = root.long("user_id") ?: return
        if (selfId != userId) return

        val groupId = root.long("group_id") ?: return
        runCatching {
            sendHelpToGroupHttp(groupId)
        }.onFailure { e ->
            log.warn(e) { "Failed to send help on group join via HTTP: groupId=$groupId" }
        }
    }

    private suspend fun handleRequest(root: JsonObject) {
        if (!AppConfig.alert.enable) {
            log.debug { "Invite forward skipped: lomu.alert.enable=false" }
            return
        }
        if (!AppConfig.alert.forwardGroupInviteEnabled) {
            log.debug { "Invite forward skipped: lomu.alert.forwardGroupInviteEnabled=false" }
            return
        }

        val requestType = root.string("request_type") ?: return
        if (requestType != "group") return

        val subType = root.string("sub_type") ?: "unknown"
        if (subType != "invite" && subType != "add") return

        val groupId = root.long("group_id") ?: return
        val userId = root.long("user_id") ?: return
        val comment = root.string("comment").orEmpty()
        val flag = root.string("flag").orEmpty()

        val msg =
            """
            [ERBot 入群请求转发]
            类型：$subType
            群：$groupId
            发起人：$userId
            备注：${comment.take(300)}
            flag：${flag.take(200)}
            """.trimIndent()

        runCatching {
            buildSuperAdminMessengerHttp().sendToSuperAdmins(msg)
        }.onSuccess {
            log.info { "Invite request forwarded to superAdmins: subType=$subType groupId=$groupId userId=$userId" }
        }.onFailure { e ->
            log.warn(e) { "Invite request forward failed: subType=$subType groupId=$groupId userId=$userId" }
        }
    }

    private fun isSuperAdmin(userId: Long): Boolean = isSuperAdminRaw(userId.toString())

    private suspend fun sendAction(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        action: String,
        params: JsonObject,
    ) {
        val payload = buildJsonObject {
            put("action", JsonPrimitive(action))
            put("params", params)
            put("echo", JsonPrimitive(echo.getAndIncrement()))
        }
        session.send(Frame.Text(payload.toString()))
    }

    private suspend fun sendPrivateTextWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        userId: Long,
        text: String,
    ) {
        sendAction(
            session,
            action = "send_private_msg",
            params = buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("message", JsonPrimitive(text))
            },
        )
    }

    private suspend fun sendGroupTextWithAtHttp(
        groupId: Long,
        userId: Long,
        text: String,
    ) {
        // Use CQ at for compatibility with common OneBot implementations.
        // Avoid relying on forward-ws "action" responses for sending.
        val msg = "[CQ:at,qq=$userId] 管理员回复：${text.take(1200)}"
        sendGroupMsgHttp(groupId.toString(), msg)
    }

    private suspend fun callActionAwait(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        action: String,
        params: JsonObject,
        timeoutMs: Long = 6_000,
    ): JsonObject? {
        val id = echo.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingActionResponses[id] = deferred
        val payload = buildJsonObject {
            put("action", JsonPrimitive(action))
            put("params", params)
            put("echo", JsonPrimitive(id))
        }
        session.send(Frame.Text(payload.toString()))
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingActionResponses.remove(id)
        return resp
    }

    private suspend fun getGroupIdListWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ): List<Long> {
        val resp = callActionAwait(session, action = "get_group_list", params = buildJsonObject { })
            ?: return emptyList()
        val data = resp["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            obj.long("group_id")
        }.distinct()
    }

    private suspend fun getGroupIdListForBroadcast(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ): List<Long> {
        // Some OneBot implementations expose forward-ws as "events only": actions like get_group_list may not respond.
        // In simbot mode we always have HTTP API, so we can fall back to HTTP safely.
        val fromWs = runCatching { getGroupIdListWs(session) }.getOrNull().orEmpty()
        if (fromWs.isNotEmpty()) return fromWs

        val fromHttp = runCatching { getGroupListHttp() }.getOrNull().orEmpty()
        if (fromHttp.isNotEmpty()) {
            log.debug { "Broadcast group list fallback to HTTP: size=${fromHttp.size}" }
            return fromHttp
        }

        val fromSeen = SimbotSeenGroupStore.snapshot()
        if (fromSeen.isNotEmpty()) {
            log.debug { "Broadcast group list fallback to seen-groups: size=${fromSeen.size}" }
        } else {
            log.debug { "Broadcast group list is empty from WS, HTTP and seen-groups." }
        }
        return fromSeen
    }

    private fun readBroadcastBlacklistFile(): MutableSet<Long> {
        synchronized(broadcastBlacklistLock) {
            if (!broadcastBlacklistPath.toFile().exists()) return mutableSetOf()
            val raw = runCatching { broadcastBlacklistPath.toFile().readText() }.getOrNull()?.trim().orEmpty()
            if (raw.isBlank()) return mutableSetOf()
            val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return mutableSetOf()
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> (el["groupIds"] as? JsonArray) ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return arr.mapNotNull { it.asString()?.let(::normalizeGroupIdRaw)?.toLongOrNull() }.toMutableSet()
        }
    }

    private fun writeBroadcastBlacklistFile(groupIds: Set<Long>) {
        synchronized(broadcastBlacklistLock) {
            val parent = broadcastBlacklistPath.parent
            parent?.toFile()?.mkdirs()
            val obj = buildJsonObject {
                put("groupIds", JsonArray(groupIds.sorted().map { JsonPrimitive(it) }))
                put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
            }
            val tmp = broadcastBlacklistPath.resolveSibling("${broadcastBlacklistPath.fileName}.tmp")
            tmp.toFile().writeText(obj.toString())
            try {
                Files.move(tmp, broadcastBlacklistPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, broadcastBlacklistPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun combinedBroadcastBlacklist(): Set<Long> {
        val fromConfig = AppConfig.broadcast.groupBlacklist
            .mapNotNull { normalizeGroupIdRaw(it).toLongOrNull() }
            .toSet()
        val fromFile = readBroadcastBlacklistFile()
        return fromConfig + fromFile
    }

    private fun buildReceiptHelp(): String =
        """
        [ERBot 回执回复（仅超级管理员）]
        说明：当你收到 “[ERBot 转发] …” 的转发消息时，会附带 “回执ID”。

        用法：
        - 回执回复 <回执ID> <内容>   （在原群里 @用户 并回复）
        - 回执查看 <回执ID>
        """.trimIndent()

    private fun buildBroadcastHelp(): String =
        """
        [ERBot 群发（仅超级管理员）]
        说明：需要开启 lomu.broadcast.enable=true；并且建议“私聊 bot”执行。

        1) 发送预览：
           群发 <内容>
           例：群发 服务器将于今晚 23:00 重启

        2) 确认发送：
           群发确认 <token>

        3) 取消：
           群发取消

        4) 黑名单（不会接收群发）：
           群发黑名单 列表
           群发黑名单 添加 <群号>
           群发黑名单 删除 <群号>
        """.trimIndent()

    private suspend fun tryHandleReceiptPrivateCommand(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        superAdminUserId: Long,
        plainText: String,
    ): Boolean {
        val text = plainText.trim()
        if (text.isBlank()) return false

        if (text == "回执帮助" || text == "回执 帮助") {
            sendPrivateTextWs(session, superAdminUserId, buildReceiptHelp())
            return true
        }

        if (text.startsWith("回执查看")) {
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val id = parts.getOrNull(1).orEmpty()
            if (id.isBlank()) {
                sendPrivateTextWs(session, superAdminUserId, "用法：回执查看 <回执ID>")
                return true
            }
            val r = SimbotReceiptStore.get(id)
            if (r == null) {
                sendPrivateTextWs(session, superAdminUserId, "回执不存在或已过期：${id.uppercase()}")
                return true
            }
            val ageSec = ((System.currentTimeMillis() - r.createdAtMs) / 1000L).coerceAtLeast(0)
            val target = if (r.type == "group") "群=${r.groupId}" else "私聊"
            sendPrivateTextWs(
                session,
                superAdminUserId,
                "回执 ${id.uppercase()}：title=${r.title} target=$target user=${r.userId} age=${ageSec}s"
            )
            return true
        }

        if (text.startsWith("回执回复")) {
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val id = parts.getOrNull(1).orEmpty()
            val content = parts.drop(2).joinToString(" ").trim()
            if (id.isBlank() || content.isBlank()) {
                sendPrivateTextWs(session, superAdminUserId, "用法：回执回复 <回执ID> <内容>")
                return true
            }
            val r = SimbotReceiptStore.remove(id)
            if (r == null) {
                sendPrivateTextWs(session, superAdminUserId, "回执不存在或已过期：${id.uppercase()}")
                return true
            }
            if (r.type == "group") {
                val gid = r.groupId ?: 0L
                if (gid <= 0L) {
                    sendPrivateTextWs(session, superAdminUserId, "回执异常：缺少 groupId，已丢弃。")
                    return true
                }
                val ok = runCatching { sendGroupTextWithAtHttp(gid, r.userId, content) }.isSuccess
                if (ok) {
                    sendPrivateTextWs(session, superAdminUserId, "已回复到群：$gid（回执 ${id.uppercase()} 已关闭）")
                } else {
                    sendPrivateTextWs(session, superAdminUserId, "回复失败：群=$gid（可能 HTTP API 不可用/权限不足；回执 ${id.uppercase()} 已关闭）")
                }
                return true
            }
            val ok = runCatching { sendPrivateMsgHttp(r.userId.toString(), "管理员回复：${content.take(1200)}") }.isSuccess
            if (ok) {
                sendPrivateTextWs(session, superAdminUserId, "已私聊回复用户：${r.userId}（回执 ${id.uppercase()} 已关闭）")
            } else {
                sendPrivateTextWs(session, superAdminUserId, "私聊回复失败：用户=${r.userId}（可能 HTTP API 不可用/权限不足；回执 ${id.uppercase()} 已关闭）")
            }
            return true
        }

        return false
    }

    private suspend fun tryHandleSuperAdminPrivateCommand(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        userId: Long,
        plainText: String,
    ): Boolean {
        val text = plainText.trim()
        if (text.isBlank()) return false
        if (!isSuperAdmin(userId)) return false

        // Receipt reply is always available for superAdmins (doesn't depend on broadcast switch).
        if (tryHandleReceiptPrivateCommand(session, userId, text)) return true

        // Guard: feature must be enabled explicitly.
        if (!AppConfig.broadcast.enable) {
            if (text == "群发帮助" || text == "群发 帮助") {
                sendPrivateTextWs(session, userId, buildBroadcastHelp())
                return true
            }
            return false
        }

        if (text == "群发帮助" || text == "群发 帮助") {
            sendPrivateTextWs(session, userId, buildBroadcastHelp())
            return true
        }

        if (text == "群发诊断") {
            val wsGroups = runCatching { getGroupIdListWs(session) }.getOrNull().orEmpty()
            val httpGroups = runCatching { getGroupListHttp() }.getOrNull().orEmpty()
            val seenGroups = SimbotSeenGroupStore.snapshot()
            val blacklist = combinedBroadcastBlacklist()
            val msg =
                """
                [群发诊断]
                WS群列表：${wsGroups.size}
                HTTP群列表：${httpGroups.size}
                已见群：${seenGroups.size}
                黑名单：${blacklist.size}
                
                提示：
                - 若 HTTP 群列表=0，但你 curl 能返回，请检查 lomu.onebot.apiServerHost / access_token 是否与 curl 一致（尤其别有空格）。
                - 若三者都为 0，可先在目标群发任意消息让 bot 记录“已见群”，再执行群发。
                """.trimIndent()
            sendPrivateTextWs(session, userId, msg)
            return true
        }

        if (text == "邀请诊断") {
            val lastAt = lastRequestAtMs.get()
            val ageSec = if (lastAt <= 0L) -1 else ((System.currentTimeMillis() - lastAt) / 1000L).coerceAtLeast(0)
            val msg =
                """
                [邀请诊断]
                wsUrl=$wsUrl
                alert.enable=${AppConfig.alert.enable}
                alert.forwardGroupInviteEnabled=${AppConfig.alert.forwardGroupInviteEnabled}
                superAdmins=${AppConfig.alias.superAdmins.map(::normalizeUserIdRaw).filter { it.isNotBlank() }.distinct().size}
                lastRequestAgeSec=$ageSec
                lastRequest=${lastRequestSummary ?: "<无>"}
                
                说明：
                - lastRequest 为 <无>：代表 bot 没收到 OneBot 的 request 事件（napcat 可能未上报/未开启，或 eventServerHost 不是正确的正向 WS）。
                - lastRequest 有值但没转发：检查上面两个开关是否为 true，以及 superAdmins 是否非空。
                """.trimIndent()
            sendPrivateTextWs(session, userId, msg)
            return true
        }

        if (text == "邀请转发测试") {
            runCatching {
                buildSuperAdminMessengerHttp().sendToSuperAdmins("[ERBot 测试] 邀请转发测试：如果你看到这条消息，说明 send_private_msg HTTP 可用。")
            }.onSuccess {
                sendPrivateTextWs(session, userId, "已触发转发测试：请检查 superAdmins 是否收到私聊。")
            }.onFailure { e ->
                sendPrivateTextWs(session, userId, "转发测试失败：${e::class.simpleName}:${e.message}")
            }
            return true
        }

        if (text == "群发取消") {
            val removed = pendingBroadcastByUser.remove(userId)
            sendPrivateTextWs(session, userId, if (removed == null) "当前没有待确认的群发。" else "已取消群发。")
            return true
        }

        if (text.startsWith("群发黑名单")) {
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val op = parts.getOrNull(1) ?: "列表"
            when (op) {
                "列表", "list", "ls" -> {
                    val configSet = AppConfig.broadcast.groupBlacklist.mapNotNull { normalizeGroupIdRaw(it).toLongOrNull() }.toSet()
                    val fileSet = readBroadcastBlacklistFile()
                    val all = (configSet + fileSet).sorted()
                    val msg = buildString {
                        appendLine("[群发黑名单]")
                        appendLine("config：${if (configSet.isEmpty()) "<空>" else configSet.sorted().joinToString(",")}")
                        appendLine("file：${if (fileSet.isEmpty()) "<空>" else fileSet.sorted().joinToString(",")}")
                        append("all：${if (all.isEmpty()) "<空>" else all.joinToString(",")}")
                    }
                    sendPrivateTextWs(session, userId, msg)
                    return true
                }

                "添加", "add", "set" -> {
                    val gid = parts.getOrNull(2)?.let(::normalizeGroupIdRaw)?.toLongOrNull()
                        ?: run {
                            sendPrivateTextWs(session, userId, "用法：群发黑名单 添加 <群号>")
                            return true
                        }
                    val fileSet = readBroadcastBlacklistFile()
                    val changed = fileSet.add(gid)
                    if (changed) writeBroadcastBlacklistFile(fileSet)
                    sendPrivateTextWs(session, userId, if (changed) "已加入黑名单：$gid" else "已存在：$gid")
                    return true
                }

                "删除", "del", "rm" -> {
                    val gid = parts.getOrNull(2)?.let(::normalizeGroupIdRaw)?.toLongOrNull()
                        ?: run {
                            sendPrivateTextWs(session, userId, "用法：群发黑名单 删除 <群号>")
                            return true
                        }
                    val fileSet = readBroadcastBlacklistFile()
                    val changed = fileSet.remove(gid)
                    if (changed) writeBroadcastBlacklistFile(fileSet)
                    sendPrivateTextWs(session, userId, if (changed) "已移出黑名单：$gid" else "未找到：$gid")
                    return true
                }
            }
            sendPrivateTextWs(session, userId, "未知操作：$op（支持：列表/添加/删除）")
            return true
        }

        if (text.startsWith("群发确认")) {
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val token = parts.getOrNull(1).orEmpty()
            if (token.isBlank()) {
                sendPrivateTextWs(session, userId, "用法：群发确认 <token>")
                return true
            }
            val pending = pendingBroadcastByUser[userId]
            if (pending == null) {
                sendPrivateTextWs(session, userId, "当前没有待确认的群发。")
                return true
            }
            val ttlMs = AppConfig.broadcast.confirmTtlSeconds.toLong() * 1000L
            val now = System.currentTimeMillis()
            if (now - pending.createdAtMs > ttlMs) {
                pendingBroadcastByUser.remove(userId)
                sendPrivateTextWs(session, userId, "群发已过期，请重新发送：群发 <内容>")
                return true
            }
            if (pending.token != token) {
                sendPrivateTextWs(session, userId, "token 不匹配。请确认后再试。")
                return true
            }

            pendingBroadcastByUser.remove(userId)
            val total = pending.groupIds.size
            if (total <= 0) {
                sendPrivateTextWs(session, userId, "目标群列表为空，未发送。")
                return true
            }

            val delayMs = AppConfig.broadcast.sendDelayMs.toLong().coerceAtLeast(0)
            sendPrivateTextWs(session, userId, "开始群发：目标群数=$total（每群间隔${delayMs}ms）")

            var sent = 0
            var failed = 0
            val failedGroups = mutableListOf<Long>()
            for (gid in pending.groupIds) {
                // Use HTTP API to send: forward-ws may not support actions reliably.
                val ok = runCatching { sendGroupMsgHttp(gid.toString(), pending.content) }.isSuccess
                if (ok) {
                    sent++
                } else {
                    failed++
                    if (failedGroups.size < 5) failedGroups.add(gid)
                }
                if (delayMs > 0) delay(delayMs)
            }
            val tail = if (failedGroups.isEmpty()) "" else "（失败示例群：${failedGroups.joinToString(",")}）"
            sendPrivateTextWs(session, userId, "群发完成：成功 $sent/$total，失败 $failed/$total。$tail")
            return true
        }

        if (text.startsWith("群发")) {
            val content = text.removePrefix("群发").trim()
            if (content.isBlank()) {
                sendPrivateTextWs(session, userId, buildBroadcastHelp())
                return true
            }

            val blacklist = combinedBroadcastBlacklist()
            val allGroups = getGroupIdListForBroadcast(session)
            if (allGroups.isEmpty()) {
                sendPrivateTextWs(
                    session,
                    userId,
                    "获取群列表失败：返回为空（可能不支持 get_group_list，或 forward-ws 不返回 action 响应；请确认 napcat 已开启 HTTP API 并配置 lomu.onebot.apiServerHost。若仍为空，可先在目标群里发任意消息让 bot 记录“已见群”，再执行群发）。"
                )
                return true
            }
            val targets = allGroups.filterNot { blacklist.contains(it) }

            val token = UUID.randomUUID().toString().replace("-", "").take(6)
            pendingBroadcastByUser[userId] = PendingBroadcast(
                token = token,
                content = content,
                groupIds = targets,
                createdAtMs = System.currentTimeMillis(),
            )
            sendPrivateTextWs(
                session,
                userId,
                """
                [群发预览]
                目标群：${targets.size}/${allGroups.size}（黑名单跳过：${allGroups.size - targets.size}）
                内容：${content.take(600)}

                确认发送：群发确认 $token（${AppConfig.broadcast.confirmTtlSeconds}s 内有效）
                取消：群发取消
                """.trimIndent()
            )
            return true
        }

        return false
    }

    private fun extractPlainText(message: JsonElement?): String? {
        if (message == null) return null
        return when (message) {
            is JsonPrimitive -> stripCqCodes(message.asString().orEmpty())
            is JsonArray -> message.joinToString("") { seg ->
                val obj = seg as? JsonObject ?: return@joinToString ""
                val type = obj.string("type") ?: return@joinToString ""
                if (type != "text") return@joinToString ""
                obj["data"]?.jsonObject?.string("text").orEmpty()
            }

            else -> stripCqCodes(message.toString())
        }
    }

    private fun stripCqCodes(raw: String): String =
        raw.replace(Regex("\\[CQ:[^\\]]*\\]"), "").trim()
}

private class OneBotForwardWsBot(
    private val wsUrl: String,
    private val commandRouter: CommandRouter,
) {
    private val echo = AtomicLong(1)
    private val pendingActionResponses = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val pendingBroadcastByUser = ConcurrentHashMap<Long, PendingBroadcast>()
    private val receiptById = ConcurrentHashMap<String, ReceiptTarget>()
    private val receiptTtlMs: Long = 7L * 24L * 60L * 60L * 1000L
    private val broadcastBlacklistLock = Any()
    private val broadcastBlacklistPath = PathUtils.dataPathResolve("broadcast", "group_blacklist.json")
    private val client = HttpClient(CIO) {
        engine {
            HttpProxyUtil.applyTo(this)
        }
        install(WebSockets)
    }

    private data class PendingBroadcast(
        val token: String,
        val content: String,
        val groupIds: List<Long>,
        val createdAtMs: Long,
    )

    private data class ReceiptTarget(
        val type: String, // "group" | "private"
        val groupId: Long?,
        val userId: Long,
        val title: String,
        val createdAtMs: Long,
    )

    suspend fun runForever() {
        if (wsUrl.isBlank()) {
            error("lomu.onebot.eventServerHost 不能为空（需要 ws://...）")
        }
        while (true) {
            try {
                log.info { "Connecting OneBot forward WS: $wsUrl" }
                client.webSocket(urlString = wsUrl) {
                    log.info { "OneBot WS connected: $wsUrl" }
                    while (isActive) {
                        when (val frame = incoming.receive()) {
                            is Frame.Text -> handleEvent(frame.readText(), this)
                            else -> Unit
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn(e) { "OneBot WS disconnected, retrying in 2s..." }
                delay(2_000)
            }
        }
    }

    private suspend fun handleEvent(text: String, session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession) {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return

        // Handle OneBot action responses (they don't have post_type).
        run {
            val echoValue = root.long("echo")
            val hasStatus = root["status"] != null || root["retcode"] != null
            val hasPostType = root["post_type"] != null
            if (echoValue != null && hasStatus && !hasPostType) {
                pendingActionResponses.remove(echoValue)?.complete(root)
                return
            }
        }

        val postType = root.string("post_type") ?: return
        if (postType == "request") {
            handleRequest(root, session)
            return
        }
        if (postType == "notice") {
            handleNotice(root, session)
            return
        }
        if (postType != "message") return
        val messageType = root.string("message_type") ?: return
        if (messageType != "group" && messageType != "private") return

        val messageId = root.long("message_id")
        val userId = root.long("user_id") ?: return
        val senderObj = root["sender"]?.jsonObject
        val senderName = senderObj?.string("nickname") ?: userId.toString()
        val senderRole = senderObj?.string("role")

        val rawMessage = root["raw_message"]?.asString() ?: root["message"]?.toString().orEmpty()
        val plainText = extractPlainText(root["message"])?.trim().orEmpty()

        if (messageType == "private") {
            val handled = tryHandleSuperAdminPrivateCommand(session, userId, senderName, plainText)
            if (handled) return

            if (AppConfig.alert.forwardPrivateEnabled && plainText.isNotBlank()) {
                val messenger = buildSuperAdminMessengerWs(session)
                messenger.sendToSuperAdmins(
                    """
                    [ERBot 私聊转发]
                    用户：${senderName}(${userId})
                    内容：${plainText.take(800)}
                    """.trimIndent()
                )
            }
            return
        }

        val groupId = root.long("group_id") ?: return

        // Super-admin help shortcuts (group). The actual operations are available via private chat.
        run {
            val t = plainText.trim()
            if (isSuperAdmin(userId) && (t == "群发帮助" || t == "群发 帮助" || t == "回执帮助")) {
                sendGroupReply(session, groupId, BotReply.Text(buildGroupAdminHelpHint()))
                return
            }
        }

        if (AppConfig.oneBot.ackEmojiEnabled && messageId != null && commandRouter.commandFind(plainText) != null) {
            // Best-effort ack as soon as a command is recognized.
            runCatching { sendAckEmojiLikeWs(session, messageId) }
        }

        val sender = MessageSender(
            groupOpenId = groupId.toString(),
            senderName = senderName,
            senderOpenId = userId.toString(),
            message = rawMessage,
            plainText = plainText,
            senderRole = senderRole,
        )
        val reply = withContext(SuperAdminMessengerContext(buildSuperAdminMessengerWs(session))) {
            commandRouter.call(sender)
        } ?: return

        sendGroupReply(session, groupId, reply)
    }

    private fun normalizeUserId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return Regex("\\d+").findAll(trimmed).toList().lastOrNull()?.value.orEmpty()
    }

    private fun normalizeGroupId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return Regex("\\d+").find(trimmed)?.value.orEmpty()
    }

    private suspend fun sendPrivateTextWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        userId: Long,
        text: String,
    ) {
        sendAction(
            session,
            action = "send_private_msg",
            params = buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("message", JsonPrimitive(text))
            },
        )
    }

    private suspend fun callActionAwait(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        action: String,
        params: JsonObject,
        timeoutMs: Long = 6_000,
    ): JsonObject? {
        val id = echo.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingActionResponses[id] = deferred
        val payload = buildJsonObject {
            put("action", JsonPrimitive(action))
            put("params", params)
            put("echo", JsonPrimitive(id))
        }
        session.send(Frame.Text(payload.toString()))
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingActionResponses.remove(id)
        return resp
    }

    private suspend fun getGroupIdListWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ): List<Long> {
        val resp = callActionAwait(session, action = "get_group_list", params = buildJsonObject { })
            ?: return emptyList()
        val data = resp["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            obj.long("group_id")
        }.distinct()
    }

    private fun readBroadcastBlacklistFile(): MutableSet<Long> {
        synchronized(broadcastBlacklistLock) {
            if (!broadcastBlacklistPath.toFile().exists()) return mutableSetOf()
            val raw = runCatching { broadcastBlacklistPath.toFile().readText() }.getOrNull()?.trim().orEmpty()
            if (raw.isBlank()) return mutableSetOf()
            val el = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return mutableSetOf()
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> (el["groupIds"] as? JsonArray) ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return arr.mapNotNull { it.asString()?.let(::normalizeGroupId)?.toLongOrNull() }.toMutableSet()
        }
    }

    private fun writeBroadcastBlacklistFile(groupIds: Set<Long>) {
        synchronized(broadcastBlacklistLock) {
            val parent = broadcastBlacklistPath.parent
            parent?.toFile()?.mkdirs()
            val obj = buildJsonObject {
                put("groupIds", JsonArray(groupIds.sorted().map { JsonPrimitive(it) }))
                put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
            }
            val tmp = broadcastBlacklistPath.resolveSibling("${broadcastBlacklistPath.fileName}.tmp")
            tmp.toFile().writeText(obj.toString())
            try {
                Files.move(tmp, broadcastBlacklistPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, broadcastBlacklistPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun combinedBroadcastBlacklist(): Set<Long> {
        val fromConfig = AppConfig.broadcast.groupBlacklist
            .mapNotNull { normalizeGroupId(it).toLongOrNull() }
            .toSet()
        val fromFile = readBroadcastBlacklistFile()
        return fromConfig + fromFile
    }

    private fun isSuperAdmin(userId: Long): Boolean {
        val normalized = normalizeUserId(userId.toString())
        return normalized.isNotBlank() && AppConfig.alias.superAdmins.any { normalizeUserId(it) == normalized }
    }

    private fun cleanupReceipts() {
        val now = System.currentTimeMillis()
        receiptById.entries.removeIf { now - it.value.createdAtMs > receiptTtlMs }
    }

    private fun extractReceiptTargetFromForwardText(text: String): ReceiptTarget? {
        // Forward message format is produced by Alerting.forwardUserMessage.
        // We only attach receipts for "[ERBot 转发]" to avoid noise.
        val lines = text.lines().map { it.trim() }
        val titleLine = lines.firstOrNull().orEmpty()
        val title = Regex("^\\[ERBot 转发\\]\\s*(.*)$")
            .find(titleLine)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "转发"

        val groupLine = lines.firstOrNull { it.startsWith("群：") }.orEmpty()
        val userLine = lines.firstOrNull { it.startsWith("用户：") }.orEmpty()

        val groupDigits = Regex("\\d+").find(groupLine)?.value
        val userDigits = Regex("\\d+").findAll(userLine).toList().lastOrNull()?.value
        val uid = userDigits?.toLongOrNull() ?: return null

        val gid = groupDigits?.toLongOrNull()
        val type = if (gid != null && gid > 0L) "group" else "private"
        return ReceiptTarget(type = type, groupId = gid, userId = uid, title = title, createdAtMs = System.currentTimeMillis())
    }

    private fun attachReceiptIfForwarded(text: String): String {
        val t = text.trim()
        if (!t.startsWith("[ERBot 转发]")) return text
        cleanupReceipts()
        val target = extractReceiptTargetFromForwardText(t) ?: return text

        var id: String
        do {
            id = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        } while (receiptById.containsKey(id))
        receiptById[id] = target

        return text +
            """

            回执ID：$id
            管理员回复：回执回复 $id <内容>
            """.trimIndent()
    }

    private fun buildReceiptHelp(): String =
        """
        [ERBot 回执回复（仅超级管理员）]
        说明：当你收到 “[ERBot 转发] …” 的转发消息时，会附带 “回执ID”。

        用法：
        - 回执回复 <回执ID> <内容>   （在原群里 @用户 并回复）
        - 回执查看 <回执ID>
        """.trimIndent()

    private fun buildAtSegment(userId: Long): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("at"))
            put(
                "data",
                buildJsonObject {
                    put("qq", JsonPrimitive(userId))
                }
            )
        }

    private suspend fun sendGroupTextWithAtWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        groupId: Long,
        userId: Long,
        text: String,
    ) {
        val segments = JsonArray(
            listOf(
                buildAtSegment(userId),
                buildTextSegment(" 管理员回复：$text"),
            )
        )
        sendAction(
            session,
            action = "send_group_msg",
            params = buildJsonObject {
                put("group_id", JsonPrimitive(groupId))
                put("message", segments)
            },
        )
    }

    private suspend fun sendPrivateTextToUserWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        userId: Long,
        text: String,
    ) {
        sendAction(
            session,
            action = "send_private_msg",
            params = buildJsonObject {
                put("user_id", JsonPrimitive(userId))
                put("message", JsonPrimitive(text))
            },
        )
    }

    private suspend fun tryHandleReceiptPrivateCommand(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        superAdminUserId: Long,
        plainText: String,
    ): Boolean {
        val text = plainText.trim()
        if (text.isBlank()) return false

        if (text == "回执帮助") {
            sendPrivateTextWs(session, superAdminUserId, buildReceiptHelp())
            return true
        }

        if (text.startsWith("回执查看")) {
            cleanupReceipts()
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val id = parts.getOrNull(1).orEmpty().uppercase()
            if (id.isBlank()) {
                sendPrivateTextWs(session, superAdminUserId, "用法：回执查看 <回执ID>")
                return true
            }
            val r = receiptById[id]
            if (r == null) {
                sendPrivateTextWs(session, superAdminUserId, "回执不存在或已过期：$id")
                return true
            }
            val ageSec = ((System.currentTimeMillis() - r.createdAtMs) / 1000L).coerceAtLeast(0)
            val target = if (r.type == "group") "群=${r.groupId}" else "私聊"
            sendPrivateTextWs(
                session,
                superAdminUserId,
                "回执 $id：title=${r.title} target=$target user=${r.userId} age=${ageSec}s"
            )
            return true
        }

        if (text.startsWith("回执回复")) {
            cleanupReceipts()
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val id = parts.getOrNull(1).orEmpty().uppercase()
            val content = parts.drop(2).joinToString(" ").trim()
            if (id.isBlank() || content.isBlank()) {
                sendPrivateTextWs(session, superAdminUserId, "用法：回执回复 <回执ID> <内容>")
                return true
            }
            val r = receiptById[id]
            if (r == null) {
                sendPrivateTextWs(session, superAdminUserId, "回执不存在或已过期：$id")
                return true
            }
            receiptById.remove(id)
            if (r.type == "group") {
                val gid = r.groupId ?: 0L
                if (gid <= 0L) {
                    sendPrivateTextWs(session, superAdminUserId, "回执异常：缺少 groupId，已丢弃。")
                    return true
                }
                sendGroupTextWithAtWs(session, gid, r.userId, content.take(1200))
                sendPrivateTextWs(session, superAdminUserId, "已回复到群：$gid（回执 $id 已关闭）")
                return true
            }
            sendPrivateTextToUserWs(session, r.userId, "管理员回复：${content.take(1200)}")
            sendPrivateTextWs(session, superAdminUserId, "已私聊回复用户：${r.userId}（回执 $id 已关闭）")
            return true
        }
        return false
    }

    private fun buildBroadcastHelp(): String =
        """
        [ERBot 群发（仅超级管理员）]
        说明：需要开启 lomu.broadcast.enable=true；并且建议“私聊 bot”执行。

        1) 发送预览：
           群发 <内容>
           例：群发 服务器将于今晚 23:00 重启

        2) 确认发送：
           群发确认 <token>

        3) 取消：
           群发取消

        4) 黑名单（不会接收群发）：
           群发黑名单 列表
           群发黑名单 添加 <群号>
           群发黑名单 删除 <群号>
        """.trimIndent()

    private fun buildGroupAdminHelpHint(): String =
        """
        这些指令需要“超级管理员私聊 bot”触发：
        - 群发帮助 / 群发 ...
        - 回执帮助 / 回执回复 ...
        """.trimIndent()

    private suspend fun tryHandleSuperAdminPrivateCommand(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        userId: Long,
        senderName: String,
        plainText: String,
    ): Boolean {
        val text = plainText.trim()
        if (text.isBlank()) return false
        if (!isSuperAdmin(userId)) return false

        // Receipt reply is always available for superAdmins (doesn't depend on broadcast switch).
        if (tryHandleReceiptPrivateCommand(session, userId, text)) return true

        // Guard: feature must be enabled explicitly.
        if (!AppConfig.broadcast.enable) {
            if (text == "群发帮助" || text == "群发 帮助") {
                sendPrivateTextWs(session, userId, buildBroadcastHelp())
                return true
            }
            return false
        }

        if (text == "群发帮助" || text == "群发 帮助") {
            sendPrivateTextWs(session, userId, buildBroadcastHelp())
            return true
        }

        if (text == "群发取消") {
            val removed = pendingBroadcastByUser.remove(userId)
            sendPrivateTextWs(session, userId, if (removed == null) "当前没有待确认的群发。" else "已取消群发。")
            return true
        }

        if (text.startsWith("群发黑名单")) {
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val op = parts.getOrNull(1) ?: "列表"
            when (op) {
                "列表", "list", "ls" -> {
                    val configSet = AppConfig.broadcast.groupBlacklist.mapNotNull { normalizeGroupId(it).toLongOrNull() }.toSet()
                    val fileSet = readBroadcastBlacklistFile()
                    val all = (configSet + fileSet).sorted()
                    val msg = buildString {
                        appendLine("[群发黑名单]")
                        appendLine("config：${if (configSet.isEmpty()) "<空>" else configSet.sorted().joinToString(",")}")
                        appendLine("file：${if (fileSet.isEmpty()) "<空>" else fileSet.sorted().joinToString(",")}")
                        append("all：${if (all.isEmpty()) "<空>" else all.joinToString(",")}")
                    }
                    sendPrivateTextWs(session, userId, msg)
                    return true
                }

                "添加", "add", "set" -> {
                    val gid = parts.getOrNull(2)?.let(::normalizeGroupId)?.toLongOrNull()
                        ?: run {
                            sendPrivateTextWs(session, userId, "用法：群发黑名单 添加 <群号>")
                            return true
                        }
                    val fileSet = readBroadcastBlacklistFile()
                    val changed = fileSet.add(gid)
                    if (changed) writeBroadcastBlacklistFile(fileSet)
                    sendPrivateTextWs(session, userId, if (changed) "已加入黑名单：$gid" else "已存在：$gid")
                    return true
                }

                "删除", "del", "rm" -> {
                    val gid = parts.getOrNull(2)?.let(::normalizeGroupId)?.toLongOrNull()
                        ?: run {
                            sendPrivateTextWs(session, userId, "用法：群发黑名单 删除 <群号>")
                            return true
                        }
                    val fileSet = readBroadcastBlacklistFile()
                    val changed = fileSet.remove(gid)
                    if (changed) writeBroadcastBlacklistFile(fileSet)
                    sendPrivateTextWs(session, userId, if (changed) "已移出黑名单：$gid" else "未找到：$gid")
                    return true
                }
            }
            sendPrivateTextWs(session, userId, "未知操作：$op（支持：列表/添加/删除）")
            return true
        }

        if (text.startsWith("群发确认")) {
            val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            val token = parts.getOrNull(1).orEmpty()
            if (token.isBlank()) {
                sendPrivateTextWs(session, userId, "用法：群发确认 <token>")
                return true
            }
            val pending = pendingBroadcastByUser[userId]
            if (pending == null) {
                sendPrivateTextWs(session, userId, "当前没有待确认的群发。")
                return true
            }
            val ttlMs = AppConfig.broadcast.confirmTtlSeconds.toLong() * 1000L
            val now = System.currentTimeMillis()
            if (now - pending.createdAtMs > ttlMs) {
                pendingBroadcastByUser.remove(userId)
                sendPrivateTextWs(session, userId, "群发已过期，请重新发送：群发 <内容>")
                return true
            }
            if (pending.token != token) {
                sendPrivateTextWs(session, userId, "token 不匹配。请确认后再试。")
                return true
            }

            // Execute broadcast.
            pendingBroadcastByUser.remove(userId)
            val delayMs = AppConfig.broadcast.sendDelayMs.toLong()
            val total = pending.groupIds.size
            if (total <= 0) {
                sendPrivateTextWs(session, userId, "目标群列表为空，未发送。")
                return true
            }
            sendPrivateTextWs(session, userId, "开始群发：目标群数=$total（每群间隔${delayMs}ms）")
            var sent = 0
            for (gid in pending.groupIds) {
                sendAction(
                    session,
                    action = "send_group_msg",
                    params = buildJsonObject {
                        put("group_id", JsonPrimitive(gid))
                        put("message", JsonPrimitive(pending.content))
                    },
                )
                sent++
                if (delayMs > 0) delay(delayMs)
            }
            sendPrivateTextWs(session, userId, "群发完成：已发送 $sent/$total。")
            return true
        }

        if (text.startsWith("群发")) {
            val content = text.removePrefix("群发").trim()
            if (content.isBlank()) {
                sendPrivateTextWs(session, userId, buildBroadcastHelp())
                return true
            }

            val blacklist = combinedBroadcastBlacklist()
            val allGroups = getGroupIdListWs(session)
            if (allGroups.isEmpty()) {
                sendPrivateTextWs(session, userId, "获取群列表失败：返回为空（OneBot 可能不支持 get_group_list，或 WS 不返回 action 响应）。")
                return true
            }
            val targets = allGroups.filterNot { blacklist.contains(it) }

            val token = UUID.randomUUID().toString().replace("-", "").take(6)
            pendingBroadcastByUser[userId] = PendingBroadcast(
                token = token,
                content = content,
                groupIds = targets,
                createdAtMs = System.currentTimeMillis(),
            )
            sendPrivateTextWs(
                session,
                userId,
                """
                [群发预览]
                目标群：${targets.size}/${allGroups.size}（黑名单跳过：${allGroups.size - targets.size}）
                内容：${content.take(600)}
                
                确认发送：群发确认 $token（${AppConfig.broadcast.confirmTtlSeconds}s 内有效）
                取消：群发取消
                """.trimIndent()
            )
            return true
        }

        return false
    }

    private suspend fun handleRequest(
        root: JsonObject,
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ) {
        if (!AppConfig.alert.enable) return
        if (!AppConfig.alert.forwardGroupInviteEnabled) return

        val requestType = root.string("request_type") ?: return
        if (requestType != "group") return

        val subType = root.string("sub_type") ?: "unknown"
        // We mainly care about invite requests, but "add" is also useful to forward.
        if (subType != "invite" && subType != "add") return

        val groupId = root.long("group_id") ?: return
        val userId = root.long("user_id") ?: return
        val comment = root.string("comment").orEmpty()
        val flag = root.string("flag").orEmpty()

        val msg =
            """
            [ERBot 入群请求转发]
            类型：$subType
            群：$groupId
            发起人：$userId
            备注：${comment.take(300)}
            flag：${flag.take(200)}
            """.trimIndent()

        runCatching {
            buildSuperAdminMessengerWs(session).sendToSuperAdmins(msg)
        }
    }

    private suspend fun handleNotice(
        root: JsonObject,
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ) {
        if (!AppConfig.help.onJoinEnabled) return
        val noticeType = root.string("notice_type") ?: return
        if (noticeType != "group_increase") return
        val selfId = root.long("self_id") ?: return
        val userId = root.long("user_id") ?: return
        if (selfId != userId) return

        val groupId = root.long("group_id") ?: return
        runCatching {
            sendGroupReply(session, groupId, buildHelpReplyForJoin())
        }.onFailure { e ->
            log.warn(e) { "Failed to send help on group join via WS: groupId=$groupId" }
        }
    }

    private fun extractPlainText(message: JsonElement?): String? {
        if (message == null) return null
        return when (message) {
            is JsonPrimitive -> stripCqCodes(message.asString().orEmpty())
            is JsonArray -> message.joinToString("") { seg ->
                val obj = seg as? JsonObject ?: return@joinToString ""
                val type = obj.string("type") ?: return@joinToString ""
                if (type != "text") return@joinToString ""
                obj["data"]?.jsonObject?.string("text").orEmpty()
            }
            else -> stripCqCodes(message.toString())
        }
    }

    private fun stripCqCodes(raw: String): String =
        raw.replace(Regex("\\[CQ:[^\\]]*\\]"), "").trim()

    private suspend fun sendGroupReply(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        groupId: Long,
        reply: BotReply,
    ) {
        when (reply) {
            is BotReply.Text -> {
                sendAction(
                    session,
                    action = "send_group_msg",
                    params = buildJsonObject {
                        put("group_id", JsonPrimitive(groupId))
                        put("message", JsonPrimitive(reply.text))
                    },
                )
            }

            is BotReply.ImageFile -> {
                val fileParam = toOneBotFileParam(reply.path)
                sendAction(
                    session,
                    action = "send_group_msg",
                    params = buildJsonObject {
                        put("group_id", JsonPrimitive(groupId))
                        put("message", JsonArray(listOf(buildImageSegment(fileParam))))
                    },
                )
            }

            is BotReply.Multi -> {
                val segments = buildSegments(reply)
                if (segments.isEmpty()) return
                sendAction(
                    session,
                    action = "send_group_msg",
                    params = buildJsonObject {
                        put("group_id", JsonPrimitive(groupId))
                        put("message", JsonArray(segments))
                    },
                )
            }
        }
    }

    private fun buildSegments(reply: BotReply): List<JsonObject> {
        fun flatten(r: BotReply): List<JsonObject> {
            return when (r) {
                is BotReply.Text -> listOf(buildTextSegment(r.text))
                is BotReply.ImageFile -> listOf(buildImageSegment(toOneBotFileParam(r.path)))
                is BotReply.Multi -> r.replies.flatMap { flatten(it) }
            }
        }
        return flatten(reply)
    }

    private fun buildTextSegment(text: String): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("text"))
            put(
                "data",
                buildJsonObject {
                    put("text", JsonPrimitive(text))
                }
            )
        }

    private fun buildImageSegment(fileParam: String): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("image"))
            put(
                "data",
                buildJsonObject {
                    put("file", JsonPrimitive(fileParam))
                    put("cache", JsonPrimitive("0"))
                    put("proxy", JsonPrimitive("0"))
                }
            )
        }

    private suspend fun sendAction(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        action: String,
        params: JsonObject,
    ) {
        val payload = buildJsonObject {
            put("action", JsonPrimitive(action))
            put("params", params)
            put("echo", JsonPrimitive(echo.getAndIncrement()))
        }
        session.send(Frame.Text(payload.toString()))
    }

    private suspend fun sendAckEmojiLikeWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        messageId: Long,
    ) {
        val emojiId = AppConfig.oneBot.ackEmojiId
        sendAction(
            session,
            action = "set_msg_emoji_like",
            params = buildJsonObject {
                put("message_id", JsonPrimitive(messageId))
                put("emoji_id", toJsonNumberOrString(emojiId))
            },
        )
    }

    private fun buildSuperAdminMessengerWs(
        session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
    ): SuperAdminMessenger {
        val superAdmins = AppConfig.alias.superAdmins
        return SuperAdminMessenger { text ->
            if (superAdmins.isEmpty()) return@SuperAdminMessenger
            val payload = attachReceiptIfForwarded(text)
            for (uid in superAdmins) {
                val n = uid.trim().takeIf { it.isNotBlank() } ?: continue
                sendAction(
                    session,
                    action = "send_private_msg",
                    params = buildJsonObject {
                        put("user_id", toJsonNumberOrString(n))
                        put("message", JsonPrimitive(payload))
                    },
                )
            }
        }
    }

    private fun toOneBotFileParam(path: String): String {
        val trimmed = path.trim()
        if (trimmed.startsWith("file://")) return trimmed
        // OneBot implementations usually accept absolute paths, but file:// is more explicit.
        return if (trimmed.startsWith("/")) "file://$trimmed" else trimmed
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.asString()

private fun JsonObject.long(key: String): Long? =
    this[key]?.asString()?.toLongOrNull()

private fun JsonElement.asString(): String? =
    (this as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }
