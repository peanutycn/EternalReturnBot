package cn.luorenmu.service

import cn.luorenmu.common.util.PathUtils
import cn.luorenmu.config.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Alias store with three scopes:
 * - Personal (user-only, independent of group)
 * - Group shared (per group)
 * - Global
 *
 * Namespaces:
 * - player
 * - character
 *
 * Persisted as multiple files under `<jarDir>/data/aliases/`:
 * - global.json
 * - users/<userId>.json
 * - groups/<groupId>.json
 */
class AliasService {
    private val log = KotlinLogging.logger { }
    private val mutex = Mutex()

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val storeDir: Path = resolveStoreDir()
    private val globalPath: Path = storeDir.resolve("global.json")
    private val usersDir: Path = storeDir.resolve("users")
    private val groupsDir: Path = storeDir.resolve("groups")

    init {
        log.info { "Alias store dir: $storeDir" }
    }

    enum class Namespace(val key: String) {
        PLAYER("player"),
        CHARACTER("character"),
    }

    enum class Scope {
        PERSONAL,
        GROUP,
        GLOBAL,
    }

    data class Resolved(
        val value: String,
        val scope: Scope?,
    )

    suspend fun resolve(namespace: Namespace, groupId: String, userId: String, input: String): Resolved {
        val aliasKey = normalizeAlias(input)
        if (aliasKey.isEmpty()) return Resolved(input.trim(), null)
        return mutex.withLock {
            val normalizedUserId = normalizeUserId(userId)
            val normalizedGroupId = normalizeGroupId(groupId)

            val personal = readUserStoreUnsafe(normalizedUserId).namespace(namespace)[aliasKey]
            if (personal != null) return@withLock Resolved(personal.value, Scope.PERSONAL)

            val group = readGroupStoreUnsafe(normalizedGroupId).shared.namespace(namespace)[aliasKey]
            if (group != null) return@withLock Resolved(group.value, Scope.GROUP)

            val global = readGlobalStoreUnsafe().namespace(namespace)[aliasKey]
            if (global != null) return@withLock Resolved(global.value, Scope.GLOBAL)

            Resolved(input.trim(), null)
        }
    }

    suspend fun get(namespace: Namespace, scope: Scope, groupId: String, userId: String, alias: String): AliasEntry? {
        val key = normalizeAlias(alias)
        if (key.isEmpty()) return null
        return mutex.withLock {
            when (scope) {
                Scope.PERSONAL -> {
                    val normalizedUserId = normalizeUserId(userId)
                    readUserStoreUnsafe(normalizedUserId).namespace(namespace)[key]
                }

                Scope.GROUP -> {
                    val normalizedGroupId = normalizeGroupId(groupId)
                    readGroupStoreUnsafe(normalizedGroupId).shared.namespace(namespace)[key]
                }

                Scope.GLOBAL -> readGlobalStoreUnsafe().namespace(namespace)[key]
            }
        }
    }

    suspend fun set(
        namespace: Namespace,
        scope: Scope,
        groupId: String,
        userId: String,
        alias: String,
        value: String,
        operatorUserId: String,
    ) {
        val key = normalizeAlias(alias)
        val nickname = value.trim()
        require(key.isNotEmpty()) { "alias is blank" }
        require(nickname.isNotEmpty()) { "value is blank" }

        mutex.withLock {
            val now = System.currentTimeMillis()
            val entry = AliasEntry(value = nickname, updatedAt = now, updatedBy = normalizeUserId(operatorUserId))
            when (scope) {
                Scope.PERSONAL -> {
                    val normalizedUserId = normalizeUserId(userId)
                    val userStore = readUserStoreUnsafe(normalizedUserId)
                    userStore.namespace(namespace)[key] = entry
                    writeUserStoreUnsafe(normalizedUserId, userStore)
                }

                Scope.GROUP -> {
                    val normalizedGroupId = normalizeGroupId(groupId)
                    val groupStore = readGroupStoreUnsafe(normalizedGroupId)
                    groupStore.shared.namespace(namespace)[key] = entry
                    writeGroupStoreUnsafe(normalizedGroupId, groupStore)
                }

                Scope.GLOBAL -> {
                    val global = readGlobalStoreUnsafe()
                    global.namespace(namespace)[key] = entry
                    writeGlobalStoreUnsafe(global)
                }
            }
        }
    }

