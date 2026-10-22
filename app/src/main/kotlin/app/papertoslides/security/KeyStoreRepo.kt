package app.papertoslides.security

import app.papertoslides.core.keypool.HealthRegistry
import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.provider.KeyToken
import app.papertoslides.core.provider.Providers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredKey(val id: String, val key: String, val addedAt: Long, val label: String = "", val provider: String = Providers.GEMINI.id) {
    /** What the scheduler carries for this key (bare key for Gemini, `provider|key` otherwise). */
    val token get() = KeyToken.make(provider, key)
}

/** What the UI may see about a key: never the key itself. */
data class KeyView(val id: String, val masked: String, val hash: String, val addedAt: Long, val provider: String = Providers.GEMINI.id)

/**
 * The sealed key list lives in a private file under filesDir. If it cannot be opened (key invalidated, restored
 * phone, bad tag) the vault is treated as empty and the user is asked for the keys again; never a crash.
 */
class KeyStoreRepo(private val dir: File) {
    private val file = File(dir, "keys.sealed")
    private val healthFile = File(dir, "key_health.json") // hash-keyed only
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cache: List<StoredKey>? = null
    var lastError: String? = null
        private set

    @Synchronized fun all(): List<StoredKey> {
        cache?.let { return it }
        val list = if (!file.exists()) emptyList() else try {
            json.decodeFromString<List<StoredKey>>(KeyVault.open(file.readBytes()).decodeToString())
        } catch (e: Exception) {
            lastError = "Your saved keys could not be unlocked on this phone. Please add them again."
            file.delete()
            emptyList()
        }
        cache = list
        return list
    }

    fun views(): List<KeyView> = all().map { KeyView(it.id, KeyLanes.mask(it.key), KeyLanes.khash(it.token), it.addedAt, it.provider) }

    /** Lane keys of every stored key, all providers. */
    fun secrets(): List<String> = all().map { it.token }

    /** Adds every distinct key found in [pasted] for [provider]; returns how many were new. */
    @Synchronized fun add(pasted: String, provider: String = Providers.GEMINI.id): Int {
        val current = all()
        val fresh = KeyLanes.splitKeys(pasted, if (provider == Providers.CUSTOM.id) 8 else 20).filter { k -> current.none { it.key == k && it.provider == provider } }
        if (fresh.isEmpty()) return 0
        val now = System.currentTimeMillis()
        save(current + fresh.mapIndexed { i, k -> StoredKey(KeyLanes.khash(k) + now + i, k, now, provider = provider) })
        return fresh.size
    }

    @Synchronized fun remove(id: String) = save(all().filter { it.id != id })

    private fun save(list: List<StoredKey>) {
        dir.mkdirs()
        val tmp = File(dir, "keys.sealed.tmp")
        tmp.writeBytes(KeyVault.seal(json.encodeToString(list).encodeToByteArray()))
        tmp.renameTo(file)
        cache = list
    }

    @Synchronized fun health(): HealthRegistry =
        runCatching { json.decodeFromString<HealthRegistry>(healthFile.readText()) }.getOrElse { HealthRegistry() }

    @Synchronized fun saveHealth(h: HealthRegistry) {
        runCatching { healthFile.writeText(json.encodeToString(h)) }
    }
}
