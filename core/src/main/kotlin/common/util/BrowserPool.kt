package cn.luorenmu.common.util

import cn.luorenmu.config.AppConfig
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.BoundingBox
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 *
 * @author LoMu
 * Date 2025/10/25 17:40
 */
object BrowserPool {
    private val log = KotlinLogging.logger { }

    private val webPageScreenshots: CopyOnWriteArrayList<WebPageScreenshot> by lazy {
        val item = CopyOnWriteArrayList<WebPageScreenshot>()
        repeat(AppConfig.playwright.poolSize) {
            item.add(WebPageScreenshot(AppConfig.playwright.headless))
        }
        item
    }

    private val index = AtomicInteger(0)

    fun getBrowser(): WebPageScreenshot {
        val idx = index.getAndUpdate { (it + 1) % webPageScreenshots.size }
        return webPageScreenshots[idx]
    }

    class WebPageScreenshot internal constructor(headless: Boolean) {
        private val headlessFlag = headless
        @Volatile
        private var playwright: Playwright = Playwright.create()
        @Volatile
        private var browser: Browser = launchBrowser()
        @Volatile
        private var context = newContext()
        @Volatile
        private var page = newPage()

        private fun launchBrowser(): Browser {
            val options = BrowserType.LaunchOptions()
                .setHeadless(headlessFlag)
                // Reduce shared memory pressure in container-like environments.
                .setArgs(listOf("--disable-dev-shm-usage"))
            HttpProxyUtil.resolvePlaywrightProxy()?.let { options.setProxy(it) }
            return playwright.chromium().launch(options)
        }

        private fun newContext() = browser.newContext(
            Browser.NewContextOptions()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .setExtraHTTPHeaders(
                    mapOf(
                        "Accept-Language" to "zh-CN,zh;q=0.9",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                    )
                )
        )

        private fun newPage(): Page {
            val p = context.newPage()
            p.onCrash {
                log.warn { "Playwright page crashed, will recreate on next request." }
            }
            return p
        }

        private fun shouldRecover(e: Throwable): Boolean {
            val msg = e.message?.lowercase().orEmpty()
            return msg.contains("page crashed") ||
                msg.contains("browser has been closed") ||
                msg.contains("target closed") ||
                msg.contains("has been closed")
        }

        private fun resetSession(reason: String) {
            log.warn { "Resetting Playwright session: $reason" }
            runCatching { page.close() }
            runCatching { context.close() }
            runCatching { browser.close() }
            runCatching { playwright.close() }

            playwright = Playwright.create()
            browser = launchBrowser()
            context = newContext()
            page = newPage()
        }

        private fun ensureReady() {
            if (page.isClosed) {
                resetSession("page closed")
            }
        }

        private fun <T> runWithRecovery(action: () -> T): T {
            ensureReady()
            return try {
                action()
            } catch (e: PlaywrightException) {
                if (shouldRecover(e)) {
                    resetSession("playwright exception: ${e.message ?: "unknown"}")
                    action()
                } else {
                    throw e
                }
            }
        }

        /**
         * @param url 网页链接
         * @param selector 标签
         * @param waitUntilState 等待规则
         * @param pageConsumer 消费者
         */
        fun customizeSelector(
            url: String,
            selector: String,
            waitUntilState: WaitUntilState = WaitUntilState.DOMCONTENTLOADED,
            pageConsumer: (page: Page, box: BoundingBox?) -> Unit,
        ) {
            synchronized(this) {
                runWithRecovery {
                    page.navigate(url, Page.NavigateOptions().setWaitUntil(waitUntilState).setTimeout(15000.0))
                    val locator = page.locator(selector)
                    val boundingBox = locator.boundingBox()
                    pageConsumer(page, boundingBox)
                }
            }
        }

