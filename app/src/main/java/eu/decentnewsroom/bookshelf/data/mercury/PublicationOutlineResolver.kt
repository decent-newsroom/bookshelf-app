package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.*
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** All transport adapters must return events which have already crossed the signature boundary. */
internal class PublicationOutlineResolver(
    private val fetchIndex: suspend (PublicationChildReference) -> NostrEvent?,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxDepth: Int = 16,
        val maxIndexes: Int = 256,
        val maxNodes: Int = 5_000,
        val concurrentRequests: Int = 4,
        val timeoutMillis: Long = 60_000,
        val maxRetainedIndexBytes: Long = 8L * 1024 * 1024,
    ) {
        init {
            require(maxDepth >= 0 && maxIndexes >= 1 && maxNodes >= 1 && concurrentRequests in 1..4 && timeoutMillis > 0 && maxRetainedIndexBytes > 0)
        }
    }
    data class Result(
        val outline: List<PublicationOutlineNode>,
        val indexes: Map<String, NostrEvent>,
        val complete: Boolean,
        val fingerprint: String,
    )

    suspend fun resolve(root: BookSummary): Result {
        val nodes = mutableListOf<PublicationOutlineNode>()
        val selected = linkedMapOf<String, NostrEvent?>()
        val timedOutCoordinates = mutableSetOf<String>()
        val byteLimitedCoordinates = mutableSetOf<String>()
        var retainedIndexBytes = 0L
        fun retain(reference: PublicationChildReference, event: NostrEvent?) {
            // UTF-8 takes at most three bytes per UTF-16 code unit. Include decoded-object overhead.
            val bytes = event?.let {
                1024L + 3L * (it.content.length.toLong() + it.id.length + it.pubkey.length + it.sig.length +
                    it.tags.sumOf { tag -> 32L + tag.sumOf { value -> value.length.toLong() + 16L } })
            } ?: 0L
            if (event != null && bytes > limits.maxRetainedIndexBytes - retainedIndexBytes) {
                selected[reference.coordinate] = null
                byteLimitedCoordinates += reference.coordinate
            } else {
                selected[reference.coordinate] = event
                retainedIndexBytes += bytes
            }
        }
        val started = System.nanoTime()
        var complete = root.sectionStreamKnown || root.childRefs.isNotEmpty()
        var leaf = 0
        var exhausted = false
        val rootChildren = root.directChildren.take(limits.maxNodes)
        var queuedReferences = rootChildren.size
        suspend fun prefetch(children: List<PublicationChildReference>, depth: Int, ancestors: Set<String>) {
            if (depth >= limits.maxDepth) return
            val references = children.asSequence().take((limits.maxNodes - nodes.size).coerceAtLeast(0))
                .filter { it.kind == BookKinds.PUBLICATION_INDEX && validPublicationChild(it) && it.coordinate !in ancestors }
                .distinctBy { it.coordinate }.filter { !selected.containsKey(it.coordinate) }
                .take((limits.maxIndexes - 1 - selected.size).coerceAtLeast(0)).toList()
            for (batch in references.chunked(limits.concurrentRequests)) {
                val remaining = limits.timeoutMillis - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) return
                batch.forEach { selected[it.coordinate] = null }
                val results = coroutineScope {
                    batch.map { reference -> async {
                        withTimeoutOrNull(remaining) {
                            val event = try { fetchIndex(reference) } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) { null }
                            listOf(event?.takeIf { matchesPublicationChild(it, reference) })
                        }
                    } }.map { it.await() }
                }
                batch.zip(results).forEach { (reference, result) ->
                    retain(reference, result?.singleOrNull())
                    if (result == null) timedOutCoordinates += reference.coordinate
                }
            }
        }
        suspend fun expand(children: List<PublicationChildReference>, parent: String, depth: Int, ancestors: Set<String>) {
            prefetch(children, depth, ancestors)
            for (reference in children) {
                coroutineContext.ensureActive()
                if (exhausted) return
                queuedReferences--
                val key = publicationOccurrenceKey(parent, reference.coordinate)
                var node = PublicationOutlineNode(key, parent.takeUnless { it == root.coordinate }, reference, depth)
                fun block(reason: OutlineResolution, message: String) {
                    node = node.copy(resolution = reason, diagnostic = message)
                    complete = false
                }
                if (nodes.size >= limits.maxNodes) {
                    complete = false
                    nodes[nodes.lastIndex] = nodes.last().copy(resolution = OutlineResolution.LIMIT,
                        diagnostic = "Publication outline node limit reached; remaining entries omitted.")
                    exhausted = true
                    return
                }
                if (!validPublicationChild(reference)) {
                    block(OutlineResolution.MALFORMED, "Invalid publication child reference.")
                    nodes += node
                    continue
                }
                if (reference.kind == BookKinds.PUBLICATION_CONTENT) {
                    nodes += node.copy(chapterIndex = leaf++)
                    continue
                }
                when {
                    reference.coordinate in ancestors -> block(OutlineResolution.CYCLE, "Cyclic publication index reference.")
                    depth >= limits.maxDepth -> block(OutlineResolution.LIMIT, "Publication index depth limit reached.")
                    !selected.containsKey(reference.coordinate) && selected.size + 1 >= limits.maxIndexes ->
                        block(OutlineResolution.LIMIT, "Publication index count limit reached.")
                    else -> {
                        val remaining = limits.timeoutMillis - (System.nanoTime() - started) / 1_000_000
                        if (remaining <= 0 && !selected.containsKey(reference.coordinate)) {
                            block(OutlineResolution.TIMEOUT, "Publication outline resolution timed out.")
                        } else {
                            var timedOut = reference.coordinate in timedOutCoordinates
                            if (!selected.containsKey(reference.coordinate)) {
                                val attempt = withTimeoutOrNull(remaining) {
                                    val event = try { fetchIndex(reference) } catch (failure: CancellationException) {
                                        throw failure
                                    } catch (_: Exception) { null }
                                    listOf(event?.takeIf { matchesPublicationChild(it, reference) })
                                }
                                timedOut = attempt == null
                                retain(reference, attempt?.singleOrNull())
                            }
                            val event = selected[reference.coordinate]
                            if (event == null) {
                                val byteLimited = reference.coordinate in byteLimitedCoordinates
                                block(if (byteLimited) OutlineResolution.LIMIT else if (timedOut) OutlineResolution.TIMEOUT else OutlineResolution.UNAVAILABLE,
                                    if (byteLimited) "Publication index byte budget reached."
                                    else if (timedOut) "Publication outline resolution timed out." else "Publication index is unavailable.")
                            } else {
                                node = node.copy(title = publicationIndexTitle(event, reference.identifier), eventId = event.id)
                                val capacity = (limits.maxNodes - nodes.size - 1 - queuedReferences).coerceAtLeast(0)
                                val parsed = parsePublicationChildren(event.tags, capacity + 1)
                                if (parsed.size > capacity) {
                                    block(OutlineResolution.LIMIT, "Publication outline reference budget reached; remaining descendants omitted.")
                                }
                                nodes += node
                                val descendants = parsed.take(capacity)
                                queuedReferences += descendants.size
                                expand(descendants, key, depth + 1, ancestors + reference.coordinate)
                                continue
                            }
                        }
                    }
                }
                nodes += node
            }
        }
        expand(rootChildren, root.coordinate, 0, setOf(root.coordinate))
        if (root.directChildren.size > limits.maxNodes) {
            complete = false
            nodes[nodes.lastIndex] = nodes.last().copy(resolution = OutlineResolution.LIMIT,
                diagnostic = "Publication outline node limit reached; remaining entries omitted.")
        }
        val fingerprintSource = buildString {
            append(root.id)
            nodes.forEach { append('\u0000').append(it.occurrenceKey).append(':').append(it.eventId).append(':').append(it.resolution) }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(fingerprintSource.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Result(nodes, selected.mapNotNull { (coordinate, event) -> event?.let { coordinate to it } }.toMap(), complete, fingerprint)
    }
}

