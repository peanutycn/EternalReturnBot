package cn.luorenmu.request.api.entity.response.dakgg

import kotlinx.serialization.Serializable

/**
 *
 * @author LoMu
 * Date 2025/11/4 14:41
 */
@Serializable
data class DakGGCharactersResponse(
    val characters: ArrayList<DakGGCharacterById> = arrayListOf(),
) {
    fun getCharacterSkinById(
        characterId: Long,
        skinId: Long,
    ): DakGGCharacterById.DakGGSkin {
        return this.characters.first { it.id == characterId }.skins.first { it.id == skinId }
    }


    fun getCharacterById(
        characterId: Long,
    ): DakGGCharacterById {
        return this.characters.first { it.id == characterId }
    }

    fun getCharacterByIdOrNull(
        characterId: Long,
    ): DakGGCharacterById? {
        return this.characters.firstOrNull { it.id == characterId }
    }

    @Serializable
    data class DakGGCharacterById(
        val id: Long = 0,
        val key: String = "",
        val name: String = "",
        val imageName: String = "",
        val imageUrl: String = "",
        val communityImageUrl: String = "",
        val weaponTypes: List<DakGGWeaponType> = arrayListOf(),
        val skins: List<DakGGSkin> = arrayListOf(),
    ) {
        fun getCharacterSkinById(skinId: Long): DakGGSkin {
            return this.skins.first { it.id == skinId }
        }
        @Serializable
        data class DakGGSkin(
            val id: Long = 0,
            val name: String = "",
            val grade: Int = 0,
            val imageName: String = "",
            val imageUrl: String = "",

            )

        @Serializable
        data class DakGGWeaponType(
            val id: Long = 0,
            val key: String = "",
        )
    }
}

enum class DakGGCharacterImgType(val value: String) {
    CharProfile("CharProfile"),
    CharResult("CharResult");

    companion object {
        fun regex(): Regex {
            val joinToString = entries.joinToString("|", "(", ")") { it.value }
            return joinToString.toRegex()
        }
    }
}
