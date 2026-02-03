package cn.luorenmu.config

import cn.luorenmu.common.util.PathUtils
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Centralized runtime configuration loader.
 *
 * Priority:
 * 1) System property: -Dlomu.config.file=...
 * 2) Env var: LOMU_CONFIG_FILE=...
 * 3) <jarDir>/config/application.conf
 * 4) <jarDir>/application.conf
 * 5) classpath application.conf (fat-jar embedded)
 */
object AppConfig {
    private val log = KotlinLogging.logger { }

    private val typesafeConfig: Config by lazy {
        loadConfig()
    }

    val server: Server by lazy {
        val host = getString("lomu.server.host") ?: "0.0.0.0"
        val port = getInt("lomu.server.port") ?: 8080
        Server(host = host, port = port)
    }

    val oneBot: OneBot by lazy {
        OneBot(
            mode = getString("lomu.onebot.mode")?.trim()?.takeIf { it.isNotBlank() } ?: "auto",
            botUniqueId = getString("lomu.onebot.botUniqueId"),
            apiServerHost = getString("lomu.onebot.apiServerHost") ?: "",
            eventServerHost = getString("lomu.onebot.eventServerHost") ?: "ws://127.0.0.1:3001",
            accessToken = getString("lomu.onebot.accessToken")?.trim()?.takeIf { it.isNotBlank() },
            ackEmojiEnabled = getBoolean("lomu.onebot.ackEmoji.enable") ?: true,
            ackEmojiId = getString("lomu.onebot.ackEmoji.emojiId")?.trim()?.takeIf { it.isNotBlank() } ?: "124",
        )
    }

    val bser: Bser by lazy {
        val keyFromEnv =
            System.getenv("BSER_OPEN_API_KEY")
                ?: System.getenv("LOMU_BSER_OPEN_API_KEY")

        val keyFromConfig = getString("lomu.bser.openApiKey")
        val keyFromFile = getString("lomu.bser.openApiKeyFile")
            ?.let { resolvePath(it) }
            ?.takeIf { it.exists() && it.isRegularFile() }
            ?.readText()
            ?.trim()

        val key = (keyFromConfig ?: keyFromFile ?: keyFromEnv)?.trim()?.takeIf { it.isNotBlank() }
        if (key == null) {
            log.warn {
                "Eternal Return OpenAPI Key is not configured. " +
                    "Some commands will be unavailable until you set lomu.bser.openApiKey / lomu.bser.openApiKeyFile / BSER_OPEN_API_KEY."
            }
        }
        Bser(openApiKey = key)
    }

    val playwright: Playwright by lazy {
        val headless = getBoolean("lomu.playwright.headless") ?: true
        val poolSize = (getInt("lomu.playwright.poolSize") ?: 1).coerceAtLeast(1)
        Playwright(headless = headless, poolSize = poolSize)
    }

