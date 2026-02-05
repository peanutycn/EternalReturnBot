package cn.luorenmu.request.api

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.exception.MessageReplyException
import cn.luorenmu.request.RequestManager
import cn.luorenmu.request.api.entity.response.dakgg.*
import cn.luorenmu.request.entity.module.DakGGServerName
import cn.luorenmu.request.entity.module.DakGGTeamMode
import io.ktor.client.call.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 *
 * @author LoMu
 * Date 2025/10/31 23:40
 */
object EternalReturnDakGGApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun getDataSeasons(): DakGGSeasonResponse {
        return EternalReturnDakGGApi.Data.GetGameDataBySeason.call().body()
    }

    suspend fun getCharacters(): DakGGCharactersResponse {
        return EternalReturnDakGGApi.Data.GetCharacters.call().body()
    }

    suspend fun getCharactersFresh(): DakGGCharactersResponse {
        // Drop in-memory cache for the exact URL, then fetch again.
        RequestManager.invalidateCacheByUrl(EternalReturnDakGGApi.Data.GetCharacters.url)
        return EternalReturnDakGGApi.Data.GetCharacters.call().body()
    }

    suspend fun getTiers(): DakGGTiersResponse {
        return EternalReturnDakGGApi.Data.GetTiers.call().body()
    }

    suspend fun getDataCurrentSeason(): DakGGCurrentSeasonResponse {
        val resp =
            EternalReturnDakGGApi.Data.GetCurrentSeason.call()
        val season = resp.body<DakGGCurrentSeasonResponse>()
        return season
    }

    suspend fun getItems(): DakGGItemsResponse {
        return EternalReturnDakGGApi.Data.GetItems.call().body()
    }


    suspend fun getCutoffsAndLeaderboard(
        page: Int = 1,
        seasonType: String,
        serverName: DakGGServerName,
        teamMode: DakGGTeamMode,
    ): DakGGLeaderboardResponse {
        val leaderboardApi = EternalReturnDakGGApi.Leaderboard.GetLeaderboard(page, seasonType, serverName, teamMode)
        val resp = leaderboardApi.call()
        return resp.body<DakGGLeaderboardResponse>()
    }

    suspend fun getTierDistributions(teamMode: DakGGTeamMode): TierDistributionsResponse {
        val tierDistributionApi =
            EternalReturnDakGGApi.Statistics.GetTierDistribution(teamMode)
        val resp = tierDistributionApi.call()
        return resp.body<TierDistributionsResponse>()
    }

    suspend fun getWeapons(): DakGGWeaponResponse {
        return EternalReturnDakGGApi.Data.GetWeapons.call().body()
    }

    suspend fun getProfile(nickname: String): DakGGProfileResponse {
        val resp = EternalReturnDakGGApi.User.GetProfile(nickname).call()
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw MessageReplyException(BotReply.Text("查询玩家失败：第三方数据源返回 ${resp.status.value}"))
        }
        return runCatching { json.decodeFromString(DakGGProfileResponse.serializer(), text) }
            .getOrElse { e ->
                val message = extractMessage(text)
                throw MessageReplyException(
                    BotReply.Text(
                        if (message != null) "查询玩家失败：$message"
                        else "查询玩家失败：第三方数据源返回了非预期数据（可能是玩家不存在/被限流）"
                    ),
                    e.stackTraceToString()
                )
            }
    }

    suspend fun getMatches(
        nickname: String,
        season: String?,
        matchingMode: String = "ALL",
        teamMode: String = "ALL",
        page: Int = 1,
    ): DakGGMatchesResponse {
        val resp = EternalReturnDakGGApi.User.GetMatches(nickname, season, matchingMode, teamMode, page).call()
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw MessageReplyException(BotReply.Text("查询战绩失败：第三方数据源返回 ${resp.status.value}"))
        }
        return runCatching { json.decodeFromString(DakGGMatchesResponse.serializer(), text) }
            .getOrElse { e ->
                val message = extractMessage(text)
                throw MessageReplyException(
                    BotReply.Text(
                        if (message != null) "查询战绩失败：$message"
                        else "查询战绩失败：第三方数据源返回了非预期数据（可能是玩家不存在/被限流）"
                    ),
                    e.stackTraceToString()
                )
            }
    }

    suspend fun getMatchesAutoSeason(nickname: String, currentSeason: DakGGCurrentSeasonResponse): DakGGMatchesResponse {
        val candidates = listOf(currentSeason.type, currentSeason.id.toString()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        var last: Exception? = null
        for (season in candidates) {
            try {
                return getMatches(nickname, season)
            } catch (e: Exception) {
                last = e
            }
        }
        return try {
            getMatches(nickname, null)
        } catch (e: Exception) {
            last = e
            throw last ?: IllegalStateException("Failed to fetch matches: no season candidates")
        }
    }

    suspend fun getMatchById(
        nickname: String,
        seasonId: Int,
        gameId: String,
    ): DakGGMatchByIdResponse {
        val resp = EternalReturnDakGGApi.User.GetMatchById(nickname, seasonId, gameId).call()
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            throw MessageReplyException(BotReply.Text("查询战绩详情失败：第三方数据源返回 ${resp.status.value}"))
        }
        return runCatching { json.decodeFromString(DakGGMatchByIdResponse.serializer(), text) }
            .getOrElse { e ->
                val message = extractMessage(text)
                throw MessageReplyException(
                    BotReply.Text(
                        if (message != null) "查询战绩详情失败：$message"
                        else "查询战绩详情失败：第三方数据源返回了非预期数据（可能被限流）"
                    ),
                    e.stackTraceToString()
                )
            }
    }

    suspend fun getTacticalSkills(): DakGGTacticalSkillResponse {
        return EternalReturnDakGGApi.Data.GetTacticalSkills.call().body()
    }

    suspend fun getTraitSkills(): DakGGTraitSkillsResponse {
        return EternalReturnDakGGApi.Data.GetTraitSkills.call().body()
    }

    private fun extractMessage(text: String): String? {
        val el = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return el["message"]?.jsonPrimitive?.contentOrNull
            ?: el["error"]?.jsonPrimitive?.contentOrNull
            ?: el["code"]?.jsonPrimitive?.contentOrNull
    }

}
