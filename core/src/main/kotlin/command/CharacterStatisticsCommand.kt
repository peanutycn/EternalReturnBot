package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.request.api.EternalReturnWebUrls
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Paths

@BotCommand(id = "statistics", alias = "实验体统计", value = "")
class CharacterStatisticsCommand : CommandEvent {
    private val log = KotlinLogging.logger {}

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val outputPath = PathUtils.resourcesPathResolve("render", "web", "character_statistics.png")
        val tier = parseTier(sender.plainText) ?: "diamond_plus"
        try {
            BrowserPool.getBrowser().customizeSelector(
                url = EternalReturnWebUrls.statisticsPage(tier = tier),
                selector = "#content-container",
                waitUntilState = WaitUntilState.DOMCONTENTLOADED,
            ) { page: Page, box ->
                page.waitForTimeout(5000.0)
                val scrollHeight = page.evaluate("document.body.scrollHeight").toString().toDoubleOrNull() ?: 2000.0
                val clipHeight = (scrollHeight - 800.0).coerceAtLeast(800.0)
                if (box == null) {
                    page.screenshot(Page.ScreenshotOptions().setPath(outputPath).setFullPage(true))
                } else {
                    page.screenshot(
                        Page.ScreenshotOptions()
                            .setPath(Paths.get(outputPath.toString()))
                            .setFullPage(true)
                            .setClip(box.x, box.y, box.width, clipHeight)
                    )
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to screenshot statistics page" }
            return BotReply.Text("网页截图失败：${e.message ?: "未知错误"}")
        }
        return BotReply.ImageFile(outputPath.toString())
    }

    private fun parseTier(plainText: String): String? {
        val parts = plainText.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size <= 1) return null
        val token = parts[1].trim()
        val s = token.lowercase()
        return when (s) {
            "灭钻", "灭钻+", "灭钻＋", "钻石", "钻石+", "钻石＋", "diamond_plus" -> "diamond_plus"
            "星陨", "星陨+", "星陨＋", "meteorite_plus" -> "meteorite_plus"
            "无暇", "无暇+", "无暇＋", "mithril_plus" -> "mithril_plus"
            "in1000", "in1k", "前1000", "top1000" -> "in1000"
            else -> null
        }
    }
}
