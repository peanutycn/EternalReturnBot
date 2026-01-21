package cn.luorenmu.request.api.entity.module

import cn.luorenmu.request.api.entity.response.dakgg.DakGGCharacterImgType

/**
 *
 * @author LoMu
 * Date 2025/11/4 23:06
 */
enum class ImageResourcesType(val path: String, val fileType: String) {
    /**
     * ID
     */
    Weapon("/weapon/", ".png"),

    /**
     * ID
     */
    TierFull("/tier/full/", ".png"),

    /**
     * ID
     */
    TierRound("/tier/round/", ".png"),

    /**
     * ID-SKIN_ID
     */
    Character("/character/", ".png"),

    /**
     * ID
     */
    Item("/item/", ".png"),

    /**
     * ID
     */
    ItemBg("/item/bg/", ".svg"),

    /**
     * ID
     */
    TacticalSkill("/tactical/skill/", ".png"),

    /**
     * ID
     */
    TraitSkill("/trait/skill/", ".png"),

    /**
     * NULL
     */
    TraitSkillGroupPlaceholder("/trait/group/wilson", ".png"),

    /**
     * ID
     */
    TraitSkillGroup("/trait/group/", ".png");


    fun getGeneralPath(name: String): String {
        return "/resources/images${this.path}${name}${this.fileType}"
    }

    companion object {

        const val TRAIT_SKILL_GROUP_PLACEHOLDER_WILSON_URL =
            "//cdn.dak.gg/er/images/common/img-placeholder-wilson-round.png"

        fun getCharacterPath(
            characterId: Int,
            skinId: Long,
            imageType: DakGGCharacterImgType,
        ): String {
            return "/resources/images${Character.path}${characterId}/${imageType.value}/${skinId}${Character.fileType}"
        }
    }
}
