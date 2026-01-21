package cn.luorenmu.command

import cn.luorenmu.command.entity.BotReply
import cn.luorenmu.command.entity.MessageSender
import cn.luorenmu.common.annotation.BotCommand
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.request.api.EternalReturnWebUrls
import com.microsoft.playwright.Page
import io.github.oshai.kotlinlogging.KotlinLogging

@BotCommand(id = "routes", alias = "查询路线", value = "<id>")
class RoutesCommand : CommandEvent {
    private val log = KotlinLogging.logger {}

    override suspend fun listen(sender: MessageSender, command: Map<String, String>): BotReply? {
        val id = command["id"]?.trim().orEmpty()
        if (id.isBlank()) return BotReply.Text("用法：查询路线 <路线ID>（或 routes <路线ID>）")
        if (!id.matches(Regex("^\\d{1,8}$"))) return BotReply.Text("路线ID不合法：$id")

        val url = EternalReturnWebUrls.routesPage(id)
        val outputPath = PathUtils.resourcesPathResolve("render", "web", "routes", "${id}.png")

        try {
            BrowserPool.getBrowser().screenshotSelector(
                url = url,
                output = outputPath,
                selector = "#content-container",
            ) { page: Page ->
                page.waitForTimeout(3000.0)
                val finalUrl = page.url()
                if (!finalUrl.contains("/er/routes/$id")) {
                    throw IllegalStateException("未找到该路线")
                }
                // DakGG routes page may render the "advanced items" section inside a scroll container.
                // Expand the container height so screenshot clipping includes the full content.
                runCatching {
                    page.evaluate(
                        """() => {
                            const el = document.querySelector('#content-container');
                            const scroller = el || document.scrollingElement || document.body;
                            scroller.scrollTop = scroller.scrollHeight;
                        }"""
                    )
                }
                page.waitForTimeout(300.0)
                runCatching {
                    page.evaluate(
                        """() => {
                            const el = document.querySelector('#content-container');
                            if (!el) return;
                            const h = el.scrollHeight || el.offsetHeight || 0;
                            if (h > 0) {
                                el.style.height = h + 'px';
                                el.style.overflow = 'visible';
                            }
                        }"""
                    )
                }
                page.waitForTimeout(300.0)
            }
        } catch (e: Exception) {
            log.warn(e) { "Failed to screenshot routes page: $url" }
            return BotReply.Text(e.message ?: "网页截图失败")
        }

        return BotReply.ImageFile(outputPath.toString())
    }
}
