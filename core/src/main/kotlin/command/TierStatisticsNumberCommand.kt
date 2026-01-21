package cn.luorenmu.command

import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.render.FreemarkerRenderer
import cn.luorenmu.request.api.Api.Companion.ioAsync
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.entity.module.DakGGServerName
import cn.luorenmu.request.entity.module.DakGGTeamMode
import cn.luorenmu.service.EternalReturnRenderService
import cn.luorenmu.service.ResourcesDownloadService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import org.koin.java.KoinJavaComponent.inject

/**
 *
 * @author LoMu
 * Date 2025/11/1 00:39
 */
@BotCommand("tier", "段位统计", "<server>")
class TierStatisticsNumberCommand : CommandEvent {
    private val log = KotlinLogging.logger {}
    private val resourcesDownloadService: ResourcesDownloadService by inject(ResourcesDownloadService::class.java)
    private val eternalReturnRenderService: EternalReturnRenderService by inject(
        EternalReturnRenderService::class.java
    )

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val serverName = command["server"]?.let { DakGGServerName.convert(it) } ?: DakGGServerName.Asia
        preheatRequest(serverName)
        val cutoffsAndTierNumber = eternalReturnRenderService.getCutoffsAndTierNumber(serverName)
        val outputPath = PathUtils.resourcesPathResolve("render", "tier", "tierStatisticsNumber.png")
        BrowserPool.getBrowser().screenshotContentSelector(
            FreemarkerRenderer.render("tier_statistics_number.ftl", cutoffsAndTierNumber),
            outputPath,
            "#app"
        )
        return BotReply.ImageFile(outputPath.toString())
    }

    private suspend fun preheatRequest(serverName: DakGGServerName) {
        coroutineScope {
            ioAsync {
                EternalReturnDakGGApiClient.getTierDistributions(DakGGTeamMode.Squad).distributions.map { it.tierType }
            }
            ioAsync {
                val type = EternalReturnDakGGApiClient.getDataCurrentSeason().type
                EternalReturnDakGGApiClient.getCutoffsAndLeaderboard(
                    1,
                    type,
                    serverName,
                    DakGGTeamMode.Squad
                ).cutoffs.map { it.tierType }
            }

            val tiers = EternalReturnDakGGApiClient.getTiers()
            resourcesDownloadService.downloadTiers(tiers)
        }
    }
}
