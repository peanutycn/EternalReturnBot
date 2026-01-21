package cn.luorenmu.request.api.entity.response.dakgg

import kotlinx.serialization.Serializable

/**
 * Match detail response for DakGG endpoint:
 * `/v1/players/{nickname}/matches/{seasonId}/{gameId}`
 */
@Serializable
data class DakGGMatchByIdResponse(
    val matches: List<DakGGMatchesResponse.Match> = emptyList(),
    val playerTiers: List<PlayerTier> = emptyList(),
) {
    @Serializable
    data class PlayerTier(
        val tierId: Int = 0,
        val tierGradeId: Long = 0,
        val tierMmr: Long = 0,
        val mmr: Int = 0,
        val userNum: Int = 0,
    )
}

