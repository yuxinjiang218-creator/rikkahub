package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface KeyCursorStore {
    fun get(scopeKey: String): Int
    fun put(scopeKey: String, nextIndex: Int)
}

interface KeyRoulette {
    fun next(keys: String, providerId: String = DEFAULT_SCOPE): String

    companion object {
        fun default(cursorStore: KeyCursorStore? = null): KeyRoulette = DefaultKeyRoulette(cursorStore)

        /**
         * LRU rotation persisted to cacheDir/lru_key_roulette.json.
         * providerId separates multiple provider instances of the same type.
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()
private const val DEFAULT_SCOPE = "default"

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private class DefaultKeyRoulette(
    private val cursorStore: KeyCursorStore? = null
) : KeyRoulette {
    private val memoryCursor = ConcurrentHashMap<String, Int>()

    @Synchronized
    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        val scopeKey = buildScopeKey(providerId, keyList)
        val rawIndex = memoryCursor[scopeKey] ?: cursorStore?.get(scopeKey) ?: 0
        val index = Math.floorMod(rawIndex, keyList.size)
        val nextIndex = (index + 1) % keyList.size

        memoryCursor[scopeKey] = nextIndex
        cursorStore?.put(scopeKey, nextIndex)
        return keyList[index]
    }

    private fun buildScopeKey(scope: String, keyList: List<String>): String {
        val normalized = keyList.joinToString(separator = "\n")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
        return "$scope|$hash"
    }
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 day

// Prevent multiple provider instances from racing on the same cache file.
private object LruFileLock

// File structure: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()

            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            val selected = keyList.firstOrNull { it !in providerCache }
                ?: providerCache.minByOrNull { it.value }!!.key

            providerCache[selected] = now
            allCache[providerId] = providerCache

            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selected
        }
    }

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