    suspend fun remove(namespace: Namespace, scope: Scope, groupId: String, userId: String, alias: String): Boolean {
        val key = normalizeAlias(alias)
        if (key.isEmpty()) return false
        return mutex.withLock {
            val removed = when (scope) {
                Scope.PERSONAL -> {
                    val normalizedUserId = normalizeUserId(userId)
                    val userStore = readUserStoreUnsafe(normalizedUserId)
                    val ok = userStore.namespace(namespace).remove(key) != null
                    if (ok) writeUserStoreUnsafe(normalizedUserId, userStore)
                    ok
                }

                Scope.GROUP -> {
                    val normalizedGroupId = normalizeGroupId(groupId)
                    val groupStore = readGroupStoreUnsafe(normalizedGroupId)
                    val ok = groupStore.shared.namespace(namespace).remove(key) != null
                    if (ok) writeGroupStoreUnsafe(normalizedGroupId, groupStore)
                    ok
                }

                Scope.GLOBAL -> {
                    val global = readGlobalStoreUnsafe()
                    val ok = global.namespace(namespace).remove(key) != null
                    if (ok) writeGlobalStoreUnsafe(global)
                    ok
                }
            }
            removed
        }
    }

    suspend fun listByAlias(namespace: Namespace, scope: Scope, groupId: String, userId: String): Map<String, String> {
        return mutex.withLock {
            val map: Map<String, AliasEntry> = when (scope) {
                Scope.PERSONAL -> {
                    val normalizedUserId = normalizeUserId(userId)
                    readUserStoreUnsafe(normalizedUserId).namespace(namespace)
                }

                Scope.GROUP -> {
                    val normalizedGroupId = normalizeGroupId(groupId)
                    readGroupStoreUnsafe(normalizedGroupId).shared.namespace(namespace)
                }

                Scope.GLOBAL -> readGlobalStoreUnsafe().namespace(namespace)
            }
            map.mapValues { it.value.value }.toSortedMap()
        }
    }

    suspend fun listManagers(groupId: String): Set<String> {
        return mutex.withLock {
            val normalizedGroupId = normalizeGroupId(groupId)
            readGroupStoreUnsafe(normalizedGroupId).managers.toSortedSet()
        }
    }

    suspend fun addManager(groupId: String, targetUserId: String): Boolean {
        val normalized = normalizeUserId(targetUserId)
        require(normalized.isNotEmpty()) { "userId is blank" }
        return mutex.withLock {
            val normalizedGroupId = normalizeGroupId(groupId)
            val groupStore = readGroupStoreUnsafe(normalizedGroupId)
            val changed = groupStore.managers.add(normalized)
            if (changed) writeGroupStoreUnsafe(normalizedGroupId, groupStore)
            changed
        }
    }

    suspend fun removeManager(groupId: String, targetUserId: String): Boolean {
        val normalized = normalizeUserId(targetUserId)
        if (normalized.isEmpty()) return false
        return mutex.withLock {
            val normalizedGroupId = normalizeGroupId(groupId)
            val groupStore = readGroupStoreUnsafe(normalizedGroupId)
            val changed = groupStore.managers.remove(normalized)
            if (changed) writeGroupStoreUnsafe(normalizedGroupId, groupStore)
            changed
        }
    }

    suspend fun isGroupManager(groupId: String, userId: String): Boolean {
        val normalized = normalizeUserId(userId)
        if (normalized.isEmpty()) return false
        return mutex.withLock {
            val normalizedGroupId = normalizeGroupId(groupId)
            readGroupStoreUnsafe(normalizedGroupId).managers.contains(normalized)
        }
    }

    private fun readGlobalStoreUnsafe(): NamespaceStore {
        ensureDirExists(storeDir)
        return readJsonUnsafe(globalPath, NamespaceStore())
    }

