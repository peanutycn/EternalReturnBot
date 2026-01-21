package cn.luorenmu.service.entity

import cn.luorenmu.HTTP_SERVER_URL
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.pow

/**
 * @author LoMu
 * Date 2025.03.29 15:08
 */
data class EternalReturnPlayRender(
    val nickName: String = "螺母",
    val level: Int = 1,
    val data: EternalReturnPlayerData,
    val profileImageUrl: String? = null,
    val seasonPlayTimeText: String = "",
    val recentPlayers: List<EternalReturnPlayerRecentPlay>,
    val characterUseStats: List<EternalReturnCharacterUseStats>,
    var rating: String? = null,
    val mmrStats: EternalReturnPlayerMMRStats? = null,
    var matches: List<EternalReturnPlayerMatchData> = mutableListOf(),
    val season: String,
    val httpServer: String = HTTP_SERVER_URL,
    val mode :String = "排位"
) {


    data class EternalReturnPlayerMMRStats(
        val mmrDate: List<String>,
        val mmr: List<Int>,
    ) {
        val mmrDateJson = Json.Default.encodeToString(mmrDate)
        val mmrJson: String = Json.Default.encodeToString(mmr)

        val chart: MmrChart by lazy { MmrChart.build(mmrDate, mmr) }

        data class MmrChart(
            val width: Int,
            val height: Int,
            val paddingLeft: Int,
            val paddingRight: Int,
            val paddingTop: Int,
            val paddingBottom: Int,
            val points: String,
            val circles: List<Point>,
            val yTicks: List<YTick>,
            val xLabels: List<XLabel>,
        ) {
            data class Point(val x: Int, val y: Int)
            data class YTick(val y: Int, val label: Int)
            data class XLabel(val x: Int, val label: String)

            companion object {
                fun build(labels: List<String>, values: List<Int>): MmrChart {
                    val width = 320
                    val height = 130
                    // Leave enough room for 4-5 digit Y labels in SVG.
                    val paddingLeft = 56
                    val paddingRight = 12
                    val paddingTop = 10
                    val paddingBottom = 22

                    val n = minOf(labels.size, values.size).coerceAtLeast(0)
                    if (n <= 0) {
                        return MmrChart(
                            width,
                            height,
                            paddingLeft,
                            paddingRight,
                            paddingTop,
                            paddingBottom,
                            points = "",
                            circles = emptyList(),
                            yTicks = emptyList(),
                            xLabels = emptyList(),
                        )
                    }

                    val plotW = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                    val plotH = (height - paddingTop - paddingBottom).coerceAtLeast(1)

                    val ys = values.take(n)
                    val rawMin = ys.minOrNull() ?: 0
                    val rawMax = ys.maxOrNull() ?: 0

                    fun niceStep(step: Double): Int {
                        val abs = kotlin.math.abs(step)
                        if (!abs.isFinite() || abs <= 0.0) return 1
                        val exp = kotlin.math.floor(kotlin.math.log10(abs)).toInt()
                        val base = 10.0.pow(exp.toDouble())
                        val f = abs / base
                        val nice = when {
                            f <= 1 -> 1.0
                            f <= 2 -> 2.0
                            f <= 5 -> 5.0
                            else -> 10.0
                        }
                        return (nice * base).toInt().coerceAtLeast(1)
                    }

                    val span = (rawMax - rawMin).coerceAtLeast(1)
                    val step = niceStep(span / 3.0)
                    val minY = kotlin.math.floor(rawMin.toDouble() / step) * step
                    val maxY = kotlin.math.ceil(rawMax.toDouble() / step) * step
                    val rangeY = (maxY - minY).toInt().coerceAtLeast(1)

                    fun xAt(i: Int): Int {
                        if (n <= 1) return paddingLeft + plotW / 2
                        return paddingLeft + ((plotW.toDouble() * i) / (n - 1)).toInt()
                    }

                    fun yAt(v: Int): Int {
                        if (maxY == minY) return paddingTop + plotH / 2
                        val t = (v - minY) / (maxY - minY)
                        return (paddingTop + (1.0 - t) * plotH).toInt()
                    }

                    val circles = ys.mapIndexed { i, v -> Point(xAt(i), yAt(v)) }
                    val points = circles.joinToString(" ") { "${it.x},${it.y}" }

                    val yTicks = buildList {
                        var v = minY.toInt()
                        while (v <= maxY.toInt()) {
                            add(YTick(y = yAt(v), label = v))
                            v += step
                        }
                    }

                    val maxXLabels = 7
                    val stepX = kotlin.math.ceil(n.toDouble() / maxXLabels).toInt().coerceAtLeast(1)
                    val xLabels = buildList {
                        var i = 0
                        while (i < n) {
                            add(XLabel(x = xAt(i), label = labels[i]))
                            i += stepX
                        }
                        if (n > 1 && (n - 1) % stepX != 0) {
                            add(XLabel(x = xAt(n - 1), label = labels[n - 1]))
                        }
                    }

                    return MmrChart(
                        width,
                        height,
                        paddingLeft,
                        paddingRight,
                        paddingTop,
                        paddingBottom,
                        points,
                        circles,
                        yTicks,
                        xLabels,
                    )
                }
            }
        }
    }

    data class EternalReturnCharacterUseStats(
        val imgUrl: String,
        val characterName: String,
        val characterPlay: Int,
        val winRate: String,
        val getRP: Int,
        val avgRank: String,
        val avgDmg: Int,
    )

    data class EternalReturnPlayerData(
        var rp: String = "段位鉴定中.",
        var rpName: String = "",
        var tierImageUrl: String = "",
        // Optional rank badges (global/local/in1000). Empty means "not available".
        var globalRankText: String = "",
        var localRankText: String = "",
        var in1000RankText: String = "",
        var play: Int = 0,
        var avgTk: String = "-",
        var avgKill: String = "-",
        var avgRank: String = "-",
        var avgAssists: String = "-",
        var avgDmg: String = "-",
        var top1: String = "-",
        var top2: String = "-",
        var top3: String = "-",
    )

    data class EternalReturnPlayerRecentPlay(
        var imageWrapperUrl: String = "",
        var plays: Int = 1,
        var winRate: String = "0.00%",
        var avgRank: String = "0.00%",
        var nickname: String = "",
        var characterName: String = "",
    )


    data class EternalReturnPlayerMatchData(
        val serverName: String = "",
        val nickName: String = "螺母",
        val characterName: String = "螺母",
        val rank: Int = 8,
        val matchingModeId: Int = 0,
        val type: String = "排位",
        val dateHour: String = "",
        val dateMonth: String = "",
        val characterAvatarUrl: String = "",
        val weaponUrl: String = "",
        val traitSkillGroupUrl: String = "",
        val tacticalSkillUrl: String = "",
        val traitSkillUrl: String = "",
        val kill: Int = 0,
        val assist: Int = 0,
        val kda: Double = 0.00,
        val dmg: Long = 0,
        val tk: Int = 0,
        val rpChange: Int = 0,
        val rp: Int = 0,
        val rpSvgUrl: String = "",
        val routeId: String = "Private",
        val equips: MutableList<EternalReturnEquip> = mutableListOf(),
        val gameId: String = "0",
        val version: String = "",
        val teamMates: List<EternalReturnTeammate>? = null,
    ) {
        data class EternalReturnTeammate(
            var nickName: String = "",
            var avatarUrl: String = "",
            var rp: String = "0",
            var rpImageUrl: String = "",
            var tk: Int = 0,
            var kill: Int = 0,
            var assist: Int = 0,
            var dmg: Int = 0,
            var weaponUrl: String = "",
            var skillUrl: String = "",
            var traitSkillGroupUrl: String = "",
            var traitSkillUrl: String = "",
            var equips: MutableList<EternalReturnEquip> = mutableListOf(),
        )
    }

}
