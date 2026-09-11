package eu.decentnewsroom.bookshelf.data.ratings

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import java.math.BigDecimal

/** R1 tag adapter for ratings of kind-30040 publications. Events must already be verified. */
object BookRatingEventParser {
    fun parse(event: NostrEvent): BookRatingParseResult {
        if (event.kind != BookKinds.RATING) return BookRatingParseResult.Rejected("Unexpected event kind.")
        val ratingTarget = parseRatingTarget(event.tags)
            ?: return BookRatingParseResult.Rejected("A kind-30040 reference is required in a or A.")
        val entityTypes = event.tags.valuesFor("m")
        if (entityTypes.size > 1 || ratingTarget.entityType?.let { type -> entityTypes.singleOrNull()?.let { it != type } == true } == true) {
            return BookRatingParseResult.Rejected("The optional m tag conflicts with the rating target.")
        }
        val producerRating = singleTagValue(event.tags, "rating")
            ?: return BookRatingParseResult.Rejected("A single rating tag is required.")
        val normalizedRating = parseStrictNormalizedRating(producerRating)
            ?: return BookRatingParseResult.Rejected("The rating must be a decimal between 0 and 1 inclusive.")
        return BookRatingParseResult.Accepted(BookRating(
            eventId = event.id,
            bookCoordinate = ratingTarget.coordinate,
            reviewerPubkey = event.pubkey.lowercase(),
            createdAt = event.createdAt,
            producerRating = producerRating,
            normalizedRating = normalizedRating,
            review = event.content,
            declaredEntityType = entityTypes.singleOrNull(),
            legacyStarTag = event.tags.valuesFor("s").singleOrNull(),
        ))
    }

    /** R1 splits only the first colon; the remainder is a 30040 coordinate. */
    fun parseBookTarget(target: String): Pair<String, String>? {
        val namespaceEnd = target.indexOf(':')
        if (namespaceEnd <= 0) return null
        val parts = target.substring(namespaceEnd + 1).split(':', limit = 3)
        if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_INDEX ||
            !HEX_64.matches(parts[1]) || parts[2].isBlank()
        ) return null
        return target.substring(0, namespaceEnd) to
            "${BookKinds.PUBLICATION_INDEX}:${parts[1].lowercase()}:${parts[2]}"
    }

    /** Supports producers that reference publications through Nostr a/A address tags. */
    private fun parseRatingTarget(tags: List<List<String>>): RatingTarget? {
        val addressTargets = (tags.valuesFor("a") + tags.valuesFor("A")).mapNotNull(::parsePublicationCoordinate).distinct()
        if (addressTargets.size != 1) return null
        val typedTargets = tags.valuesFor("d").mapNotNull(::parseBookTarget)
        if (typedTargets.any { it.second != addressTargets.single() }) return null
        val entityTypes = typedTargets.map { it.first }.distinct()
        if (entityTypes.size > 1) return null
        return RatingTarget(addressTargets.single(), entityTypes.singleOrNull())
    }

    private fun parsePublicationCoordinate(value: String): String? {
        val parts = value.split(':', limit = 3)
        if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_INDEX || !HEX_64.matches(parts[1]) || parts[2].isBlank()) return null
        return "${BookKinds.PUBLICATION_INDEX}:${parts[1].lowercase()}:${parts[2]}"
    }

    private fun parseStrictNormalizedRating(value: String): Double? {
        if (!DECIMAL.matches(value)) return null
        val decimal = value.toBigDecimalOrNull() ?: return null
        if (decimal < BigDecimal.ZERO || decimal > BigDecimal.ONE) return null
        return decimal.toDouble()
    }

    private fun singleTagValue(tags: List<List<String>>, name: String): String? = tags.valuesFor(name).singleOrNull()
    private fun List<List<String>>.valuesFor(name: String): List<String> =
        asSequence().filter { it.getOrNull(0) == name }.mapNotNull { it.getOrNull(1) }.distinct().toList()

    private val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
    private val DECIMAL = Regex("^(?:0(?:\\.\\d+)?|1(?:\\.0+)?)$")

    private data class RatingTarget(val coordinate: String, val entityType: String?)
}

sealed interface BookRatingParseResult {
    data class Accepted(val rating: BookRating) : BookRatingParseResult
    data class Rejected(val reason: String) : BookRatingParseResult
}
