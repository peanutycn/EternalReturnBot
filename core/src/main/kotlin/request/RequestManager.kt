package cn.luorenmu.request

import cn.luorenmu.common.util.HttpProxyUtil
import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.common.util.StringLockUtil.withKeyLock
import cn.luorenmu.exception.ForbiddenException
import cn.luorenmu.request.api.Api
import cn.luorenmu.request.api.EternalReturnOpenApi
import cn.luorenmu.request.api.PakeResourceApi
import cn.luorenmu.request.api.ResourceApi
import cn.luorenmu.request.api.entity.module.CacheTime
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.cache.storage.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 *
 * @author LoMu
 * Date 2025/10/25 16:30
 */
object RequestManager {
    private val log = KotlinLogging.logger {}

    /**
     * public 用于在赛季更新时清空缓存
     */
    public val cacheMap = run {
        val map = mutableMapOf<CacheTime, Cache<String, HttpResponse>>()
        for (time in CacheTime.entries) {
            map[time] = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(time.duration, time.unit)
                .build()
        }
        map
    }

    private val client = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 1000
            endpoint {
                maxConnectionsPerRoute = 100
                pipelineMaxSize = 20
                keepAliveTime = 5000
                connectTimeout = 10_000
                connectAttempts = 3
            }
            HttpProxyUtil.applyTo(this)

        }
        install(HttpRequestRetry.Plugin) {
            maxRetries = 5
            retryOnServerErrors(maxRetries = 3)

            retryOnExceptionIf { request, cause ->
                when (cause) {
                    is SocketTimeoutException,
                    is ConnectTimeoutException,
                    is ClientRequestException,
                    is java.net.ConnectException,
                    is HttpRequestTimeoutException,
                        -> true

                    else -> false
                }
            }
            exponentialDelay()
            delayMillis { retry ->
                retry * 1000L
            }

        }
        install(HttpCache.Companion) {
            val file = PathUtils.resourcesPathResolve("cache").toFile()
            if (!file.exists()) {
                file.mkdirs()
            }
            publicStorage(FileStorage(file))
        }
        install(HttpTimeout.Plugin) {
            requestTimeoutMillis = 10000
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true  // 忽略 JSON 中的未知字段
                isLenient = true          // 允许非标准 JSON
                encodeDefaults = true     // 编码默认值
                explicitNulls = false     // 不编码 null 值
                coerceInputValues = true  // 强制转换输入值
                allowStructuredMapKeys = true // 允许结构化 Map 键
            })
        }
        install(HttpCache) {
            val resourcesPathResolve = PathUtils.resourcesPathResolve("cache")
            if (!resourcesPathResolve.toFile().exists()) {
                Files.createDirectory(resourcesPathResolve)
            }
            val fileDirectory = resourcesPathResolve.toFile()
            publicStorage(FileStorage(fileDirectory))
        }

        defaultRequest {
            header(
                HttpHeaders.UserAgent,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
            )
        }
    }

    private suspend fun executeRequest(api: Api): HttpResponse {
        val requestUrl = (api.baseUrl + api.url).let {
            // Support scheme-relative URLs like //cdn.example.com/...
            if (it.startsWith("//")) "https:$it" else it
        }
        return client.request {
            url(requestUrl)
            method = api.method
            if (api.body.isNotEmpty()) {
                setBody(api.body)
            }
            api.headers.let { reqHeaders ->
                reqHeaders.forEach { h ->
                    header(h.key, h.value)
                }
            }
        }
    }

    suspend fun call(api: Api): HttpResponse {
        log.debug { "call ${api.method.value} ${api.baseUrl}${api.url}" }
        if (api !is ResourceApi && api.cacheTime != CacheTime.NULL) {
            if (api.method == HttpMethod.Get) {
                val cache = cacheMap[api.cacheTime]!!
                cache.getIfPresent(api.url)?.let {
                    log.debug { "Cache hit for ${api.url}" }
                    return it
                }
            }
        }
        val response = withKeyLock(api.url) {
            val currentTimeMillis = System.currentTimeMillis()
            try {
                return@withKeyLock executeRequest(api)
            } finally {
                if (api is ResourceApi) {
                    log.debug { "call stream file: ${api.path} from ${api.baseUrl}${api.url}" }
                } else {
                    log.debug { "call ${api.method.value} ${api.baseUrl}${api.url} take ${(System.currentTimeMillis() - currentTimeMillis) / 1000.0}m" }
                }
            }
        }
        if (api is EternalReturnOpenApi) {
            val jsonObject = response.body<JsonObject>().jsonObject
            jsonObject["message"]?.jsonPrimitive?.content?.let {
                if (it == "Too Many Requests") {
                    log.debug { "EternalReturnOpenApi Too Many Requests retry ${api.url}" }
                    delay(Random.nextLong(1000, 3000))
                    return call(api)
                }
                if (it == "Forbidden") {
                    log.debug { "EternalReturnOpenApi Forbidden ${api.url}" }
                    throw ForbiddenException()
                }
            }
        }
        if (response.status.value in 200..207) {
            log.debug { "call success ${api.baseUrl}${api.url} => ${response.status.value}" }
            if (api.method == HttpMethod.Get) {
                cacheMap[api.cacheTime]!!.put(api.url, response)
            }
        }

        return response
    }

    suspend fun callStream(api: PakeResourceApi, path: Path) {
        val file = path.toFile()
        if (file.exists() && file.length() > 0) {
            return
        }
        try {
            file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }
            val response = call(api)

            val readBytes = response.readBytes()
            FileOutputStream(file).use { output ->
                output.write(readBytes)
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to download file: $path from ${api.baseUrl}${api.url} => $e" }
            if (file.exists() && file.length() == 0L) {
                file.delete()
            }
            throw e
        }
    }
}
