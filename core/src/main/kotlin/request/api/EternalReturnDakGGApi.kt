package cn.luorenmu.request.api

import cn.luorenmu.common.util.toPath
import cn.luorenmu.request.api.entity.module.CacheTime
import cn.luorenmu.request.api.entity.module.ImageResourcesType
import cn.luorenmu.request.api.entity.response.dakgg.DakGGCharacterImgType
import cn.luorenmu.request.entity.module.DakGGServerName
import cn.luorenmu.request.entity.module.DakGGTeamMode
import io.ktor.http.*
import java.net.URLEncoder
import java.nio.file.Path

/**
 *
 * @author LoMu
 * Date 2025/10/31 23:02
 */

/**
 * 部分数据无法通过官方Api获取或官方Api更新不及时的紧急替换
 */
sealed class EternalReturnDakGGApi(
    override var url: String,
    override var method: HttpMethod = HttpMethod.Companion.Get,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val body: MutableMap<String, String> = mutableMapOf(),
    override val cacheTime: CacheTime = CacheTime.NULL,
) : PakeApi(url, method, headers, body, cacheTime) {
    override var baseUrl: String = "https://er.dakgg.io/api"


    sealed class Data(
        url: String,
        method: HttpMethod = HttpMethod.Companion.Get,
        headers: MutableMap<String, String> = mutableMapOf(),
        body: MutableMap<String, String> = mutableMapOf(),
        cacheTime: CacheTime = CacheTime.ONE_WEEK,
    ) : EternalReturnDakGGApi(url, method, headers, body, cacheTime) {

        object GetTiers : Data("/v1/data/tiers?hl=zh_cn")
        object GetGameDataBySeason : Data("/v1/data/seasons?hl=zh_CN")
        object GetCurrentSeason : Data("/v0/current-season?hl=zh_CN")

        object GetCharacters : Data("/v1/data/characters?hl=zh_CN")

        object GetItems : Data("/v1/data/items?hl=zh-cn")

        object GetWeapons : Data("/v1/data/masteries?hl=zh_cn")

        object GetTraitSkills : Data(
            "/v1/data/trait-skills?hl=zh_cn"
        )

        object GetTacticalSkills : Data("/v1/data/tactical-skills?hl=zh_cn")
    }

    sealed class User(
        url: String,
        method: HttpMethod = HttpMethod.Companion.Get,
        headers: MutableMap<String, String> = mutableMapOf(),
        body: MutableMap<String, String> = mutableMapOf(),
        cacheTime: CacheTime = CacheTime.FIVE_MINUTES,
    ) : EternalReturnDakGGApi(url, method, headers, body, cacheTime) {
        /**
         * 获取用户信息、不传入season默认当前赛季
         */
        class GetProfile(nickname: String) :
            User("/v1/players/${URLEncoder.encode(nickname, "UTF-8")}/profile")

        /**
         * Matches list (used as a fallback when OpenAPI key is not configured).
         */
        class GetMatches(
            nickname: String,
            season: String,
            matchingMode: String = "ALL",
            teamMode: String = "ALL",
            page: Int = 1,
        ) : User(
            "/v1/players/${URLEncoder.encode(nickname, "UTF-8")}/matches" +
                "?season=${URLEncoder.encode(season, "UTF-8")}" +
                "&matchingMode=${URLEncoder.encode(matchingMode, "UTF-8")}" +
                "&teamMode=${URLEncoder.encode(teamMode, "UTF-8")}" +
                "&page=${page}" +
                "&hl=zh_CN"
        )

        /**
         * Match detail by game id (used for teammate rendering).
         */
        class GetMatchById(
            nickname: String,
            seasonId: Int,
            gameId: String,
        ) : User(
            "/v1/players/${URLEncoder.encode(nickname, "UTF-8")}/matches/${seasonId}/${URLEncoder.encode(gameId, "UTF-8")}?hl=zh_CN",
            cacheTime = CacheTime.FIVE_MINUTES
        )
    }


    sealed class Leaderboard(
        url: String,
        method: HttpMethod = HttpMethod.Companion.Get,
        headers: MutableMap<String, String> = mutableMapOf(),
        body: MutableMap<String, String> = mutableMapOf(),
        cacheTime: CacheTime = CacheTime.ONE_HOUR,
    ) : EternalReturnDakGGApi(url, method, headers, body, cacheTime) {
        class GetLeaderboard(page: Int, seasonType: String, serverName: DakGGServerName, teamMode: DakGGTeamMode) :
            Leaderboard(
                "/v0/leaderboard?page=${page}&seasonKey=${seasonType}&serverName=${serverName.value}&teamMode=${teamMode.value}&hl=zh_CN"
            )
    }


    sealed class Statistics(
        url: String,
        method: HttpMethod = HttpMethod.Companion.Get,
        headers: MutableMap<String, String> = mutableMapOf(),
        body: MutableMap<String, String> = mutableMapOf(),
        cacheTime: CacheTime = CacheTime.ONE_HOUR,
    ) : EternalReturnDakGGApi(url, method, headers, body, cacheTime) {
        /**
         * 段位统计
         */
        class GetTierDistribution(teamMode: DakGGTeamMode) : Statistics(
            "/v0/statistics/tier-distribution?teamMode=${teamMode.value}&hl=zh_CN"
        )
    }

    sealed class Image(
        url: String,
        path: Path,
        method: HttpMethod = HttpMethod.Companion.Get,
        headers: MutableMap<String, String> = mutableMapOf(),
        body: MutableMap<String, String> = mutableMapOf(),
    ) : PakeResourceApi(url, path, method, headers, body) {
        override var baseUrl: String = ""

        /**
         * 非固定的url数据源，需要单独处理
         * 保证输出路径一直
         */
        class DakGGImageUrlCharacter(
            url: String,
            characterId: Int,
            skinId: Long,
            imageType: DakGGCharacterImgType,
        ) :
            Image(
                url.replace(DakGGCharacterImgType.regex(), imageType.value),
                path = ImageResourcesType.getCharacterPath(characterId, skinId, imageType).toPath()
            )

        class DakGGImageUrlItemBg(
            name: String,
        ) : Image(
            "//cdn.dak.gg/er/images/item/ico-itemgradebg-0${name}.svg",
            path = ImageResourcesType.ItemBg.getGeneralPath(name).toPath()
        )

        class DakGGImageUrlResources(
            url: String,
            imageResourcesType: ImageResourcesType,
            name: String,
        ) : Image(
            url,
            path = imageResourcesType.getGeneralPath(name).toPath()
        )

    }
}
