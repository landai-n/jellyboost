package dev.jellyboost.core.common.model

/**
 * One credited person on an item — an actor, a director, a writer …
 *
 * Only fetched on the detail path (the `PEOPLE` item field); list requests stay lean, so cards
 * never carry this (docs/PLAN.md, "Screens" → ItemDetail).
 */
data class Person(
    val id: String,
    val name: String,
    /** The character an [PersonKind.ACTOR] plays, when the server knows it. */
    val role: String? = null,
    val kind: PersonKind = PersonKind.OTHER,
    val primaryImageUrl: String? = null,
)

/**
 * The credit kinds the detail screen distinguishes.
 *
 * Everything the Jellyfin server can return that v1 has no use for (composer, lyricist, the whole
 * music and comic-book set) collapses into [OTHER] rather than leaking an SDK enum into the UI.
 */
enum class PersonKind {
    ACTOR,
    DIRECTOR,
    WRITER,
    PRODUCER,
    GUEST_STAR,
    OTHER,
}
