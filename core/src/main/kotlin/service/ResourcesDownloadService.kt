package cn.luorenmu.service

import cn.luorenmu.config.AppConfig
import cn.luorenmu.request.api.Api.Companion.ioAsync
import cn.luorenmu.request.api.EternalReturnDakGGApi
import cn.luorenmu.request.api.EternalReturnDakGGApiClient
import cn.luorenmu.request.api.entity.module.ImageResourcesType
import cn.luorenmu.request.api.entity.response.dakgg.*
import cn.luorenmu.request.api.entity.response.game.BattleUserGamesResponse.UserGame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 *
 * @author LoMu
 * Date 2025/11/5 12:34
 */
class ResourcesDownloadService {

    private val log = KotlinLogging.logger {}

    private data class GameResourceRef(
        val characterNum: Long,
        val skinCode: Long,
        val bestWeapon: Int,
        val traitFirstCore: Long,
        val traitSecondSub: List<Long>,
        val tacticalSkillGroup: Long,
        val equipmentIds: List<Long>,
        val equipmentGradeIds: List<Int>,
    )

    private fun UserGame.toGameResourceRef(): GameResourceRef =
        GameResourceRef(
            characterNum = characterNum,
            skinCode = skinCode.toLong(),
            bestWeapon = bestWeapon,
            traitFirstCore = traitFirstCore,
            traitSecondSub = traitSecondSub,
            tacticalSkillGroup = tacticalSkillGroup,
            equipmentIds = equipment.values.map { it.toLong() },
            equipmentGradeIds = equipmentGrade.values.toList(),
        )

    private fun DakGGMatchesResponse.Match.toGameResourceRef(): GameResourceRef =
        GameResourceRef(
            characterNum = characterNum,
            skinCode = skinCode,
            bestWeapon = bestWeapon,
            traitFirstCore = traitFirstCore,
            traitSecondSub = traitSecondSub,
            tacticalSkillGroup = tacticalSkillGroup,
            equipmentIds = equipment.values.map { it.toLong() },
            equipmentGradeIds = equipmentGrade.values.toList(),
        )

    private val downloadConcurrency: Int
        get() = AppConfig.resources.downloadConcurrency