        fun screenshotSelector(
            url: String,
            output: Path,
            selector: String,
            waitUntilState: WaitUntilState = WaitUntilState.DOMCONTENTLOADED,
            pageConsumer: (page: Page) -> Unit = {},
        ) {
            synchronized(this) {
                runWithRecovery {
                    page.navigate(url, Page.NavigateOptions().setWaitUntil(waitUntilState).setTimeout(15000.0))
                    val locator = page.locator(selector)
                    pageConsumer(page)
                    // Compute bounding box after page customization (e.g. expanding scroll containers).
                    val boundingBox = locator.boundingBox()
                    if (boundingBox == null) {
                        page.screenshot(Page.ScreenshotOptions().setPath(output).setFullPage(true))
                    } else {
                        page.screenshot(
                            Page.ScreenshotOptions().setPath(output)
                                .setFullPage(true)
                                .setClip(boundingBox.x, boundingBox.y, boundingBox.width, boundingBox.height)
                        )
                    }
                }
            }
        }

        fun screenshotContentSelector(
            html: String,
            output: Path,
            selector: String,
        ) {
            synchronized(this) {
                runWithRecovery {
                    page.setContent(
                        html,
                        Page.SetContentOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(15000.0)
                    )
                    // Best-effort wait to reduce flaky rendering (images/charts).
                    runCatching { page.waitForLoadState(LoadState.LOAD) }
                    runCatching {
                        page.waitForFunction(
                            "() => Array.from(document.images || []).every(img => img.complete)",
                            null,
                            Page.WaitForFunctionOptions().setTimeout(8000.0)
                        )
                    }
                    runCatching {
                        page.waitForFunction(
                            "() => { const svg = document.getElementById('rank_svg'); if (svg) return true; const c = document.getElementById('rank_canvas'); if (!c) return true; const s = window.__erbot_rank_chart_status; return s === 'drawn' || s === 'skipped' || s === 'error'; }",
                            null,
                            Page.WaitForFunctionOptions().setTimeout(8000.0)
                        )
                    }
                    runCatching {
                        val status = page.evaluate("window.__erbot_rank_chart_status")?.toString()
                        val canvasW = page.evaluate("document.getElementById('rank_canvas')?.clientWidth")?.toString()
                        val canvasH = page.evaluate("document.getElementById('rank_canvas')?.clientHeight")?.toString()
                        val dataUrlLen = page.evaluate(
                            "(() => { const c=document.getElementById('rank_canvas'); if(!c||!c.toDataURL) return null; return c.toDataURL('image/png').length; })()"
                        )?.toString()
                        val svgW = page.evaluate("document.getElementById('rank_svg')?.getAttribute('width')")?.toString()
                        val svgH = page.evaluate("document.getElementById('rank_svg')?.getAttribute('height')")?.toString()
                        val svgPointsLen = page.evaluate(
                            "document.querySelector('#rank_svg polyline')?.getAttribute('points')?.length"
                        )?.toString()
                        log.debug { "Rank chart status=$status canvas=${canvasW}x${canvasH} dataUrlLen=$dataUrlLen svg=${svgW}x${svgH} svgPointsLen=$svgPointsLen" }
                    }
                    runCatching { page.waitForTimeout(300.0) }
                    val locator = page.locator(selector)
                    val boundingBox = locator.boundingBox()
                    page.screenshot(
                        Page.ScreenshotOptions().setPath(output)
                            .setFullPage(true)
                            .setClip(boundingBox.x, boundingBox.y, boundingBox.width, boundingBox.height)
                    )
                }
            }
        }

        fun screenshot(
            url: String,
            output: Path,
            waitUntilState: WaitUntilState = WaitUntilState.DOMCONTENTLOADED,
            pageConsumer: (page: Page) -> Unit = {},
        ) {
            synchronized(this) {
                runWithRecovery {
                    page.navigate(url, Page.NavigateOptions().setWaitUntil(waitUntilState).setTimeout(15000.0))
                    pageConsumer(page)
                    page.screenshot(
                        Page.ScreenshotOptions().setPath(output)
                            .setFullPage(true)
                    )
                }
            }
        }


    }
}
