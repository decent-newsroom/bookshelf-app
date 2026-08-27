package eu.decentnewsroom.bookshelf.data.nostr

import android.content.Context
import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class NostrProfile(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val createdAt: Long,
) {
    val preferredName: String?
        get() = displayName ?: name
}

interface NostrProfileSource {
    suspend fun cachedProfile(pubkey: String): NostrProfile?
    suspend fun refreshProfile(pubkey: String): NostrProfile?
}

class NostrProfileRepository(
    private val relayClient: NostrRelayClient,
    private val cache: NostrProfileCache,
) : NostrProfileSource {
    override suspend fun cachedProfile(pubkey: String): NostrProfile? =
        cache.read(pubkey)?.toProfile()

    override suspend fun refreshProfile(pubkey: String): NostrProfile? {
        val event = relayClient.fetchLatestProfile(pubkey) ?: return null
        val profile = event.toProfile() ?: return null
        cache.write(event)
        return profile
    }
}

class NostrProfileCache private constructor(
    private val cacheDirectory: File,
) {
    constructor(context: Context) : this(cacheDirectory(context.applicationContext.cacheDir))

    internal constructor(cacheRoot: File, cacheDirectoryName: String = CACHE_DIRECTORY) :
        this(File(cacheRoot, cacheDirectoryName))

    suspend fun read(pubkey: String): NostrEvent? = withContext(Dispatchers.IO) {
        val normalizedPubkey = normalizePubkey(pubkey)
        runCatching {
            val file = profileFile(normalizedPubkey)
            if (!file.isFile) return@runCatching null
            json.decodeFromString<NostrEvent>(file.readText(Charsets.UTF_8))
                .takeIf { it.isProfileFor(normalizedPubkey) }
        }.getOrNull()
    }

    suspend fun write(event: NostrEvent) = withContext(Dispatchers.IO) {
        val normalizedPubkey = normalizePubkey(event.pubkey)
        require(event.isProfileFor(normalizedPubkey)) { "Only kind:0 profile events can be cached." }

        cacheDirectory.mkdirs()
        val file = profileFile(normalizedPubkey)
        val temporary = File(cacheDirectory, file.name + ".tmp")
        temporary.writeText(json.encodeToString(NostrEvent.serializer(), event), Charsets.UTF_8)
        replaceFile(temporary, file)
    }

    private fun profileFile(pubkey: String): File = File(cacheDirectory, "$pubkey.json")

    private fun replaceFile(temporary: File, destination: File) {
        runCatching {
            Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
        }.getOrElse {
            temporary.delete()
            throw IllegalStateException("Could not update Nostr profile cache.", it)
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "nostr-profiles/v1"
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun cacheDirectory(cacheRoot: File): File = File(cacheRoot, CACHE_DIRECTORY)

        fun normalizePubkey(pubkey: String): String =
            pubkey.trim().lowercase().also {
                require(HEX_64.matches(it)) { "Nostr profile pubkey is invalid." }
            }
    }
}

internal fun NostrEvent.toProfile(): NostrProfile? {
    if (kind != BookKinds.PROFILE_METADATA) return null
    val metadata = runCatching { profileJson.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return null
    val name = metadata.profileText("name")
    val displayName = metadata.profileText("display_name") ?: metadata.profileText("displayName")
    return NostrProfile(
        pubkey = pubkey.lowercase(),
        name = name,
        displayName = displayName,
        createdAt = createdAt,
    )
}

private fun JsonObject.profileText(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(MAX_PROFILE_NAME_LENGTH)
        ?.takeIf(String::isNotBlank)

private fun NostrEvent.isProfileFor(pubkey: String): Boolean =
    kind == BookKinds.PROFILE_METADATA && this.pubkey.equals(pubkey, ignoreCase = true)

private const val MAX_PROFILE_NAME_LENGTH = 80
private val profileJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
