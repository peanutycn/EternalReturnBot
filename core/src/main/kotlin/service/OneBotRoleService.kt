package cn.luorenmu.service

import cn.luorenmu.common.util.HttpProxyUtil
import cn.luorenmu.config.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Best-effort role check for OneBot v11 groups.
 *
 * It calls OneBot HTTP API `get_group_member_info` and checks `data.role`.
 */
class OneBotRoleService {
    private val log = KotlinLogging.logger { }

    data class RoleCheckDiagnosis(
        val enabled: Boolean,
        val apiServerHost: String,
        val tokenPresent: Boolean,
        val groupIdRaw: String,
        val userIdRaw: String,
        val parsedGroupId: Long?,
        val parsedUserId: Long?,
        val httpStatus: Int?,
        val oneBotStatus: String?,
        val oneBotRetcode: Long?,
        val oneBotRole: String?,
        val allowed: Boolean?,
        val error: String?,
        val responseSnippet: String?,
    )

    private val client = HttpClient(CIO) {
        engine {
            HttpProxyUtil.applyTo(this)
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
        }
    }

    suspend fun isGroupAdminOrOwner(groupIdRaw: String, userIdRaw: String): Boolean {
        return diagnoseGroupAdminOrOwner(groupIdRaw, userIdRaw).allowed == true
    }