internal fun parsePublicationChildren(tags: List<List<String>>, limit: Int = 5_000): List<PublicationChildReference> {
    val seen = mutableSetOf<String>()
    return buildList {
        for (tag in tags) {
            if (tag.firstOrNull() != "a") continue
            val parts = tag.getOrNull(1).orEmpty().split(':', limit = 3)
            val kind = parts.firstOrNull()?.toIntOrNull() ?: continue
            if (kind != BookKinds.PUBLICATION_INDEX && kind != BookKinds.PUBLICATION_CONTENT) continue
            val pubkey = parts.getOrNull(1).orEmpty().lowercase()
            val identifier = parts.getOrNull(2).orEmpty()
            val coordinate = "$kind:$pubkey:$identifier"
            if (!seen.add(coordinate)) continue
            val relay = tag.getOrNull(2)?.takeIf { value ->
                runCatching { URI(value).let { it.scheme == "wss" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
            }
            add(PublicationChildReference(kind, coordinate, pubkey, identifier, relay,
                tag.getOrNull(3)?.lowercase()?.takeIf { CHILD_HEX.matches(it) }))
            if (size >= limit) break
        }
    }
}

internal fun validPublicationChild(ref: PublicationChildReference): Boolean =
    ref.kind in setOf(BookKinds.PUBLICATION_INDEX, BookKinds.PUBLICATION_CONTENT) &&
        CHILD_HEX.matches(ref.pubkey) && ref.identifier.isNotBlank() &&
        ref.coordinate == "${ref.kind}:${ref.pubkey}:${ref.identifier}"

internal fun matchesPublicationChild(event: NostrEvent, ref: PublicationChildReference): Boolean =
    event.kind == ref.kind && event.pubkey.lowercase() == ref.pubkey &&
        event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) == ref.identifier && event.id.isNotBlank()

internal fun publicationIndexTitle(event: NostrEvent, fallback: String): String =
    listOf("title", "T").firstNotNullOfOrNull { name ->
        event.tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
    } ?: fallback

private val CHILD_HEX = Regex("^[a-f0-9]{64}$")
