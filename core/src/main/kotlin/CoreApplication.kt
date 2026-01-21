package cn.luorenmu


import cn.luorenmu.api.resourcesRouting
import cn.luorenmu.common.util.BrowserPool
import cn.luorenmu.config.AppConfig
import cn.luorenmu.service.EternalReturnRenderService
import cn.luorenmu.service.ResourcesDownloadService
import cn.luorenmu.service.OneBotRoleService
import cn.luorenmu.service.AliasService
import freemarker.cache.ClassTemplateLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.routing.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/**
 *
 * @author LoMu
 * Date 2025/9/17 12:47
 *
 */
class CoreApplication

public val apiKey: MutableMap<String, String> by lazy {
    mutableMapOf<String, String>().apply {
        AppConfig.bser.openApiKey?.let { put("x-api-key", it) }
    }
}

var SERVER_PORT: Int = 8080
var HTTP_SERVER_URL = "http://127.0.0.1:${SERVER_PORT}"

enum class Adapter {
    ONE_BOT, QG_BOT
}

lateinit var currentAdapter: Adapter

private val log = KotlinLogging.logger {}
fun Application.moduleCore(adapter: Adapter) {
    currentAdapter = adapter
    configureRouting()
    configureInstall()
    log.info("正在启动 PlayWright")
    BrowserPool.getBrowser()
    log.info("PlayWright 已启动")
    SERVER_PORT = AppConfig.server.port
    HTTP_SERVER_URL = "http://127.0.0.1:$SERVER_PORT"
}


val appModule = module {

    single { ResourcesDownloadService() }
    single { EternalReturnRenderService() }
    single { OneBotRoleService() }
    single { AliasService() }
}

fun Application.configureInstall() {
    install(FreeMarker) {
        templateLoader = ClassTemplateLoader(this::class.java.classLoader, "static/templates")
    }

    install(Koin) {
        modules(appModule)
    }

}


fun Application.configureRouting() {
    routing {
        resourcesRouting()
    }
}
