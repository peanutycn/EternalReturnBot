package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.exception.MessageReplyException
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.entity.module.DakGGServerName
import cn.luorenmu.request.entity.module.DakGGTeamMode

/**
 * Cutoff line query for DemiGod/Eternal.
 */
@BotCommand(id = "cutoffs", alias = "永恒线", value = "<server>")
class CutoffsCommand : CommandEvent {
    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val serverName = command["server"]?.let { DakGGServerName.convert(it) } ?: DakGGServerName.Asia
        val season = EternalReturnDakGGApiClient.getDataCurrentSeason()
        val leaderboard = EternalReturnDakGGApiClient.getCutoffsAndLeaderboard(1, season.type, serverName, DakGGTeamMode.Squad)
        if (leaderboard.cutoffs.isEmpty()) {
            throw MessageReplyException(BotReply.Text("数据收集中..."))
        }

        val (demigod, eternal) = when (leaderboard.cutoffs.size) {
            1 -> leaderboard.cutoffs[0] to leaderboard.cutoffs[0]
            2 -> leaderboard.cutoffs[0] to leaderboard.cutoffs[1]
            else -> leaderboard.cutoffs[0] to leaderboard.cutoffs.last()
        }

        return BotReply.Text(
            "${season.name}（${serverName.value}）\n" +
                "半神线：${demigod.mmr}\n" +
                "永恒线：${eternal.mmr}"
        )
    }
}