    private fun writeGlobalStoreUnsafe(store: NamespaceStore) {
        ensureDirExists(storeDir)
        writeJsonAtomicUnsafe(globalPath, store)
    }

    private fun readUserStoreUnsafe(normalizedUserId: String): NamespaceStore {
        if (normalizedUserId.isBlank()) return NamespaceStore()
        ensureDirExists(usersDir)
        val path = usersDir.resolve("$normalizedUserId.json")
        return readJsonUnsafe(path, NamespaceStore())
    }

    private fun writeUserStoreUnsafe(normalizedUserId: String, store: NamespaceStore) {
        if (normalizedUserId.isBlank()) return
        ensureDirExists(usersDir)
        val path = usersDir.resolve("$normalizedUserId.json")
        writeJsonAtomicUnsafe(path, store)
    }

    private fun readGroupStoreUnsafe(normalizedGroupId: String): GroupAliasStore {
        if (normalizedGroupId.isBlank()) return GroupAliasStore()
        ensureDirExists(groupsDir)
        val path = groupsDir.resolve("$normalizedGroupId.json")
        return readJsonUnsafe(path, GroupAliasStore())
    }

    private fun writeGroupStoreUnsafe(normalizedGroupId: String, store: GroupAliasStore) {
        if (normalizedGroupId.isBlank()) return
        ensureDirExists(groupsDir)
        val path = groupsDir.resolve("$normalizedGroupId.json")
        writeJsonAtomicUnsafe(path, store)
    }

    private fun ensureDirExists(dir: Path) {
        val f = dir.toFile()
        if (!f.exists()) f.mkdirs()
    }

    private inline fun <reified T> readJsonUnsafe(path: Path, default: T): T {
        if (!path.exists()) return default
        return try {
            val content = path.readText()
            if (content.isBlank()) default else json.decodeFromString(content)
        } catch (e: Exception) {
            log.error(e) { "Failed to read alias store: $path" }
            default
        }
    }

    private inline fun <reified T> writeJsonAtomicUnsafe(path: Path, value: T) {
        val parent = path.parent
        if (parent != null) ensureDirExists(parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        tmp.writeText(json.encodeToString(value))
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun normalizeAlias(input: String): String =
        input.trim().replace(Regex("\\s+"), "").lowercase()

    private fun normalizeUserId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return extractLastDigits(trimmed)
    }

    private fun normalizeGroupId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return extractFirstDigits(trimmed)
    }

    private fun extractLastDigits(input: String): String {
        return Regex("\\d+").findAll(input).toList().lastOrNull()?.value.orEmpty()
    }

    private fun extractFirstDigits(input: String): String {
        return Regex("\\d+").find(input)?.value.orEmpty()
    }

    private fun resolveStoreDir(): Path {
        val configuredDir = AppConfig.alias.storeDir?.trim()?.takeIf { it.isNotBlank() }
        if (configuredDir != null) {
            val p = java.nio.file.Path.of(configuredDir)
            return if (p.isAbsolute) p else PathUtils.currentDirectory.resolve(configuredDir)
        }
        return PathUtils.dataPathResolve("aliases")
    }

}

@Serializable
data class GroupAliasStore(
    @SerialName("shared")
    val shared: NamespaceStore = NamespaceStore(),
    @SerialName("managers")
    val managers: MutableSet<String> = mutableSetOf(),
)

@Serializable
data class NamespaceStore(
    @SerialName("player")
    val player: MutableMap<String, AliasEntry> = mutableMapOf(),
    @SerialName("character")
    val character: MutableMap<String, AliasEntry> = mutableMapOf(),
) {
    fun namespace(namespace: AliasService.Namespace): MutableMap<String, AliasEntry> =
        when (namespace) {
            AliasService.Namespace.PLAYER -> player
            AliasService.Namespace.CHARACTER -> character
        }
}

@Serializable
data class AliasEntry(
    @SerialName("value")
    val value: String,
    @SerialName("updatedAt")
    val updatedAt: Long = 0,
    @SerialName("updatedBy")
    val updatedBy: String = "",
)