    private suspend fun <T> parallelDownload(items: Iterable<T>, block: suspend (T) -> Unit) {
        coroutineScope {
            val semaphore = Semaphore(downloadConcurrency)
            items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        block(item)
                    }
                }
            }.toList().awaitAll()
        }
    }

    /**
     * 物品、装备
     */
    suspend fun downloadItemImage(item: DakGGItemsResponse.Item) {
        EternalReturnDakGGApi.Image.DakGGImageUrlResources(
            item.imageUrl,
            ImageResourcesType.Item,
            item.id.toString()
        ).callStream()
    }

    suspend fun downloadProfileData(nickname: String, matchingModeId: Int) {
        val (profile, charactersResponse) = coroutineScope {
            val profileDF = ioAsync { EternalReturnDakGGApiClient.getProfile(nickname) }
            val charactersDF = ioAsync { EternalReturnDakGGApiClient.getCharacters() }
            profileDF.await() to charactersDF.await()
        }

        val profileOverviewForImage = profile.playerSeasonOverviews.firstOrNull { it.matchingModeId == matchingModeId }
            ?: profile.playerSeasonOverviews.firstOrNull()

        val rankOverview = profile.playerSeasonOverviews.firstOrNull { it.matchingModeId == 3 }
            ?: profileOverviewForImage

        val sidebarCharacterIds = rankOverview
            ?.characterStats
            ?.take(8)
            ?.map { it.key.toLong() }
            ?: emptyList()

        val duoOverview = profile.playerSeasonOverviews.firstOrNull { it.duoStats.isNotEmpty() }
        val duoCharacterIds = duoOverview
            ?.duoStats
            ?.take(8)
            ?.mapNotNull { it.characterStats.firstOrNull()?.key }
            ?: emptyList()

        val neededCharacterIds = (sidebarCharacterIds + duoCharacterIds).distinct()

        // Sidebar/recent-play uses original skin (first skin).
        parallelDownload(neededCharacterIds) { characterId ->
            val characterById = charactersResponse.getCharacterById(characterId)
            val originSkinId = characterById.skins.first().id
            downloadCharacterImage(characterById, originSkinId, setOf(DakGGCharacterImgType.CharProfile))
        }

        // Profile image uses player's current skin when available (CharResult).
        val profileCharacter = profileOverviewForImage?.characterStats?.firstOrNull()
        val profileCharacterId = profileCharacter?.key
        if (profileCharacterId != null) {
            val characterById = charactersResponse.getCharacterById(profileCharacterId)
            val skinId = profileCharacter.skinStats?.firstOrNull()?.key ?: characterById.skins.first().id
            downloadCharacterImage(characterById, skinId, setOf(DakGGCharacterImgType.CharResult))
        }
    }

    /**
     * 武器
     */
    suspend fun downloadWeaponImage(weapon: DakGGWeaponResponse.Weapon) {
        EternalReturnDakGGApi.Image.DakGGImageUrlResources(
            weapon.iconUrl,
            ImageResourcesType.Weapon,
            weapon.id.toString()
        ).callStream()
    }

    /**
     *  召唤师技能
     */
    suspend fun downloadTacticalSkillImage(tacticalSkill: DakGGTacticalSkillResponse.TacticalSkill) {
        EternalReturnDakGGApi.Image.DakGGImageUrlResources(
            tacticalSkill.imageUrl,
            ImageResourcesType.TacticalSkill,
            tacticalSkill.id.toString()
        ).callStream()
    }

    /**
     * 天赋技能
     */
    suspend fun downloadTraitSkillImage(traitSkillId: Long, traitSkills: DakGGTraitSkillsResponse) {
        val traitSkill = traitSkills.traitSkills.first { it.id == traitSkillId }
        val traitSkillGroup = traitSkills.traitSkillGroups.firstOrNull { it.key == traitSkill.group }
        traitSkillGroup?.let {
            EternalReturnDakGGApi.Image.DakGGImageUrlResources(
                traitSkillGroup.imageUrl,
                ImageResourcesType.TraitSkillGroup,
                traitSkillGroup.key
            ).callStream()
        } ?: run {
            EternalReturnDakGGApi.Image.DakGGImageUrlResources(
                ImageResourcesType.TRAIT_SKILL_GROUP_PLACEHOLDER_WILSON_URL,
                ImageResourcesType.TraitSkillGroupPlaceholder,
                ""
            ).callStream()
        }
        EternalReturnDakGGApi.Image.DakGGImageUrlResources(
            traitSkill.imageUrl,
            ImageResourcesType.TraitSkill,
            traitSkillId.toString()
        ).callStream()
    }

    suspend fun downloadCharacterImage(
        character: DakGGCharactersResponse.DakGGCharacterById,
        skinCode: Long,
        types: Set<DakGGCharacterImgType> = setOf(DakGGCharacterImgType.CharProfile),
    ) {

        val characterSkinById = character.getCharacterSkinById(skinCode)
        for (type in types) {
            EternalReturnDakGGApi.Image.DakGGImageUrlCharacter(
                characterSkinById.imageUrl,
                character.id.toInt(),
                characterSkinById.id,
                type
            ).callStream()
        }
    }

    suspend fun downloadTiers(tiers: DakGGTiersResponse) {
        parallelDownload(tiers.tiers.distinctBy { it.id }) { tier ->
            val tierType = tier.id
            EternalReturnDakGGApi.Image.DakGGImageUrlResources(
                url = tier.imageUrl.replace("assets/", "").replace("rank", "tier"),
                ImageResourcesType.TierFull,
                tierType.toString()
            ).callStream()
            EternalReturnDakGGApi.Image.DakGGImageUrlResources(
                tier.iconUrl,
                ImageResourcesType.TierRound,
                tierType.toString()
            ).callStream()
        }
    }

    suspend fun gameDataDownload(games: List<UserGame>) {
        gameDataDownloadInternal(games.map { it.toGameResourceRef() })
    }

    suspend fun gameDataDownloadMatches(matches: List<DakGGMatchesResponse.Match>) {
        gameDataDownloadInternal(matches.map { it.toGameResourceRef() })
    }

    private suspend fun gameDataDownloadInternal(games: List<GameResourceRef>) {
        val (characterResponse, weaponResponse, traitSkillResponse, itemsResponse, tacticalSkillResponse, tiers) =
            coroutineScope {
                val charactersDF = ioAsync { EternalReturnDakGGApiClient.getCharacters() }
                val weaponsDF = ioAsync { EternalReturnDakGGApiClient.getWeapons() }
                val traitSkillsDF = ioAsync { EternalReturnDakGGApiClient.getTraitSkills() }
                val itemsDF = ioAsync { EternalReturnDakGGApiClient.getItems() }
                val tacticalSkillsDF = ioAsync { EternalReturnDakGGApiClient.getTacticalSkills() }
                val tiersDF = ioAsync { EternalReturnDakGGApiClient.getTiers() }
                listOf(
                    charactersDF.await(),
                    weaponsDF.await(),
                    traitSkillsDF.await(),
                    itemsDF.await(),
                    tacticalSkillsDF.await(),
                    tiersDF.await()
                )
            }.let {
                @Suppress("UNCHECKED_CAST")
                Sext(
                    it[0] as DakGGCharactersResponse,
                    it[1] as DakGGWeaponResponse,
                    it[2] as DakGGTraitSkillsResponse,
                    it[3] as DakGGItemsResponse,
                    it[4] as DakGGTacticalSkillResponse,
                    it[5] as DakGGTiersResponse,
                )
            }
        log.debug { "gameDataDownload 数据已收集完毕" }

        val weaponsById = weaponResponse.masteries.associateBy { it.id }
        val itemsById = itemsResponse.items.associateBy { it.id }
        val tacticalById = tacticalSkillResponse.tacticalSkills.associateBy { it.id }

        val characterPairs = games.map { it.characterNum to it.skinCode }.distinct()
        val weaponIds = games.map { it.bestWeapon }.distinct()
        val traitIds = (games.map { it.traitFirstCore } + games.flatMap { it.traitSecondSub }).distinct()
        val equipmentIds = games.flatMap { it.equipmentIds }.distinct()
        val tacticalSkillIds = games.map { it.tacticalSkillGroup }.distinct()
        val itemBgIds = games.flatMap { it.equipmentGradeIds }.distinct()

        log.debug { "gameDataDownload 开始下载段位资源" }
        downloadTiers(tiers)

        // Used in cobalt or fallback cases.
        EternalReturnDakGGApi.Image.DakGGImageUrlResources(
            ImageResourcesType.TRAIT_SKILL_GROUP_PLACEHOLDER_WILSON_URL,
            ImageResourcesType.TraitSkillGroupPlaceholder,
            ""
        ).callStream()

        log.debug { "gameDataDownload 开始下载角色" }
        parallelDownload(characterPairs) { (characterNum, skinCode) ->
            downloadCharacterImage(
                characterResponse.getCharacterById(characterNum),
                skinCode,
                setOf(DakGGCharacterImgType.CharProfile)
            )
        }

        log.debug { "gameDataDownload 开始下载武器" }
        parallelDownload(weaponIds) { weaponId ->
            val weapon = weaponsById[weaponId] ?: error("Weapon not found: $weaponId")
            downloadWeaponImage(weapon)
        }

        log.debug { "gameDataDownload 开始下载天赋" }
        parallelDownload(traitIds) { traitId ->
            downloadTraitSkillImage(traitId, traitSkillResponse)
        }

        log.debug { "gameDataDownload 开始下载装备" }
        parallelDownload(equipmentIds) { equipmentId ->
            val item = itemsById[equipmentId] ?: error("Item not found: $equipmentId")
            downloadItemImage(item)
        }

        log.debug { "gameDataDownload 开始下载战术技能" }
        parallelDownload(tacticalSkillIds) { tacticalSkillId ->
            val tacticalSkill = tacticalById[tacticalSkillId] ?: error("TacticalSkill not found: $tacticalSkillId")
            downloadTacticalSkillImage(tacticalSkill)
        }

        log.debug { "gameDataDownload 开始下载装备背景图片" }
        parallelDownload(itemBgIds) { id ->
            downloadItemBgImage(id)
        }
        log.debug { "gameDataDownload 全部已完成" }
    }

    suspend fun downloadItemBgImage(id: Int) {
        EternalReturnDakGGApi.Image.DakGGImageUrlItemBg(
            id.toString()
        ).callStream()
    }

    private data class Sext<A, B, C, D, E, F>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F,
    )
}