    val http: Http by lazy {
        Http(
            proxyUrl = getString("lomu.http.proxy.url")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    val render: Render by lazy {
        val maxMatches = (getInt("lomu.render.maxMatches") ?: 20).coerceAtLeast(1)
        val teammateMatches = (getInt("lomu.render.teammateMatches") ?: 2).coerceIn(0, 10)
        Render(maxMatches = maxMatches, teammateMatches = teammateMatches)
    }

    val help: Help by lazy {
        Help(
            onJoinEnabled = getBoolean("lomu.help.onJoin.enable") ?: true,
            onJoinImageEnabled = getBoolean("lomu.help.onJoin.imageEnable") ?: true,
        )
    }

    val resources: Resources by lazy {
        val downloadConcurrency = (getInt("lomu.resources.downloadConcurrency") ?: 16).coerceIn(1, 128)
        Resources(downloadConcurrency = downloadConcurrency)
    }

    val alias: Alias by lazy {
        Alias(
            superAdmins = getStringList("lomu.alias.superAdmins"),
            enableOneBotRoleCheck = getBoolean("lomu.alias.enableOneBotRoleCheck") ?: true,
            allowGroupAdminGlobal = getBoolean("lomu.alias.allowGroupAdminGlobal") ?: true,
            storeDir = getString("lomu.alias.storeDir")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    val alert: Alert by lazy {
        Alert(
            enable = getBoolean("lomu.alert.enable") ?: true,
            cooldownSeconds = (getInt("lomu.alert.cooldownSeconds") ?: 60).coerceIn(0, 3600),
            maxStackTraceLines = (getInt("lomu.alert.maxStackTraceLines") ?: 12).coerceIn(0, 100),
            maxMessageChars = (getInt("lomu.alert.maxMessageChars") ?: 1800).coerceIn(200, 5000),
            forwardPrivateEnabled = getBoolean("lomu.alert.forwardPrivate.enable") ?: false,
            forwardGroupInviteEnabled = getBoolean("lomu.alert.forwardGroupInvite.enable") ?: true,
            forwardAliasRequestsEnabled = getBoolean("lomu.alert.aliasRequest.enable") ?: true,
        )
    }

    val broadcast: Broadcast by lazy {
        Broadcast(
            enable = getBoolean("lomu.broadcast.enable") ?: false,
            groupBlacklist = getStringList("lomu.broadcast.groupBlacklist"),
            confirmTtlSeconds = (getInt("lomu.broadcast.confirmTtlSeconds") ?: 60).coerceIn(10, 600),
            sendDelayMs = (getInt("lomu.broadcast.sendDelayMs") ?: 150).coerceIn(0, 2_000),
        )
    }

    data class Server(
        val host: String,
        val port: Int,
    )

    data class OneBot(
        val mode: String,
        val botUniqueId: String?,
        val apiServerHost: String,
        val eventServerHost: String,
        val accessToken: String?,
        val ackEmojiEnabled: Boolean,
        val ackEmojiId: String,
    )

    data class Bser(
        val openApiKey: String?,
    )

    data class Playwright(
        val headless: Boolean,
        val poolSize: Int,
    )

    data class Http(
        val proxyUrl: String?,
    )

    data class Render(
        val maxMatches: Int,
        val teammateMatches: Int,
    )

    data class Help(
        val onJoinEnabled: Boolean,
        val onJoinImageEnabled: Boolean,
    )

    data class Resources(
        val downloadConcurrency: Int,
    )

    data class Alias(
        val superAdmins: List<String>,
        val enableOneBotRoleCheck: Boolean,
        val allowGroupAdminGlobal: Boolean,
        val storeDir: String?,
    )

    data class Alert(
        val enable: Boolean,
        val cooldownSeconds: Int,
        val maxStackTraceLines: Int,
        val maxMessageChars: Int,
        val forwardPrivateEnabled: Boolean,
        val forwardGroupInviteEnabled: Boolean,
        val forwardAliasRequestsEnabled: Boolean,
    )

    data class Broadcast(
        val enable: Boolean,
        val groupBlacklist: List<String>,
        val confirmTtlSeconds: Int,
        val sendDelayMs: Int,
    )

    private fun loadConfig(): Config {
        val fileFromProp = System.getProperty("lomu.config.file")?.takeIf { it.isNotBlank() }
        val fileFromEnv = System.getenv("LOMU_CONFIG_FILE")?.takeIf { it.isNotBlank() }

        val jarDir = PathUtils.currentDirectory
        val defaultCandidates = listOf(
            jarDir.resolve("config/application.conf"),
            jarDir.resolve("application.conf"),
        )

        var candidate = when {
            fileFromProp != null -> resolvePath(fileFromProp)
            fileFromEnv != null -> resolvePath(fileFromEnv)
            else -> defaultCandidates.firstOrNull { it.exists() && it.isRegularFile() }
        }

        val base = ConfigFactory.load()

        if (candidate == null) {
            candidate = generateConfigTemplateIfMissing(jarDir, base)
        }

        if (candidate == null) {
            log.info { "Config file not found on disk, using classpath application.conf" }
            return base.resolve()
        }

        val parsed = ConfigFactory.parseFile(File(candidate.toUri()))
        log.info { "Using config file: $candidate" }
        return parsed.withFallback(base).resolve()
    }

    private fun generateConfigTemplateIfMissing(jarDir: Path, base: Config): Path? {
        val target = jarDir.resolve("config/application.conf")
        return try {
            if (target.exists() && target.isRegularFile()) return target
            val content =
                this::class.java.classLoader
                    .getResourceAsStream("application.conf")
                    ?.bufferedReader()
                    ?.readText()
                    ?: return null
            target.parent?.toFile()?.mkdirs()
            target.writeText(content)
            log.info { "Generated config template: $target" }
            target
        } catch (e: Exception) {
            log.warn(e) { "Failed to generate config template at $target, falling back to classpath config." }
            null
        }
    }

    private fun resolvePath(input: String) = run {
        val raw = input.trim()
        val p = java.nio.file.Path.of(raw)
        if (p.isAbsolute) p else PathUtils.currentDirectory.resolve(raw)
    }

    private fun getString(path: String): String? =
        if (typesafeConfig.hasPath(path)) typesafeConfig.getString(path) else null

    private fun getInt(path: String): Int? =
        if (typesafeConfig.hasPath(path)) typesafeConfig.getInt(path) else null

    private fun getBoolean(path: String): Boolean? =
        if (typesafeConfig.hasPath(path)) typesafeConfig.getBoolean(path) else null

    private fun getStringList(path: String): List<String> =
        if (typesafeConfig.hasPath(path)) typesafeConfig.getStringList(path) else emptyList()
}
