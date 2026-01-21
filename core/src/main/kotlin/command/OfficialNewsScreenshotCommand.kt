package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.request.api.EternalReturnWebUrls
import com.microsoft.playwright.Page
import io.github.oshai.kotlinlogging.KotlinLogging

@BotCommand(id = "news", alias = "官网更新截图", value = "<id>")
class OfficialNewsScreenshotCommand : CommandEvent {
    private val log = KotlinLogging.logger {}

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val id = command["id"]?.trim().orEmpty()
        if (id.isBlank()) return BotReply.Text("用法：官网更新截图 <新闻ID>（或直接发送官网 news 链接）")
        if (!id.matches(Regex("^\\d{4,6}$"))) return BotReply.Text("新闻ID不合法：$id")

        val url = EternalReturnWebUrls.officialNews(id)
        val outputPath = PathUtils.resourcesPathResolve("render", "web", "official_news", "${id}.png")
        try {
            BrowserPool.getBrowser().screenshotSelector(
                url = url,
                output = outputPath,
                selector = "main",
            ) { page: Page ->
                page.waitForTimeout(3000.0)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to screenshot official news: $url" }
            return BotReply.Text("网页截图失败：${e.message ?: "未知错误"}")
        }
        return BotReply.ImageFile(outputPath.toString())
    }
}

