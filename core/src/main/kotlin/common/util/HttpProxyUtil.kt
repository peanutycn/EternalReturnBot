package cn.luorenmu.common.util

import cn.luorenmu.config.AppConfig
import com.microsoft.playwright.options.Proxy
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.http.Url

object HttpProxyUtil {
    fun applyTo(engine: CIOEngineConfig) {
        val proxyUrl = AppConfig.http.proxyUrl?.trim()?.takeIf { it.isNotBlank() } ?: return
        val url = Url(proxyUrl)
        engine.proxy = when (url.protocol.name.lowercase()) {
            "socks", "socks4", "socks4a", "socks5" -> {
                val port = if (url.port > 0) url.port else 1080
                ProxyBuilder.socks(url.host, port)
            }
            else -> ProxyBuilder.http(url)
        }
    }

    fun resolvePlaywrightProxy(): Proxy? {
        val proxyUrl = AppConfig.http.proxyUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return Proxy(proxyUrl)
    }
}
