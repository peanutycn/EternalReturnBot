package cn.luorenmu.request.api.entity.response.dakgg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DakGGMatchesResponse(
    val meta: Meta = Meta(),
    val matches: List<Match> = emptyList(),
) {
    @Serializable
    data class Meta(
        val season: String = "",
        val matchingMode: String = "",
        val teamMode: String = "",
        val page: Int = 0,
        val count: Int = 0,
    )

    @Serializable
    data class Match(
        @SerialName("equipment")
        private val equipmentRaw: JsonElement? = null,
        @SerialName("equipmentGrade")
        private val equipmentGradeRaw: JsonElement? = null,
        val userNum: Long = 0,
        val nickname: String = "",
        val gameId: Long = 0,
        val seasonId: Long = 0,
        val matchingMode: Int = 0,
        val matchingTeamMode: Long = 0,
        val teamNumber: Long = 0,
        val characterNum: Long = 0,
        val skinCode: Long = 0,
        val characterLevel: Long = 0,
        val squadRumbleRank: Int = 0,
        val gameRank: Int = 0,
        val escapeState: Int = 0,
        val playerKill: Int = 0,
        val playerDeaths: Int = 0,
        val playerAssistant: Int = 0,
        val monsterKill: Long = 0,
        val mmrBefore: Int = 0,
        val mmrAfter: Int = 0,
        val mmrGain: Int = 0,
        val bestWeapon: Int = 0,
        val versionMajor: Long = 0,
        val versionMinor: Long = 0,
        val serverName: String = "",
        val startDtm: String = "",
        val duration: Long = 0,
        val routeIdOfStart: Long = 0,
        val teamKill: Int = 0,
        val traitFirstCore: Long = 0,
        val traitSecondSub: List<Long> = emptyList(),
        val tacticalSkillGroup: Long = 0,
        val damageToPlayer: Long = 0,
    ) {
        val equipment: Map<Int, Int>
            get() = equipmentRaw.toIndexedIntMap()

        val equipmentGrade: Map<Int, Int>
            get() = equipmentGradeRaw.toIndexedIntMap()

        private fun JsonElement?.toIndexedIntMap(): Map<Int, Int> {
            if (this == null) return emptyMap()
            return when (this) {
                is JsonArray -> asJsonArrayToMap(this)
                is JsonObject -> asJsonObjectToMap(this)
                is JsonPrimitive -> emptyMap()
                else -> emptyMap()
            }
        }

        private fun asJsonArrayToMap(arr: JsonArray): Map<Int, Int> =
            arr.mapIndexedNotNull { index, el ->
                val v = (el as? JsonPrimitive)?.let { it.safeInt() } ?: return@mapIndexedNotNull null
                index to v
            }.toMap()

        private fun asJsonObjectToMap(obj: JsonObject): Map<Int, Int> =
            obj.entries
                .mapNotNull { (k, v) ->
                    val idx = k.toIntOrNull() ?: return@mapNotNull null
                    val item = (v as? JsonPrimitive)?.let { it.safeInt() } ?: return@mapNotNull null
                    idx to item
                }
                .sortedBy { it.first }
                .toMap()

        private fun JsonPrimitive.safeInt(): Int? = this.content.toIntOrNull()
    }
}
