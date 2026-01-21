package cn.luorenmu.command

import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.config.AppConfig
import cn.luorenmu.render.FreemarkerRenderer
import cn.luorenmu.request.api.Api.Companion.ioAsync
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.api.EternalReturnOpenApiClient
import cn.luorenmu.request.entity.module.MatchingMode
import cn.luorenmu.service.EternalReturnRenderService
import cn.luorenmu.service.ResourcesDownloadService
import cn.luorenmu.service.AliasService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.java.KoinJavaComponent.inject

/**
 *
 * @author LoMu
 * Date 2025/10/24 14:06
 */

@BotCommand("search", "查询玩家", "<nickname> <mode>")
class SearchPlayer : CommandEvent {

    private val log = KotlinLogging.logger {}
    private val resourcesDownloadService: ResourcesDownloadService by inject(ResourcesDownloadService::class.java)
    private val eternalReturnRenderService: EternalReturnRenderService by inject(
        EternalReturnRenderService::class.java
    )
    private val aliasService: AliasService by inject(AliasService::class.java)

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply {
        if (command.isEmpty() || command["nickname"] == null) {
            return BotReply.Text("请使用命令格式：查询玩家 <名称> [模式数字]（或 /search <名称> [模式数字]）")
        }

        val groupId = sender.groupOpenId
        val inputNickname = command["nickname"]!!
        val nickname = aliasService.resolve(
            AliasService.Namespace.PLAYER,
            groupId = groupId,
            userId = sender.senderOpenId,
            input = inputNickname
        ).value
        val mode = command["mode"]?.toIntOrNull()?.let { MatchingMode.convert(it) } ?: MatchingMode.Rank
        val maxMatches = AppConfig.render.maxMatches
        preheatRequest(nickname, mode, maxMatches)
        val outputPath = PathUtils.resourcesPathResolve("render", "player", "$nickname.png")
        val html =
            FreemarkerRenderer.render(
                "search_player.ftl",
                eternalReturnRenderService.getEternalReturnRender(nickname, mode, maxMatches)
            )
        BrowserPool.getBrowser()
            .screenshotContentSelector(html, outputPath, "#content-container")
        return BotReply.ImageFile(outputPath.toString())
    }

    private suspend fun preheatRequest(nickname: String, mode: MatchingMode, maxMatches: Int) {
        val hasOpenApiKey = !AppConfig.bser.openApiKey.isNullOrBlank()
        kotlinx.coroutines.supervisorScope {
            fun safeLaunch(block: suspend () -> Unit) = ioAsync {
                runCatching { block() }.onFailure { e ->
                    log.debug(e) { "Preheat task failed" }
                }
            }

            if (hasOpenApiKey) {
                safeLaunch {
                    val user = EternalReturnOpenApiClient.getUserNumByUserNickName(nickname)
                    val dataCurrentSeason = EternalReturnOpenApiClient.getDataCurrentSeason()
                    EternalReturnOpenApiClient.getUserStats(
                        user.user.userId,
                        dataCurrentSeason.seasonID,
                        MatchingMode.Rank
                    )
                }
                safeLaunch {
                    val user = EternalReturnOpenApiClient.getUserNumByUserNickName(nickname)
                    val games = EternalReturnOpenApiClient.getGamesByUserNum(user.user.userId)
                    resourcesDownloadService.gameDataDownload(games.userGames.take(maxMatches))
                }
                safeLaunch {
                    // Teammate details are fetched from DakGG match-by-id endpoint.
                    val season = EternalReturnDakGGApiClient.getDataCurrentSeason()
                    val user = EternalReturnOpenApiClient.getUserNumByUserNickName(nickname)
                    val games = EternalReturnOpenApiClient.getGamesByUserNum(user.user.userId)
                    val ids = games.userGames
                        .asSequence()
                        .filter { it.matchingMode == MatchingMode.Rank.value }
                        .take(AppConfig.render.teammateMatches)
                        .map { it.gameId.toString() }
                        .toList()
                    if (ids.isNotEmpty()) {
                        val byIdMatches = coroutineScope {
                            ids.map { gid ->
                                ioAsync { EternalReturnDakGGApiClient.getMatchById(nickname, season.id, gid).matches }
                            }.awaitAll().flatten()
                        }
                        if (byIdMatches.isNotEmpty()) {
                            resourcesDownloadService.gameDataDownloadMatches(byIdMatches)
                        }
                    }
                }
            } else {
                safeLaunch {
                    val season = EternalReturnDakGGApiClient.getDataCurrentSeason()
                    val matches = EternalReturnDakGGApiClient.getMatchesAutoSeason(nickname, season)
                    resourcesDownloadService.gameDataDownloadMatches(matches.matches.take(maxMatches))
                }
                safeLaunch {
                    // Pre-download teammate resources for a few recent ranked matches.
                    val season = EternalReturnDakGGApiClient.getDataCurrentSeason()
                    val matches = EternalReturnDakGGApiClient.getMatchesAutoSeason(nickname, season)
                    val ids = matches.matches
                        .asSequence()
                        .filter { it.matchingMode == MatchingMode.Rank.value }
                        .take(AppConfig.render.teammateMatches)
                        .map { it.gameId.toString() }
                        .toList()
                    if (ids.isNotEmpty()) {
                        val byIdMatches = coroutineScope {
                            ids.map { gid ->
                                ioAsync { EternalReturnDakGGApiClient.getMatchById(nickname, season.id, gid).matches }
                            }.awaitAll().flatten()
                        }
                        if (byIdMatches.isNotEmpty()) {
                            resourcesDownloadService.gameDataDownloadMatches(byIdMatches)
                        }
                    }
                }
            }

            safeLaunch {
                resourcesDownloadService.downloadProfileData(nickname, mode.value)
            }
        }
    }


}
