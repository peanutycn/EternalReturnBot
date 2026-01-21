package cn.luorenmu.request.api.entity.response.dakgg

import kotlinx.serialization.Serializable

/**
 *
 * @author LoMu
 * Date 2025/11/5 23:05
 */

@Serializable
data class DakGGProfileResponse(
    val meta: ProfileMeta,
    val player: ProfilePlayer,
    val playerSeasonOverviews: List<ProfilePlayerSeasonOverviews> = listOf(),
    val playerSeasons: List<ProfilePlayerSeason> = listOf(),
){

    @Serializable
    data class ProfilePlayerSeason(
        val seasonId: Int = 0,
        var mmr: Int = 0,
        var tierId: Int = 0,
        var tierGradeId: Int = 0,
        var tierMmr: Int = 0,
    )

    @Serializable
    data class ProfileMeta(
        val season: String = "",
    )

    @Serializable
    data class ProfilePlayer(
        val accountLevel: Int = 0,
        val lastPlayedSeasonId: Int = 0,
        val name: String = "",
        val syncedAt: Long = 0,
        val userNum: Long = 0,
    )

    @Serializable
    data class ProfilePlayerSeasonOverviews(
        val userNum: Long = 0,
        val seasonID: Int = 0,
        /**
         * 3为排位模式，2为匹配模式 6为钴协议 0为全部
         */
        val matchingModeId: Int = 0,
        val teamModeId: Int = 0,
        val updatedAt: Long = 0,
        val mmr: Int = 0,
        val play: Int = 0,
        val win: Int = 0,
        val top2: Int = 0,
        val top3: Int = 0,
        val place: Int = 0,
        val playerKill: Int = 0,
        val playerAssistant: Int = 0,
        val teamKill: Int = 0,
        val monsterKill: Int = 0,
        val damageToPlayer: Int = 0,
        val damageToMonster: Int = 0,
        val mmrGain: Int = 0,
        val playTime: Long = 0,
        val playerDeaths: Int = 0,
        val characterStats: List<ProfileStat>,
        // Player's activity by server. Used as supporting info for "local" rank display.
        val serverStats: List<ProfileServerStat> = listOf(),
        val mmrStats: List<List<Int>>,
        val duoStats: List<ProfileDuoStat>,
        val recentMatches: List<RecentGameMatcher>,

        /**
         * Rank info from DakGG profile:
         * - global: global leaderboard position
         * - local:  server-local leaderboard position
         * - in1000: top-1000 segment position (if any)
         */
        val rank: ProfileRank? = null,
    ) {
        @Serializable
        data class ProfileServerStat(
            val key: String = "",
            val updatedAt: Long = 0,
            val play: Long = 0,
        )

        @Serializable
        data class ProfileRank(
            val in1000: ProfileRankGlobal? = null,
            val local: ProfileRankGlobal? = null,
            val global: ProfileRankGlobal? = null,
        )

        @Serializable
        data class ProfileRankGlobal(
            val rank: Long = 0,
            val rankSize: Long = 0,
        )

        @Serializable
        data class ProfileStat(
            val key: Long = 0,
            val updatedAt: Long? = null,
            val play: Int = 0,
            val win: Long = 0,
            val top2: Long = 0,
            val top3: Long = 0,
            val place: Long = 0,
            val playerKill: Long = 0,
            val playerAssistant: Long = 0,
            val teamKill: Long = 0,
            val monsterKill: Long = 0,
            val damageToPlayer: Int = 0,
            val damageToMonster: Long = 0,
            val mmrGain: Int = 0,
            val playTime: Long = 0,
            val playerDeaths: Long = 0,
            val weaponStats: List<ProfileStat>? = null,
            val skinStats: List<ProfileStat>? = null,
        )

        @Serializable
        data class RecentGameMatcher(
            val gameId: Long = 0,
            val seasonId: Int = 0,
            // 3为排位模式，2为匹配模式 6为钴协议 0为全部
            val matchingMode: Int = 0,
            val teamMode: Int = 0,
            val characterNum: Int = 0,
            val skinCode: Int = 0,
            val gameRank: Int = 0,
            val playerKill: Int = 0,
            val playerAssistant: Int = 0,
            val monsterKill: Int = 0,
            val bestWeapon: Int = 0,
            val mmrGain: Int = 0,
            val preMade: Int = 0,
            val damageToPlayer: Int = 0,
            val damageToMonster: Int = 0,
            val giveUp: Int = 0,
            val teamKill: Int = 0,
            val playerDeaths: Int = 0,
            val escapeState: Int = 0,
        )

        @Serializable
        data class ProfileDuoStat(
            val userNum: Long,
            val nickname: String,
            val updatedAt: Long,
            val play: Int,
            val win: Int,
            val place: Int,
            val characterStats: List<ProfileCharacterStat>,
        ) {
            @Serializable
            data class ProfileCharacterStat(
                val key: Long = 0,
                val updatedAt: Long = 0,
                val play: Long = 0,
            )
        }
    }
}
