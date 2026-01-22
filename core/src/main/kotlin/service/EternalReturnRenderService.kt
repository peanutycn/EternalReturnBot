package cn.luorenmu.service

import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.config.AppConfig
import cn.luorenmu.request.api.entity.response.dakgg.DakGGMatchesResponse
import cn.luorenmu.exception.MessageReplyException
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.request.api.Api.Companion.ioAsync
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.api.EternalReturnOpenApiClient
import cn.luorenmu.request.api.EternalReturnWebUrls
import cn.luorenmu.request.api.entity.module.ImageResourcesType
import cn.luorenmu.request.api.entity.response.dakgg.DakGGCharacterImgType
import cn.luorenmu.request.api.entity.response.dakgg.DakGGCharactersResponse
import cn.luorenmu.request.api.entity.response.dakgg.DakGGLeaderboardResponse
import cn.luorenmu.request.api.entity.response.dakgg.DakGGTiersResponse
import cn.luorenmu.request.api.entity.response.dakgg.DakGGMatchByIdResponse
import cn.luorenmu.request.api.entity.response.game.BattleUserGamesResponse.UserGame
import cn.luorenmu.request.api.entity.response.user.UserStatsResponse
import cn.luorenmu.request.api.entity.response.dakgg.DakGGProfileResponse
import java.text.NumberFormat
import java.util.Locale
import cn.luorenmu.request.entity.module.DakGGServerName
import cn.luorenmu.request.entity.module.DakGGTeamMode
import cn.luorenmu.request.entity.module.MatchingMode
import cn.luorenmu.service.entity.EternalReturnEquip
import cn.luorenmu.service.entity.EternalReturnOldName
import cn.luorenmu.service.entity.EternalReturnPlayRender
import cn.luorenmu.service.entity.TierStatistics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Collectors

/**
 *
 * @author LoMu
 * Date 2025/11/21 14:20
 */
class EternalReturnRenderService {

    private val log = KotlinLogging.logger { }

    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private val matchTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    private data class CachedRankBadges(
        val global: String,
        val local: String,
        val in1000: String,
        val cachedAtMs: Long,
    )

    private val rankBadgeCache = ConcurrentHashMap<String, CachedRankBadges>()

    private fun formatSeasonPlayTime(playTimeSeconds: Long): String {
        if (playTimeSeconds <= 0) return "当前赛季游戏时间：0小时0分钟"
        val hours = playTimeSeconds / 3600
        val minutes = (playTimeSeconds % 3600) / 60
        return "当前赛季游戏时间：${hours}小时${minutes}分钟"
    }

    private fun formatRankText(rank: DakGGProfileResponse.ProfilePlayerSeasonOverviews.ProfileRankGlobal?): String {
        if (rank == null) return ""
        if (rank.rank <= 0L) return ""
        val rankStr = NumberFormat.getIntegerInstance(Locale.US).format(rank.rank)
        if (rank.rankSize <= 0L) return "${rankStr}名"
        val pct = (rank.rank.toDouble() / rank.rankSize.toDouble()) * 100.0
        val pctStr = String.format("%.2f", pct)
        return "${rankStr}名（上位${pctStr}%）"
    }

    private fun resolveSeasonBannerUrl(seasonName: String, seasonId: Int): String {
        val nameNumber = Regex("\\d+").find(seasonName)?.value?.toIntOrNull()
        val candidate = nameNumber ?: seasonId
        val safe = if (candidate in 1..20) candidate else 10
        return "https://cdn.dak.gg/er/images/bg/bg-landing-search-v${safe}.jpg"
    }

    private fun normalizeRankKey(nickname: String, mode: MatchingMode): String =
        nickname.trim().lowercase() + "#" + mode.value

    private fun parseRankBadgesFromPageText(text: String): CachedRankBadges? {
        // Extract rank lines from dak.gg page text.
        // Examples:
        // - "1,687名（上位0.78%）"
        // - "Asia 1,664名（上位0.78%）"  (we intentionally drop the server label)
        val pattern = Regex("""([0-9,]+)名（上位([0-9.]+)%）""")
        val items = pattern.findAll(text)
            .map { m -> "${m.groupValues[1]}名（上位${m.groupValues[2]}%）" }
            .distinct()
            .toList()
        if (items.isEmpty()) return null
        return CachedRankBadges(
            global = items.getOrNull(0).orEmpty(),
            local = items.getOrNull(1).orEmpty(),
            in1000 = "",
            cachedAtMs = System.currentTimeMillis(),
        )
    }