    suspend fun diagnoseGroupAdminOrOwner(groupIdRaw: String, userIdRaw: String): RoleCheckDiagnosis {
        val enabled = AppConfig.alias.enableOneBotRoleCheck
        if (!enabled) {
            return RoleCheckDiagnosis(
                enabled = false,
                apiServerHost = redactTokenInUrl(AppConfig.oneBot.apiServerHost),
                tokenPresent = !AppConfig.oneBot.accessToken.isNullOrBlank(),
                groupIdRaw = groupIdRaw,
                userIdRaw = userIdRaw,
                parsedGroupId = null,
                parsedUserId = null,
                httpStatus = null,
                oneBotStatus = null,
                oneBotRetcode = null,
                oneBotRole = null,
                allowed = null,
                error = "role check disabled: lomu.alias.enableOneBotRoleCheck=false",
                responseSnippet = null,
            )
        }
        // NOTE:
        // Simbot/adapter ids may be composite strings like "<groupId>-<botId>" or "<botId>-<userId>".
        // Group id should prefer the first number, while user id should prefer the last number.
        val groupId = parseLongIdFirst(groupIdRaw)
        val userId = parseLongIdLast(userIdRaw)

        val base = AppConfig.oneBot.apiServerHost.trim().trimEnd('/')
        if (base.isEmpty()) {
            return RoleCheckDiagnosis(
                enabled = true,
                apiServerHost = "",
                tokenPresent = !AppConfig.oneBot.accessToken.isNullOrBlank(),
                groupIdRaw = groupIdRaw,
                userIdRaw = userIdRaw,
                parsedGroupId = groupId,
                parsedUserId = userId,
                httpStatus = null,
                oneBotStatus = null,
                oneBotRetcode = null,
                oneBotRole = null,
                allowed = null,
                error = "lomu.onebot.apiServerHost is blank",
                responseSnippet = null,
            )
        }

        if (groupId == null || userId == null) {
            return RoleCheckDiagnosis(
                enabled = true,
                apiServerHost = redactTokenInUrl(base),
                tokenPresent = !AppConfig.oneBot.accessToken.isNullOrBlank(),
                groupIdRaw = groupIdRaw,
                userIdRaw = userIdRaw,
                parsedGroupId = groupId,
                parsedUserId = userId,
                httpStatus = null,
                oneBotStatus = null,
                oneBotRetcode = null,
                oneBotRole = null,
                allowed = null,
                error = "failed to parse group_id/user_id as numbers",
                responseSnippet = null,
            )
        }

        val token = AppConfig.oneBot.accessToken
        return try {
            val url = buildUrlWithToken("$base/get_group_member_info", token)
            val resp = client.post(url) {
                contentType(ContentType.Application.Json)
                if (!token.isNullOrBlank()) {
                    // Some implementations accept Authorization instead of query token.
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                // Ktor kotlinx-serialization cannot encode Map<String, Any> with mixed element types.
                setBody(
                    buildJsonObject {
                        put("group_id", groupId)
                        put("user_id", userId)
                        put("no_cache", true)
                    }
                )
            }
            val httpStatus = resp.status.value
            val text = runCatching { resp.bodyAsText() }.getOrElse { "" }
            val snippet = text.take(500).takeIf { it.isNotBlank() }

            val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            val oneBotStatus = obj?.get("status")?.jsonPrimitive?.contentOrNull
            val oneBotRetcode = obj?.get("retcode")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val role = obj?.get("data")?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull

            val notOkReason = when {
                oneBotStatus != null && oneBotStatus != "ok" -> "status=$oneBotStatus"
                oneBotRetcode != null && oneBotRetcode != 0L -> "retcode=$oneBotRetcode"
                else -> null
            }
            if (notOkReason != null) {
                log.debug {
                    "OneBot role check not ok: groupIdRaw=$groupIdRaw userIdRaw=$userIdRaw " +
                        "parsedGroupId=$groupId parsedUserId=$userId httpStatus=$httpStatus $notOkReason"
                }
                return RoleCheckDiagnosis(
                    enabled = true,
                    apiServerHost = redactTokenInUrl(base),
                    tokenPresent = !token.isNullOrBlank(),
                    groupIdRaw = groupIdRaw,
                    userIdRaw = userIdRaw,
                    parsedGroupId = groupId,
                    parsedUserId = userId,
                    httpStatus = httpStatus,
                    oneBotStatus = oneBotStatus,
                    oneBotRetcode = oneBotRetcode,
                    oneBotRole = role,
                    allowed = false,
                    error = notOkReason,
                    responseSnippet = snippet,
                )
            }

            val allowed = role == "admin" || role == "owner"
            log.debug {
                "OneBot role check ok: groupId=$groupId userId=$userId httpStatus=$httpStatus role=$role allowed=$allowed"
            }
            RoleCheckDiagnosis(
                enabled = true,
                apiServerHost = redactTokenInUrl(base),
                tokenPresent = !token.isNullOrBlank(),
                groupIdRaw = groupIdRaw,
                userIdRaw = userIdRaw,
                parsedGroupId = groupId,
                parsedUserId = userId,
                httpStatus = httpStatus,
                oneBotStatus = oneBotStatus,
                oneBotRetcode = oneBotRetcode,
                oneBotRole = role,
                allowed = allowed,
                error = null,
                responseSnippet = snippet,
            )
        } catch (e: Exception) {
            log.debug(e) {
                "OneBot role check failed: groupIdRaw=$groupIdRaw userIdRaw=$userIdRaw " +
                    "parsedGroupId=$groupId parsedUserId=$userId host=${redactTokenInUrl(AppConfig.oneBot.apiServerHost)}"
            }
            RoleCheckDiagnosis(
                enabled = true,
                apiServerHost = redactTokenInUrl(base),
                tokenPresent = !token.isNullOrBlank(),
                groupIdRaw = groupIdRaw,
                userIdRaw = userIdRaw,
                parsedGroupId = groupId,
                parsedUserId = userId,
                httpStatus = null,
                oneBotStatus = null,
                oneBotRetcode = null,
                oneBotRole = null,
                allowed = false,
                error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
                responseSnippet = null,
            )
        }
    }

    private fun buildUrlWithToken(baseUrl: String, token: String?): String {
        val t = token?.trim()?.takeIf { it.isNotBlank() } ?: return baseUrl
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return "${baseUrl}${separator}access_token=${java.net.URLEncoder.encode(t, "UTF-8")}"
    }

    private fun redactTokenInUrl(raw: String): String {
        if (raw.isBlank()) return raw
        return raw.replace(Regex("(access_token=)[^&]+"), "$1***")
    }

    private fun parseLongIdFirst(raw: String): Long? {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { return it }
        Regex("\\d+").find(trimmed)?.value?.toLongOrNull()?.let { return it }
        return null
    }

    private fun parseLongIdLast(raw: String): Long? {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { return it }
        Regex("\\d+").findAll(trimmed).toList().lastOrNull()?.value?.toLongOrNull()?.let { return it }
        return null
    }
}
