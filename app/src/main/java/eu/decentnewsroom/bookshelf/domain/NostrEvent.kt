package eu.decentnewsroom.bookshelf.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NostrEvent(
    val id: String = "",
    val pubkey: String = "",
    @SerialName("created_at")
    val createdAt: Long = 0,
    val kind: Int = 0,
    val tags: List<List<String>> = emptyList(),
    val content: String = "",
    val sig: String = "",
)