    private fun scrapeRankBadgesFromWeb(nickname: String, matchingMode: MatchingMode): CachedRankBadges? {
        if (matchingMode != MatchingMode.Rank) return null
        val cacheKey = normalizeRankKey(nickname, matchingMode)
        val now = System.currentTimeMillis()
        rankBadgeCache[cacheKey]?.let { cached ->
            if (now - cached.cachedAtMs < 30L * 60L * 1000L) return cached
        }

        return try {
            var bodyText: String? = null
            BrowserPool.getBrowser().customizeSelector(
                url = EternalReturnWebUrls.playerPage(nickname),
                selector = "body",
            ) { page, _ ->
                page.waitForTimeout(2500.0)
                bodyText = runCatching {
                    page.evaluate("() => document.body && document.body.innerText")?.toString()
                }.getOrNull()
            }
            val parsed = bodyText?.let { parseRankBadgesFromPageText(it) }
            log.debug {
                val len = bodyText?.length ?: 0
                "Rank scrape: nickname=$nickname ok=${parsed != null} textLen=$len " +
                    "global='${parsed?.global.orEmpty()}' local='${parsed?.local.orEmpty()}'"
            }
            if (parsed != null) rankBadgeCache[cacheKey] = parsed
            parsed
        } catch (e: Exception) {
            log.debug(e) { "Failed to scrape rank badges from web: nickname=$nickname" }
            null
        }
    }

    private fun hasAnyRank(overview: DakGGProfileResponse.ProfilePlayerSeasonOverviews?): Boolean {
        val r = overview?.rank ?: return false
        return (r.global?.rank ?: 0L) > 0L || (r.local?.rank ?: 0L) > 0L || (r.in1000?.rank ?: 0L) > 0L
    }

    private fun pickOverviewForRank(overviews: List<DakGGProfileResponse.ProfilePlayerSeasonOverviews>): DakGGProfileResponse.ProfilePlayerSeasonOverviews? {
        if (overviews.isEmpty()) return null
        val ranked = overviews.filter { it.matchingModeId == MatchingMode.Rank.value }
        val candidates = if (ranked.isNotEmpty()) ranked else overviews
        // Prefer the overview that actually contains rank fields.
        return candidates.firstOrNull { hasAnyRank(it) } ?: candidates.firstOrNull()
    }

    private fun serverNameCovert(serverName: String): String =
        when (serverName) {
            "Asia" -> "亚一"
            "Asia2" -> "亚二"
            "NorthAmerica" -> "北美"
            "Europe" -> "欧洲"
            "SouthAmerica" -> "南美"
            "Australia" -> "澳一"
            else -> serverName
        }

    private fun matchRating(matches: List<EternalReturnPlayRender.EternalReturnPlayerMatchData>): String? {
        if (matches.isEmpty()) return null
        val maxServer = matches.groupBy { it.serverName }.maxByOrNull { it.value.size }?.key ?: return null
        val modeId =
            matches.groupBy { it.matchingModeId }.maxByOrNull { it.value.size }?.key ?: MatchingMode.Rank.value
        val filter = matches.filter { it.matchingModeId == modeId }
        val count = filter.size
        if (count <= 0) return null
        val avg = filter.map { it.dmg }.average().toInt()
        val top1Count = filter.count { it.rank == 1 }
        val winRate = String.format("%.2f", (top1Count.toDouble() / count.toDouble()) * 100)
        val modeName = MatchingMode.convert(modeId).modeName
        return "常驻服务器${serverNameCovert(maxServer)} ${modeName}模式 ${count}场对局 胜率:${winRate}% 平均伤害:${avg}"
    }

    suspend fun getEternalReturnRender(
        nickname: String,
        matchingMode: MatchingMode,
        maxMatches: Int = 20,
    ): EternalReturnPlayRender {
        val safeMaxMatches = maxMatches.coerceAtLeast(1)
        /**
         * 数据收集
         */
        // val userStats = EternalReturnOpenApiClient.getUserStats(userId, 35, matchingMode)

        val (profile, characters, tiers, season) = coroutineScope {
            val profileDF = ioAsync { EternalReturnDakGGApiClient.getProfile(nickname) }
            val charactersDF = ioAsync { EternalReturnDakGGApiClient.getCharacters() }
            val tierDF = ioAsync { EternalReturnDakGGApiClient.getTiers() }
            val seasonDF = ioAsync { EternalReturnDakGGApiClient.getDataCurrentSeason() }
            Quad(profileDF.await(), charactersDF.await(), tierDF.await(), seasonDF.await())
        }

        val playerSeasonOverviews = profile.playerSeasonOverviews

        val traitSkills = EternalReturnDakGGApiClient.getTraitSkills()
        val traitSkillIdToGroupKey = traitSkills.traitSkills.associate { it.id to it.group }
        val playerSeasonOverview =
            playerSeasonOverviews.firstOrNull { it.matchingModeId == matchingMode.value } ?: run {
                playerSeasonOverviews.firstOrNull()
            }

        val accountLevel = profile.player.accountLevel
        val profileImageUrl = playerSeasonOverview?.run {
            val characterState = playerSeasonOverview.characterStats.first()
            val skinState = characterState.skinStats!!.first()
            ImageResourcesType.getCharacterPath(
                characterState.key.toInt(),
                skinState.key,
                DakGGCharacterImgType.CharResult
            )
        }


        /**
         * 近期一起玩的人
         */
        val recentPlays = mutableListOf<EternalReturnPlayRender.EternalReturnPlayerRecentPlay>()

        playerSeasonOverviews.firstOrNull { seasonOverview -> seasonOverview.duoStats.isNotEmpty() }
            ?.let { seasonOverview ->
                seasonOverview.duoStats.take(8).forEach { duoStat ->
                    val characterById = characters.getCharacterById(duoStat.characterStats.first().key)
                    recentPlays.add(EternalReturnPlayRender.EternalReturnPlayerRecentPlay().apply {
                        imageWrapperUrl = ImageResourcesType.getCharacterPath(
                            characterById.id.toInt(), characterById.skins.first().id,
                            DakGGCharacterImgType.CharProfile
                        )
                        this.plays = duoStat.play
                        val playDouble = this.plays.toDouble()
                        this.nickname = duoStat.nickname
                        this.winRate = "${String.format("%.1f", (duoStat.win / playDouble) * 100)}%"
                        this.avgRank = "#${String.format("%.1f", duoStat.place / playDouble)}"
                    })
                }
            }

        val eternalReturnPlayerData = EternalReturnPlayRender.EternalReturnPlayerData()


        /**
         * 段位收集
         */
        var tier: DakGGTiersResponse.EternalReturnTier = tiers.getUnRank()
        val latestPlaySeason = profile.playerSeasons.firstOrNull { it.seasonId == season.id }
            ?: profile.playerSeasons.firstOrNull()
            ?: run {
            throw MessageReplyException(BotReply.Text("该玩家无任何游玩数据"))
        }


        /**
         * 指定的就是排位
         */
        if (matchingMode == MatchingMode.Rank) {
            tier = tiers.getTierById(latestPlaySeason.tierId)
        }


        /**
         * 左边栏段位显示
         */
        eternalReturnPlayerData.tierImageUrl = ImageResourcesType.TierRound.getGeneralPath(tier.id.toString())
        if (matchingMode == MatchingMode.Rank) {
            eternalReturnPlayerData.rp = if (latestPlaySeason.mmr == 0) "段位鉴定中." else "${latestPlaySeason.mmr}RP"
            if (tier.id != 0) {
                val showGrade = !(latestPlaySeason.tierId > 6 && latestPlaySeason.tierId * 10 > 60)
                val gradeText = if (showGrade && latestPlaySeason.tierGradeId > 0) " ${latestPlaySeason.tierGradeId}" else ""
                eternalReturnPlayerData.rpName = "${tier.name}${gradeText} - ${latestPlaySeason.tierMmr}RP"
            } else {
                eternalReturnPlayerData.rpName = tier.name
            }
            eternalReturnPlayerData.tierImageUrl = ImageResourcesType.TierRound.getGeneralPath(tier.id.toString())
        } else {
            eternalReturnPlayerData.rpName = "非排位数据"
            eternalReturnPlayerData.rp =  "无"
        }


        /**
         * 左边栏数据展示
         */
        if (playerSeasonOverview !== null) {
            val playDouble = playerSeasonOverview.play.toDouble()
            eternalReturnPlayerData.play = playerSeasonOverview.play
            eternalReturnPlayerData.avgTk = String.format("%.2f", playerSeasonOverview.teamKill / playDouble)
            eternalReturnPlayerData.avgKill = String.format("%.2f", playerSeasonOverview.playerKill / playDouble)
            eternalReturnPlayerData.avgRank = "#" + String.format("%.2f", playerSeasonOverview.place / playDouble)
            eternalReturnPlayerData.avgDmg =
                (playerSeasonOverview.damageToPlayer / playerSeasonOverview.play).toString()
            eternalReturnPlayerData.avgAssists =
                String.format("%.2f", playerSeasonOverview.playerAssistant / playDouble)
            eternalReturnPlayerData.top1 = String.format("%.1f", (playerSeasonOverview.win / playDouble) * 100) + "%"
            eternalReturnPlayerData.top2 = String.format("%.1f", (playerSeasonOverview.top2 / playDouble) * 100) + "%"
            eternalReturnPlayerData.top3 = String.format("%.1f", (playerSeasonOverview.top3 / playDouble) * 100) + "%"
        }

        /**
         * 左边栏分数波动
         */
        var playerMMRStats: EternalReturnPlayRender.EternalReturnPlayerMMRStats? = null
        if (matchingMode == MatchingMode.Rank) {
            val rankOverview = profile.playerSeasonOverviews
                .firstOrNull { it.matchingModeId == MatchingMode.Rank.value }
            val fallbackOverview = profile.playerSeasonOverviews.firstOrNull()
            val overview = listOfNotNull(rankOverview, fallbackOverview).firstOrNull { it.mmrStats.isNotEmpty() }

            val mmrStatsRaw = overview?.mmrStats.orEmpty()
            if (mmrStatsRaw.isNotEmpty()) {
                val mmrStats = mmrStatsRaw.take(7).reversed()
                val pairs = mmrStats.mapNotNull { mmrs ->
                    val v = mmrs.getOrNull(1) ?: return@mapNotNull null
                    val raw = mmrs.firstOrNull()?.toString().orEmpty()
                    val dateStr = raw.takeIf { it.length >= 8 }?.substring(4)
                        ?.let { it.substring(0, 2) + "/" + it.substring(2) }
                        ?: raw
                    dateStr to v
                }
                if (pairs.isNotEmpty()) {
                    playerMMRStats = EternalReturnPlayRender.EternalReturnPlayerMMRStats(
                        mmrDate = pairs.map { it.first },
                        mmr = pairs.map { it.second },
                    )
                }
            }
            log.debug {
                val selectedMode = overview?.matchingModeId
                val selectedSize = overview?.mmrStats?.size ?: 0
                val rankSize = rankOverview?.mmrStats?.size ?: 0
                val fallbackSize = fallbackOverview?.mmrStats?.size ?: 0
                val points = playerMMRStats?.mmr?.size ?: 0
                "MMR stats: nickname=$nickname mode=$matchingMode selectedModeId=$selectedMode " +
                    "rankRaw=$rankSize fallbackRaw=$fallbackSize selectedRaw=$selectedSize points=$points"
            }
        }

        /**
         * 左边栏常用角色
         */

        val characterUseStats = mutableListOf<EternalReturnPlayRender.EternalReturnCharacterUseStats>()
        playerSeasonOverviews.firstOrNull { it.matchingModeId == 3 }?.characterStats?.take(8)
            ?.forEach { characterState ->
                val characterById = characters.getCharacterById(characterState.key)
                characterUseStats.add(
                    EternalReturnPlayRender.EternalReturnCharacterUseStats(
                        characterName = characterById.name,
                        imgUrl = ImageResourcesType.getCharacterPath(
                            characterById.id.toInt(), characterById.skins.first().id,
                            DakGGCharacterImgType.CharProfile
                        ),
                        winRate = "${
                            String.format(
                                "%.1f",
                                if (characterState.win == 0L) 0.0 else characterState.win / characterState.play.toDouble() * 100
                            )
                        }%",
                        characterPlay = characterState.play,
                        getRP = characterState.mmrGain,
                        avgRank = "#${
                            String.format(
                                "%.1f", characterState.place / characterState.play.toDouble()
                            )
                        }",
                        avgDmg = if (characterState.damageToPlayer == 0) 0 else characterState.damageToPlayer / characterState.play,
                    )
                )
            }

        val matches = if (AppConfig.bser.openApiKey.isNullOrBlank()) {
            val matchesResp = EternalReturnDakGGApiClient.getMatchesAutoSeason(nickname, season)
            matchesResp.matches.take(safeMaxMatches).map { gameConvertMatcherFromDakGG(it, characters, traitSkillIdToGroupKey) }
        } else {
            try {
                val userId = EternalReturnOpenApiClient.getUserNumByUserNickName(nickname).user.userId
                val gamesResponse = EternalReturnOpenApiClient.getGamesByUserNum(userId)
                gamesResponse.userGames.take(safeMaxMatches).map { gameConvertMatcher(it, characters, traitSkillIdToGroupKey) }
            } catch (e: Exception) {
                // Fallback to DakGG when OpenAPI is forbidden / rate-limited / misconfigured.
                log.warn(e) { "OpenAPI failed, falling back to DakGG matches: nickname=$nickname" }
                val matchesResp = EternalReturnDakGGApiClient.getMatchesAutoSeason(nickname, season)
                matchesResp.matches.take(safeMaxMatches).map { gameConvertMatcherFromDakGG(it, characters, traitSkillIdToGroupKey) }
            }
        }

        val teammateMatchLimit = AppConfig.render.teammateMatches.coerceAtLeast(0)
        val enrichedMatches =
            if (teammateMatchLimit <= 0) {
                matches
            } else {
                val byIdMap = fetchTeammatesByGameId(
                    nickname = nickname,
                    seasonId = season.id,
                    seasonKey = season.type,
                    matches = matches,
                    limit = teammateMatchLimit
                )
                matches.map { m ->
                    val teamInfo = byIdMap[m.gameId]
                    if (teamInfo == null) m
                    else m.copy(teamMates = buildTeammates(teamInfo, nickname, profile.player.userNum, traitSkillIdToGroupKey))
                }
            }

        val playTimeSeconds = profile.playerSeasonOverviews
            .firstOrNull { it.matchingModeId == matchingMode.value }
            ?.playTime
            ?: profile.playerSeasonOverviews.firstOrNull()?.playTime
            ?: 0

        // Global/local rank is available in DakGG profile for ranked overview.
        // Web screenshot shows this, so we also surface it in the rendered battle image when present.
        run {
            val overviewForRank = pickOverviewForRank(profile.playerSeasonOverviews)
            val rank = overviewForRank?.rank
            // Global: show plain text without prefix (matches web screenshot expectation).
            eternalReturnPlayerData.globalRankText = formatRankText(rank?.global)
            // Local: DakGG rank.local doesn't carry server label; keep it as-is (no "本服" wording).
            eternalReturnPlayerData.localRankText = formatRankText(rank?.local)
            // in1000 is often redundant with global/local; keep it empty to match web style.
            eternalReturnPlayerData.in1000RankText = ""

            // Fallback: profile API may omit rank fields for some players, but the web page still shows them.
            if (matchingMode == MatchingMode.Rank &&
                eternalReturnPlayerData.globalRankText.isBlank() &&
                eternalReturnPlayerData.localRankText.isBlank() &&
                eternalReturnPlayerData.in1000RankText.isBlank()
            ) {
                val scraped = scrapeRankBadgesFromWeb(nickname, matchingMode)
                if (scraped != null) {
                    eternalReturnPlayerData.globalRankText = scraped.global
                    eternalReturnPlayerData.localRankText = scraped.local
                    eternalReturnPlayerData.in1000RankText = scraped.in1000
                }
            }

            log.debug {
                val candidates = profile.playerSeasonOverviews.joinToString("; ") { ov ->
                    val g = ov.rank?.global?.rank ?: 0L
                    val l = ov.rank?.local?.rank ?: 0L
                    val i = ov.rank?.in1000?.rank ?: 0L
                    "mode=${ov.matchingModeId} team=${ov.teamModeId} g=$g l=$l i=$i"
                }
                "Rank badges: nickname=$nickname " +
                    "global='${eternalReturnPlayerData.globalRankText}' " +
                    "local='${eternalReturnPlayerData.localRankText}' " +
                    "serverStatsKeys=${overviewForRank?.serverStats?.map { it.key } ?: emptyList<String>()} " +
                    "candidates=[$candidates]"
            }
        }

        return EternalReturnPlayRender(
            mmrStats = playerMMRStats,
            nickName = nicknameHide(nickname),
            profileImageUrl = profileImageUrl,
            level = accountLevel,
            data = eternalReturnPlayerData,
            seasonPlayTimeText = formatSeasonPlayTime(playTimeSeconds),
            rating = matchRating(enrichedMatches),
            matches = enrichedMatches,
            recentPlayers = recentPlays,
            characterUseStats = characterUseStats,
            season = season.name,
            seasonBannerUrl = resolveSeasonBannerUrl(season.name, season.id),
            mode = matchingMode.modeName
        )
    }

    private suspend fun fetchTeammatesByGameId(
        nickname: String,
        seasonId: Int,
        seasonKey: String,
        matches: List<EternalReturnPlayRender.EternalReturnPlayerMatchData>,
        limit: Int,
    ): Map<String, DakGGMatchByIdResponse> = coroutineScope {
        val targetIds = matches.asSequence()
            .filter { it.matchingModeId == MatchingMode.Rank.value }
            .sortedByDescending { it.gameId.toLongOrNull() ?: 0L }
            .map { it.gameId }
            .filter { it.isNotBlank() && it != "0" }
            .distinct()
            .take(limit)
            .toList()

        if (targetIds.isEmpty()) return@coroutineScope emptyMap()

        val seasons = try {
            EternalReturnDakGGApiClient.getDataSeasons()
        } catch (_: Exception) {
            null
        }
        val mappedSeasonId = seasons?.seasons?.firstOrNull { it.key == seasonKey }?.id
        val seasonCandidates = listOfNotNull(seasonId, mappedSeasonId).distinct()

        val jobs = targetIds.map { gid ->
            ioAsync {
                try {
                    val resp = fetchMatchByIdAnySeason(nickname, seasonCandidates, gid) ?: return@ioAsync null
                    gid to resp
                } catch (e: Exception) {
                    log.debug(e) { "Failed to fetch match-by-id: nickname=$nickname gameId=$gid" }
                    null
                }
            }
        }
        jobs.awaitAll().filterNotNull().toMap()
    }

    private suspend fun fetchMatchByIdAnySeason(
        nickname: String,
        seasonCandidates: List<Int>,
        gameId: String,
    ): DakGGMatchByIdResponse? {
        if (seasonCandidates.isEmpty()) return null
        for (sid in seasonCandidates) {
            val resp =
                try {
                    EternalReturnDakGGApiClient.getMatchById(
                        nickname = nickname,
                        seasonId = sid,
                        gameId = gameId,
                    )
                } catch (_: Exception) {
                    null
                }
            if (resp != null && resp.matches.isNotEmpty()) return resp
        }
        return null
    }

    private fun buildTeammates(
        byId: DakGGMatchByIdResponse,
        nickname: String,
        selfUserNum: Long,
        traitSkillIdToGroupKey: Map<Long, String>,
    ): List<EternalReturnPlayRender.EternalReturnPlayerMatchData.EternalReturnTeammate> {
        val self = byId.matches.firstOrNull { it.userNum == selfUserNum }
            ?: byId.matches.firstOrNull { it.nickname.equals(nickname, ignoreCase = true) }
            ?: return emptyList()
        val teamNumber = self.teamNumber
        if (teamNumber == 0L) return emptyList()
        val tierByUserNum = byId.playerTiers.associateBy { it.userNum.toLong() }
        return byId.matches
            .asSequence()
            .filter { it.teamNumber == teamNumber && it.userNum != selfUserNum }
            .map { mate ->
                val tierId = tierByUserNum[mate.userNum]?.tierId ?: 0
                val traitSkillGroupKey = mate.traitSecondSub.firstOrNull()?.let { traitSkillIdToGroupKey[it] }
                EternalReturnPlayRender.EternalReturnPlayerMatchData.EternalReturnTeammate(
                    nickName = mate.nickname,
                    avatarUrl = ImageResourcesType.getCharacterPath(
                        mate.characterNum.toInt(),
                        mate.skinCode,
                        DakGGCharacterImgType.CharProfile
                    ),
                    rp = mate.mmrAfter.toString(),
                    rpImageUrl = ImageResourcesType.TierRound.getGeneralPath(tierId.toString()),
                    tk = mate.teamKill,
                    kill = mate.playerKill,
                    assist = mate.playerAssistant,
                    dmg = mate.damageToPlayer.toInt(),
                    weaponUrl = ImageResourcesType.Weapon.getGeneralPath(mate.bestWeapon.toString()),
                    skillUrl = ImageResourcesType.TacticalSkill.getGeneralPath(mate.tacticalSkillGroup.toString()),
                    traitSkillUrl = ImageResourcesType.TraitSkill.getGeneralPath(mate.traitFirstCore.toString()),
                    traitSkillGroupUrl = if (mate.matchingMode == MatchingMode.Cobalt.value)
                        ImageResourcesType.TraitSkillGroupPlaceholder.getGeneralPath("")
                    else traitSkillGroupKey?.let { key ->
                        ImageResourcesType.TraitSkillGroup.getGeneralPath(key)
                    } ?: ImageResourcesType.TraitSkillGroupPlaceholder.getGeneralPath(""),
                    equips = gameEquip(mate.equipment, mate.equipmentGrade),
                )
            }
            .toList()
    }

    private fun formatMatchTime(startDtmRaw: String, durationSeconds: Long? = null): Pair<String, String> {
        val start = startDtmRaw.trim()
        if (start.isEmpty()) return "" to ""
        val zdt = runCatching { ZonedDateTime.parse(start, matchTimeFormatter) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(start) }.getOrNull()
            ?: return "" to ""
        val durationMin = durationSeconds
            ?.takeIf { it > 0 }
            ?.let { (it / 60).toInt().coerceAtLeast(1) }
        val time =
            if (durationMin == null) "%02d:%02d:%02d".format(zdt.hour, zdt.minute, zdt.second)
            else "%02d:%02d %dm".format(zdt.hour, zdt.minute, durationMin)
        val date = "${zdt.monthValue}月${zdt.dayOfMonth}日"
        return time to date
    }

    private fun gameConvertMatcher(
        game: UserGame,
        characters: DakGGCharactersResponse,
        traitSkillIdToGroupKey: Map<Long, String>,
    ): EternalReturnPlayRender.EternalReturnPlayerMatchData {
        val killAndAssist = game.playerKill + game.playerAssistant
        val traitSkillGroupKey = game.traitSecondSub.firstOrNull()?.let { traitSkillIdToGroupKey[it] }
        val (time, date) = formatMatchTime(game.startDtm, game.duration)
        return EternalReturnPlayRender.EternalReturnPlayerMatchData(
            rp = game.mmrAfter,
            rpChange = game.mmrGain,
            serverName = game.serverName,
            nickName = game.nickname,
            characterName = characters.getCharacterById(game.characterNum).name,
            rank = if (game.escapeState == 3) 99 else game.gameRank,
            matchingModeId = game.matchingMode,
            type = MatchingMode.convert(game.matchingMode).modeName,
            dateHour = time,
            dateMonth = date,
            kill = game.playerKill,
            tk = game.teamKill,
            equips = gameEquip(game.equipment, game.equipmentGrade),
            weaponUrl = ImageResourcesType.Weapon.getGeneralPath(game.bestWeapon.toString()),
            tacticalSkillUrl = ImageResourcesType.TacticalSkill.getGeneralPath(game.tacticalSkillGroup.toString()),
            traitSkillUrl = ImageResourcesType.TraitSkill.getGeneralPath(game.traitFirstCore.toString()),
            traitSkillGroupUrl = if (MatchingMode.convert(game.matchingMode) == MatchingMode.Cobalt)
                ImageResourcesType.TraitSkillGroupPlaceholder.getGeneralPath("")
            else traitSkillGroupKey?.let { ImageResourcesType.TraitSkillGroup.getGeneralPath(it) }
                ?: ImageResourcesType.TraitSkillGroupPlaceholder.getGeneralPath(""),
            characterAvatarUrl = ImageResourcesType.getCharacterPath(
                game.characterNum.toInt(),
                game.skinCode.toLong(),
                DakGGCharacterImgType.CharProfile
            ),
            assist = game.playerAssistant,
            gameId = game.gameId.toString(),
            dmg = game.damageToPlayer,
            kda = if (game.playerDeaths == 0) killAndAssist.toDouble()
            else killAndAssist.toDouble() / game.playerDeaths,
            routeId = if (game.routeIdOfStart != 0L) game.routeIdOfStart.toString() else "Private",
            version = "1.${game.versionMajor}.${game.versionMinor}"
        )
    }

    private fun gameConvertMatcherFromDakGG(
        game: DakGGMatchesResponse.Match,
        characters: DakGGCharactersResponse,
        traitSkillIdToGroupKey: Map<Long, String>,
    ): EternalReturnPlayRender.EternalReturnPlayerMatchData {
        val killAndAssist = game.playerKill + game.playerAssistant
        val traitSkillGroupKey = game.traitSecondSub.firstOrNull()?.let { traitSkillIdToGroupKey[it] }
        val (time, date) = formatMatchTime(game.startDtm, game.duration)
        return EternalReturnPlayRender.EternalReturnPlayerMatchData(
            rp = game.mmrAfter,
            rpChange = game.mmrGain,
            serverName = game.serverName,
            nickName = game.nickname,
            characterName = characters.getCharacterById(game.characterNum).name,
            rank = if (game.escapeState == 3) 99 else game.gameRank,
            matchingModeId = game.matchingMode,
            type = MatchingMode.convert(game.matchingMode).modeName,
            dateHour = time,
            dateMonth = date,
            kill = game.playerKill,
            tk = game.teamKill,
            equips = gameEquip(game.equipment, game.equipmentGrade),
            weaponUrl = ImageResourcesType.Weapon.getGeneralPath(game.bestWeapon.toString()),
            tacticalSkillUrl = ImageResourcesType.TacticalSkill.getGeneralPath(game.tacticalSkillGroup.toString()),
            traitSkillUrl = ImageResourcesType.TraitSkill.getGeneralPath(game.traitFirstCore.toString()),
            traitSkillGroupUrl = if (MatchingMode.convert(game.matchingMode) == MatchingMode.Cobalt)
                ImageResourcesType.TraitSkillGroupPlaceholder.getGeneralPath("")
            else traitSkillGroupKey?.let { ImageResourcesType.TraitSkillGroup.getGeneralPath(it) }
                ?: ImageResourcesType.TraitSkillGroupPlaceholder.getGeneralPath(""),
            characterAvatarUrl = ImageResourcesType.getCharacterPath(
                game.characterNum.toInt(),
                game.skinCode,
                DakGGCharacterImgType.CharProfile
            ),
            assist = game.playerAssistant,
            gameId = game.gameId.toString(),
            dmg = game.damageToPlayer,
            kda = if (game.playerDeaths == 0) killAndAssist.toDouble()
            else killAndAssist.toDouble() / game.playerDeaths,
            routeId = if (game.routeIdOfStart != 0L) game.routeIdOfStart.toString() else "Private",
            version = "1.${game.versionMajor}.${game.versionMinor}"
        )
    }

    private fun gameEquip(equipment: Map<Int, Int>, equipmentGrade: Map<Int, Int>): MutableList<EternalReturnEquip> {
        val equipList = mutableListOf<EternalReturnEquip>()
        for ((index, value) in equipment.entries.sortedBy { it.key }.take(6)) {
            val grade = equipmentGrade[index] ?: 0
            val equip = EternalReturnEquip(
                itemBgUrl = ImageResourcesType.ItemBg.getGeneralPath(grade.toString()),
                itemUrl = ImageResourcesType.Item.getGeneralPath(value.toString())
            )
            equipList.add(equip)
        }
        return equipList
    }

    private fun nicknameHide(nickname: String): String {
        return nickname
    }

    suspend fun getCutoffsAndTierNumber(serverName: DakGGServerName): TierStatistics {
        val (leaderboard, td, season) = coroutineScope {
            val leaderboardDeferred = ioAsync {
                val type = EternalReturnDakGGApiClient.getDataCurrentSeason().type
                EternalReturnDakGGApiClient.getCutoffsAndLeaderboard(1, type, serverName, DakGGTeamMode.Squad)
            }
            val tierDistributionDeferred = ioAsync {
                EternalReturnDakGGApiClient.getTierDistributions(DakGGTeamMode.Squad)
            }
            val seasonDF = ioAsync { EternalReturnDakGGApiClient.getDataCurrentSeason() }
            Triple(leaderboardDeferred.await(), tierDistributionDeferred.await(), seasonDF.await())
        }

        // 段位
        val tierTypes = td.distributions.stream().map { ds -> ds.tierType }.distinct().sorted { o1, o2 ->
            val i1 = if (o1 < 10) o1 * 10 else o1
            val i2 = if (o2 < 10) o2 * 10 else o2
            i1 - i2
        }.collect(Collectors.toList())

        val count = mutableMapOf<Int, Int>()
        val rate = mutableMapOf<Int, Double>()


        // 收集整个段位的人数和占率
        for (distribution in td.distributions) {
            count[distribution.tierType]?.let {
                count[distribution.tierType] = it + distribution.count
            } ?: run {
                count[distribution.tierType] = distribution.count
            }
            rate[distribution.tierType]?.let {
                rate[distribution.tierType] = it + distribution.rate
            } ?: run {
                rate[distribution.tierType] = distribution.rate
            }
        }

        // 预前赛或无永恒
        if (leaderboard.cutoffs.isEmpty()) {
            throw MessageReplyException(BotReply.Text("数据收集中..."))
        }
        val eternal: DakGGLeaderboardResponse.Cutoffs
        val demigod: DakGGLeaderboardResponse.Cutoffs
        when (leaderboard.cutoffs.size) {
            1 -> {
                eternal = leaderboard.cutoffs[0]
                demigod = leaderboard.cutoffs[0]
            }

            2 -> {
                eternal = leaderboard.cutoffs[1]
                demigod = leaderboard.cutoffs[0]
            }

            else -> {
                throw MessageReplyException(BotReply.Text("数据收集中..."))
            }
        }

        val rateStr = rate.mapValues { String.format("%.2f", it.value * 100) }.mapKeys { it.key.toString() }
        val tierTypesStr = tierTypes.map { it.toString() }
        val countStr = count.mapKeys { it.key.toString() }
        return TierStatistics(
            season.name,
            tierTypesStr,
            countStr,
            rateStr,
            eternal,
            demigod
        )
    }

    /**
     * TODO 该接口需要限制访问. 权限验证/群验证/角色验证
     */
    suspend fun oldName(nickname: String): EternalReturnOldName {
        val (userResponse, dataCurrentSeason) = coroutineScope {
            val userResponseDF = ioAsync {
                EternalReturnOpenApiClient.getUserNumByUserNickName(nickname)
            }
            val dataCurrentSeasonDF = ioAsync {
                EternalReturnOpenApiClient.getDataCurrentSeason()
            }
            userResponseDF.await() to dataCurrentSeasonDF.await()
        }
        val userId = userResponse.user.userId
        val seasonID = dataCurrentSeason.seasonID
        val userStatsResponses = coroutineScope {
            val list = CopyOnWriteArrayList<UserStatsResponse>()
            for (i in 1..<seasonID) {
                ioAsync {
                    // TODO 缓存来自底层 在没有缓存的情况下会同时发送大量请求
                    val resp = EternalReturnOpenApiClient.getUserStats(userId, i, MatchingMode.Rank)
                    list.add(resp)
                }
            }
            list
        }

        val oldNames = mutableSetOf<String>()
        for (response in userStatsResponses) {
            response.userStats.firstOrNull()?.nickname?.let { oldName ->
                if (!oldName.equals(nickname, true)) {
                    oldNames.add(oldName)
                }
            }
        }
        if (oldNames.isEmpty()) {
            throw MessageReplyException(BotReply.Text("没有找到该玩家之前的昵称"))
        }
        return EternalReturnOldName(
            nickname,
            oldNames.toList()
        )

    }
}
